package com.omnicore.pke.engine.ingest.journey;

import com.omnicore.pke.engine.graph.CodeQueryService;
import com.omnicore.pke.engine.graph.InterviewSessionStore;
import com.omnicore.pke.engine.kernel.ConflictException;
import com.omnicore.pke.engine.kernel.EpistemicStatus;
import com.omnicore.pke.engine.kernel.PkeIds;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.pipeline.skill.SkillResult;
import com.omnicore.pke.engine.pipeline.skill.SkillRunner;
import com.omnicore.pke.engine.store.GraphStore;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeResult;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityRef;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityWrite;
import com.omnicore.pke.engine.store.KnowledgeChanges.EvidenceInput;
import com.omnicore.pke.engine.store.KnowledgeChanges.RelationshipWrite;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * The "interview" journey source (M6): the {@code journey-interview} skill asks one question per turn until the
 * journey is complete; the person answers in chat (MCP). Each step's API is then resolved deterministically against
 * the graph's ApiOperations: one match is linked, several (or none) become a question to the person. When every step
 * is resolved the journey is written through the single writer as {@code HUMAN_VERIFIED} knowledge, with the same
 * shape as journeys from tests and collections ({@code UserJourney -HAS_STEP-> JourneyStep -CALLS_API-> ApiOperation}).
 * The session ({@code :InterviewSession}) holds the transcript, the draft and the pending question between turns.
 */
@Service
public class JourneyInterviewService {

    static final String SKILL = "journey-interview";
    static final String METHOD = JourneyKnowledgeMapper.METHOD_PREFIX + "interview";
    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9-]{1,79}");
    private static final int MAX_APIS = 300;
    private static final int MAX_CANDIDATES = 10;

    private final SkillRunner skills;
    private final CodeQueryService code;
    private final InterviewSessionStore sessions;
    private final GraphStore writer;

    public JourneyInterviewService(SkillRunner skills, CodeQueryService code, InterviewSessionStore sessions,
                                   GraphStore writer) {
        this.skills = skills;
        this.code = code;
        this.sessions = sessions;
        this.writer = writer;
    }

    /**
     * One turn: {@code ASKING} with the next {@code question}, or {@code COMPLETED} with the written journey.
     * {@code steps} is the draft so far (order, name, method, path, apiId when resolved).
     */
    public record Turn(String sessionId, String journeyKey, String status, String question,
                       List<Map<String, Object>> steps, String journeyId, Integer eventsWritten) {
    }

    public Turn start(String journeyKey, String name, String description, String actor) {
        if (journeyKey == null || !KEY.matcher(journeyKey).matches()) {
            throw ValidationFailedException.of("/journeyKey", "must be 2-80 lower-case letters, digits or '-'");
        }
        Map<String, Object> journey = new LinkedHashMap<>();
        journey.put("key", journeyKey);
        journey.put("name", name == null || name.isBlank() ? JourneyKnowledgeMapper.humanise(journeyKey) : name.strip());
        if (description != null && !description.isBlank()) {
            journey.put("description", description.strip());
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("journey", journey);
        state.put("transcript", new ArrayList<>());
        state.put("resolved", new LinkedHashMap<>());
        return advance(PkeIds.uuidV7().toString(), state, actor);
    }

    public Turn answer(String sessionId, String answer, String actor) {
        Map<String, Object> session = sessions.get(sessionId);
        if (!"ASKING".equals(session.get("status"))) {
            throw new ConflictException("Interview " + sessionId + " is " + session.get("status"));
        }
        if (answer == null || answer.isBlank()) {
            throw ValidationFailedException.of("/answer", "is required");
        }
        Map<String, Object> state = mutable(session.get("state"));
        Map<String, Object> pending = mutable(state.remove("pending"));
        if ("API".equals(pending.get("kind"))) {
            List<String> candidates = strings(pending.get("candidates"));
            String choice = choose(answer.strip(), candidates);
            if (choice == null) {
                pending.put("question", "Please answer with a number from 1 to " + candidates.size()
                        + " or 'none'. " + pending.get("prompt"));
                state.put("pending", pending);
                sessions.save(sessionId, journeyKey(state), "ASKING", state, actor);
                return turn(sessionId, state, "ASKING", (String) pending.get("question"), null, null);
            }
            Map<String, Object> resolved = mutable(state.get("resolved"));
            resolved.put(String.valueOf(pending.get("stepOrder")), choice);
            state.put("resolved", resolved);
        } else {
            List<Object> transcript = new ArrayList<>(list(state.get("transcript")));
            transcript.add(Map.of("question", pending.get("question"), "answer", answer.strip()));
            state.put("transcript", transcript);
        }
        return advance(sessionId, state, actor);
    }

    public Turn get(String sessionId) {
        Map<String, Object> session = sessions.get(sessionId);
        Map<String, Object> state = mutable(session.get("state"));
        Map<String, Object> pending = mutable(state.get("pending"));
        return turn(sessionId, state, (String) session.get("status"), (String) pending.get("question"),
                (String) state.get("journeyId"), null);
    }

    private Turn advance(String sessionId, Map<String, Object> state, String actor) {
        if (state.get("draft") == null) {
            Map<String, Object> output = ask(state);
            if (!"COMPLETE".equals(output.get("status"))) {
                state.put("pending", Map.of("kind", "MODEL", "question", String.valueOf(output.get("question"))));
                sessions.save(sessionId, journeyKey(state), "ASKING", state, actor);
                return turn(sessionId, state, "ASKING", String.valueOf(output.get("question")), null, null);
            }
            Map<String, Object> draft = new LinkedHashMap<>();
            draft.put("description", output.get("description"));
            draft.put("steps", output.get("steps") == null ? List.of() : output.get("steps"));
            draft.put("skillVersion", state.remove("lastSkillVersion"));
            state.put("draft", draft);
        }
        Map<String, Object> resolved = mutable(state.get("resolved"));
        state.put("resolved", resolved);
        List<Map<String, Object>> apis = code.apiCatalogue(MAX_APIS);
        for (Map<String, Object> step : steps(state)) {
            String order = String.valueOf(number(step.get("order")));
            if (resolved.containsKey(order)) {
                continue;
            }
            List<Map<String, Object>> candidates = candidates(step, apis);
            if (candidates.size() == 1) {
                resolved.put(order, candidates.getFirst().get("id"));
                continue;
            }
            Map<String, Object> pending = apiQuestion(step, candidates, apis);
            state.put("pending", pending);
            sessions.save(sessionId, journeyKey(state), "ASKING", state, actor);
            return turn(sessionId, state, "ASKING", (String) pending.get("question"), null, null);
        }
        if (steps(state).isEmpty()) {
            throw ValidationFailedException.of("/steps", "the interview completed without any step");
        }
        ChangeResult result = writer.apply(changes(sessionId, state), actor);
        String journeyId = PkeIds.entityId("UserJourney", List.of(journeyKey(state)));
        state.put("journeyId", journeyId);
        sessions.save(sessionId, journeyKey(state), "COMPLETED", state, actor);
        return turn(sessionId, state, "COMPLETED", null, journeyId, result.eventCount());
    }

    private Map<String, Object> ask(Map<String, Object> state) {
        List<Map<String, Object>> apis = code.apiCatalogue(MAX_APIS).stream().map(a -> {
            Map<String, Object> api = new LinkedHashMap<>();
            api.put("layer", a.get("layer"));
            api.put("service", a.get("service"));
            api.put("method", a.get("method"));
            api.put("path", a.get("path"));
            return api;
        }).toList();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("journey", state.get("journey"));
        input.put("transcript", state.get("transcript"));
        input.put("apis", apis);
        SkillResult result = skills.run(SKILL, input, null, "journey-interview");
        if (result.skipped()) {
            throw new ConflictException("The journey interview needs a chat model; none is configured");
        }
        state.put("lastSkillVersion", result.provenance().skillVersion());
        return result.output();
    }

    /** Exact path, then path suffix, then path containment; always the same HTTP method. */
    static List<Map<String, Object>> candidates(Map<String, Object> step, List<Map<String, Object>> apis) {
        String method = String.valueOf(step.get("method")).toUpperCase(Locale.ROOT);
        String path = String.valueOf(step.get("path"));
        List<Predicate<String>> rules = List.of(path::equals, p -> p.endsWith(path), p -> p.contains(path));
        for (Predicate<String> rule : rules) {
            List<Map<String, Object>> matches = apis.stream()
                    .filter(a -> method.equals(a.get("method")) && rule.test(String.valueOf(a.get("path")))).toList();
            if (!matches.isEmpty()) {
                return matches;
            }
        }
        return List.of();
    }

    private static Map<String, Object> apiQuestion(Map<String, Object> step, List<Map<String, Object>> candidates,
                                                   List<Map<String, Object>> apis) {
        List<Map<String, Object>> options = candidates.isEmpty()
                ? apis.stream().filter(a -> String.valueOf(step.get("method")).equalsIgnoreCase(String.valueOf(a.get("method"))))
                        .limit(MAX_CANDIDATES).toList()
                : candidates.stream().limit(MAX_CANDIDATES).toList();
        StringBuilder prompt = new StringBuilder("Step ").append(number(step.get("order"))).append(" '")
                .append(step.get("name")).append("' (").append(step.get("method")).append(' ').append(step.get("path"))
                .append(candidates.isEmpty() ? ") matches no known API." : ") matches " + candidates.size() + " APIs.")
                .append(" Which API does it call?");
        for (int i = 0; i < options.size(); i++) {
            Map<String, Object> o = options.get(i);
            prompt.append(' ').append(i + 1).append(") ").append(o.get("method")).append(' ').append(o.get("path"))
                    .append(" [").append(o.get("layer")).append(' ').append(o.get("service")).append(']');
        }
        prompt.append(". Answer with the number, or 'none' to keep the step without an API.");
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("kind", "API");
        pending.put("stepOrder", number(step.get("order")));
        pending.put("candidates", options.stream().map(o -> o.get("id")).toList());
        pending.put("prompt", prompt.toString());
        pending.put("question", prompt.toString());
        return pending;
    }

    /** The chosen candidate id, {@code "none"}, or null when the answer is not a valid choice. */
    static String choose(String answer, List<String> candidates) {
        if (answer.equalsIgnoreCase("none")) {
            return "none";
        }
        if (candidates.contains(answer)) {
            return answer;
        }
        try {
            int index = Integer.parseInt(answer.replaceAll("[^0-9]", ""));
            return index >= 1 && index <= candidates.size() ? candidates.get(index - 1) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ChangeSet changes(String sessionId, Map<String, Object> state) {
        Map<String, Object> journey = mutable(state.get("journey"));
        Map<String, Object> draft = mutable(state.get("draft"));
        Map<String, Object> resolved = mutable(state.get("resolved"));
        String key = journeyKey(state);
        String skillVersion = draft.get("skillVersion") == null ? null : SKILL + "@" + draft.get("skillVersion");
        String source = "interview:" + sessionId;
        Map<String, Object> journeyProps = new LinkedHashMap<>();
        journeyProps.put("journeyKey", key);
        journeyProps.put("name", journey.get("name"));
        Object description = draft.get("description") != null ? draft.get("description") : journey.get("description");
        if (description != null) {
            journeyProps.put("description", description);
        }
        EntityRef journeyRef = EntityRef.key("UserJourney", Map.of("journeyKey", key));
        List<EntityWrite> entities = new ArrayList<>();
        List<RelationshipWrite> relationships = new ArrayList<>();
        entities.add(new EntityWrite("UserJourney", null, journeyProps, EpistemicStatus.HUMAN_VERIFIED, null,
                List.of(evidence(source, "journey", String.valueOf(journey.get("name")), skillVersion))));
        for (Map<String, Object> step : steps(state)) {
            long order = number(step.get("order"));
            EvidenceInput evidence = evidence(source, "step " + order,
                    step.get("name") + " (" + step.get("method") + " " + step.get("path") + ")", skillVersion);
            Map<String, Object> stepKey = Map.of("journeyKey", key, "order", order);
            entities.add(new EntityWrite("JourneyStep", null, stepKey, EpistemicStatus.HUMAN_VERIFIED, null, List.of(evidence)));
            EntityRef stepRef = EntityRef.key("JourneyStep", stepKey);
            relationships.add(new RelationshipWrite("HAS_STEP", journeyRef, stepRef, Map.of("order", order),
                    EpistemicStatus.HUMAN_VERIFIED, null, List.of(evidence)));
            String apiId = (String) resolved.get(String.valueOf(order));
            if (apiId != null && !"none".equals(apiId)) {
                relationships.add(new RelationshipWrite("CALLS_API", stepRef, EntityRef.id(apiId), Map.of(),
                        EpistemicStatus.HUMAN_VERIFIED, null, List.of(evidence)));
            }
        }
        return new ChangeSet("interview:" + sessionId, entities, relationships, List.of());
    }

    private static EvidenceInput evidence(String source, String location, String excerpt, String skillVersion) {
        // scoped by the session: re-running the same interview adds no evidence; journey views list it as a source
        return new EvidenceInput(source, null, location, null, METHOD, null, skillVersion, null, null,
                excerpt.length() > 2000 ? excerpt.substring(0, 2000) : excerpt, "HUMAN_VERIFIED", null,
                EpistemicStatus.HUMAN_VERIFIED, source);
    }

    private Turn turn(String sessionId, Map<String, Object> state, String status, String question, String journeyId,
                      Integer events) {
        Map<String, Object> resolved = mutable(state.get("resolved"));
        List<Map<String, Object>> steps = steps(state).stream().map(s -> {
            Map<String, Object> view = new LinkedHashMap<>(s);
            Object api = resolved.get(String.valueOf(number(s.get("order"))));
            if (api != null) {
                view.put("apiId", api);
            }
            return view;
        }).toList();
        return new Turn(sessionId, journeyKey(state), status, question, steps,
                journeyId != null ? journeyId : (String) state.get("journeyId"), events);
    }

    private static List<Map<String, Object>> steps(Map<String, Object> state) {
        Map<String, Object> draft = mutable(state.get("draft"));
        List<Map<String, Object>> steps = new ArrayList<>();
        list(draft.get("steps")).forEach(s -> steps.add(mutable(s)));
        steps.sort(java.util.Comparator.comparingLong(s -> number(s.get("order"))));
        return steps;
    }

    private static String journeyKey(Map<String, Object> state) {
        return String.valueOf(mutable(state.get("journey")).get("key"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutable(Object value) {
        return value == null ? new LinkedHashMap<>() : value instanceof LinkedHashMap<?, ?> m
                ? (Map<String, Object>) m : new LinkedHashMap<>((Map<String, Object>) value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }

    private static List<String> strings(Object value) {
        return list(value).stream().map(String::valueOf).toList();
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }
}

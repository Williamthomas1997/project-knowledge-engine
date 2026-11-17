package com.omnicore.pke.engine.api.mcp;

import com.omnicore.pke.engine.graph.CodeQueryService;
import com.omnicore.pke.engine.graph.CodeQueryService.JourneySummary;
import com.omnicore.pke.engine.graph.CodeQueryService.JourneyView;
import com.omnicore.pke.engine.graph.EvolutionRequestStore;
import com.omnicore.pke.engine.graph.KnowledgeQueryService;
import com.omnicore.pke.engine.graph.KnowledgeQueryService.EntityView;
import com.omnicore.pke.engine.graph.PipelineExecutionStore.ExecutionView;
import com.omnicore.pke.engine.graph.QuestionStore.QuestionView;
import com.omnicore.pke.engine.graph.RetrievalQueries;
import com.omnicore.pke.engine.graph.SourceQueryService;
import com.omnicore.pke.engine.graph.SourceQueryService.SectionView;
import com.omnicore.pke.engine.history.SnapshotService;
import com.omnicore.pke.engine.history.SnapshotService.SnapshotDiff;
import com.omnicore.pke.engine.ingest.journey.JourneyInterviewService;
import com.omnicore.pke.engine.ingest.journey.JourneyInterviewService.Turn;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.pipeline.PipelineExecutor;
import com.omnicore.pke.engine.pipeline.skill.SkillRegistry;
import com.omnicore.pke.engine.reconcile.QuestionService;
import com.omnicore.pke.engine.retrieval.ContextService;
import com.omnicore.pke.engine.retrieval.ContextService.ContextResult;
import com.omnicore.pke.engine.retrieval.GuardedCypher;
import com.omnicore.pke.engine.retrieval.LogTrailService;
import com.omnicore.pke.engine.retrieval.LogTrailService.Explanation;
import com.omnicore.pke.engine.retrieval.QueryTemplates;
import com.omnicore.pke.engine.retrieval.RetrievalService;
import com.omnicore.pke.engine.retrieval.RetrievalService.SearchResult;
import com.omnicore.pke.engine.schema.DefinitionKind;
import com.omnicore.pke.engine.schema.SchemaService;
import com.omnicore.pke.engine.security.Access;
import com.omnicore.pke.engine.security.ExecutionPolicies;
import com.omnicore.pke.engine.security.Role;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The MCP tools (M6, {@code docs/mcp-tools.md}). Each tool calls the same application service as the matching REST
 * endpoint and returns the same value, so REST and MCP answers are equivalent. The HTTP endpoint already requires a
 * READER key; tools that change something require CONTRIBUTOR, are checked against the execution policy where they
 * start work, and write an audit event. No tool executes code.
 */
@Component
class KnowledgeTools {

    private final RetrievalService retrieval;
    private final ContextService context;
    private final KnowledgeQueryService knowledge;
    private final RetrievalQueries graph;
    private final QueryTemplates templates;
    private final CodeQueryService code;
    private final SourceQueryService sources;
    private final SnapshotService snapshots;
    private final SchemaService schema;
    private final SkillRegistry skills;
    private final QuestionService questions;
    private final PipelineExecutor pipelines;
    private final LogTrailService logTrail;
    private final EvolutionRequestStore evolution;
    private final JourneyInterviewService interviews;
    private final Access access;
    private final ExecutionPolicies policies;

    KnowledgeTools(RetrievalService retrieval, ContextService context, KnowledgeQueryService knowledge,
                   RetrievalQueries graph, QueryTemplates templates, CodeQueryService code, SourceQueryService sources,
                   SnapshotService snapshots, SchemaService schema, SkillRegistry skills, QuestionService questions,
                   PipelineExecutor pipelines, LogTrailService logTrail, EvolutionRequestStore evolution,
                   JourneyInterviewService interviews, Access access, ExecutionPolicies policies) {
        this.retrieval = retrieval;
        this.context = context;
        this.knowledge = knowledge;
        this.graph = graph;
        this.templates = templates;
        this.code = code;
        this.sources = sources;
        this.snapshots = snapshots;
        this.schema = schema;
        this.skills = skills;
        this.questions = questions;
        this.pipelines = pipelines;
        this.logTrail = logTrail;
        this.evolution = evolution;
        this.interviews = interviews;
        this.access = access;
        this.policies = policies;
    }

    // ---- reading (READER) ----

    @McpTool(name = "search_knowledge", annotations = @McpAnnotations(readOnlyHint = true),
            description = "Hybrid (keyword + vector) search over the knowledge graph: knowledge cards, APIs, methods, "
                    + "log statements and more. Returns ranked hits with their entity ids and types.")
    SearchResult searchKnowledge(
            @McpToolParam(description = "What to look for, in plain words") String query,
            @McpToolParam(description = "Number of hits, default 10", required = false) Integer k,
            @McpToolParam(description = "Only these entity types, e.g. ApiOperation, KnowledgeCard", required = false)
            List<String> types) {
        return retrieval.search(new RetrievalService.SearchRequest(query, k, types, null, null, null, null, null));
    }

    @McpTool(name = "get_task_context", annotations = @McpAnnotations(readOnlyHint = true),
            description = "Context for a coding task: relevant requirements, APIs, code, journeys and open questions, "
                    + "each fact with a citation, within a token budget. Call this before changing code.")
    ContextResult getTaskContext(
            @McpToolParam(description = "The task, e.g. 'Add a currency check to transfer validation'") String task,
            @McpToolParam(description = "Token budget, default 1500", required = false) Integer budgetTokens) {
        return context.context(new ContextService.ContextRequest(task, budgetTokens, null, null));
    }

    @McpTool(name = "get_entity", annotations = @McpAnnotations(readOnlyHint = true),
            description = "One entity by id: type, current properties, epistemic status, validity and version.")
    EntityView getEntity(@McpToolParam(description = "Entity id (pke_id)") String id) {
        return knowledge.entity(id, List.of());
    }

    @McpTool(name = "traverse_dependencies", annotations = @McpAnnotations(readOnlyHint = true),
            description = "The neighbourhood of an entity: entities connected to it within depth hops, with the "
                    + "relationships between them (e.g. a method's callers and callees, an API's handler).")
    Map<String, Object> traverseDependencies(
            @McpToolParam(description = "Entity id to start from") String id,
            @McpToolParam(description = "Hops, 1 to 3, default 1", required = false) Integer depth,
            @McpToolParam(description = "Maximum entities, default 50", required = false) Integer limit) {
        return graph.neighbourhood(id, depth == null ? 1 : depth, limit == null ? 50 : limit);
    }

    @McpTool(name = "find_affected_components", annotations = @McpAnnotations(readOnlyHint = true),
            description = "What is affected when a method changes: its callers (up to 3 hops), the APIs they handle, "
                    + "the journeys calling those APIs and the tests covering them (template impact-of-method).")
    GuardedCypher.Execution findAffectedComponents(
            @McpToolParam(description = "JavaMethod entity id") String methodId,
            @McpToolParam(description = "Maximum rows, default 50", required = false) Integer limit) {
        return templates.run("impact-of-method", methodId, limit == null ? 50 : limit);
    }

    @McpTool(name = "list_journeys", annotations = @McpAnnotations(readOnlyHint = true),
            description = "Every user journey: key, name, number of steps and the sources that describe it.")
    List<JourneySummary> listJourneys() {
        return code.journeys();
    }

    @McpTool(name = "get_journey", annotations = @McpAnnotations(readOnlyHint = true),
            description = "One user journey by id or key: ordered steps with the APIs they call and their handler "
                    + "methods, data passed between steps, sources and covering test suites.")
    JourneyView getJourney(@McpToolParam(description = "Journey id or key, e.g. international-transfer") String idOrKey) {
        return code.journey(idOrKey);
    }

    @McpTool(name = "get_requirements", annotations = @McpAnnotations(readOnlyHint = true),
            description = "Requirements and business rules (knowledge cards) an API implements, directly or through "
                    + "the methods its handler calls (template requirements-of-api).")
    GuardedCypher.Execution getRequirements(
            @McpToolParam(description = "ApiOperation entity id") String apiId,
            @McpToolParam(description = "Maximum rows, default 50", required = false) Integer limit) {
        return templates.run("requirements-of-api", apiId, limit == null ? 50 : limit);
    }

    @McpTool(name = "find_brd_sections", annotations = @McpAnnotations(readOnlyHint = true),
            description = "Full-text search over the sections of ingested documents (BRDs): heading path, text and "
                    + "document version. Use to read the original wording behind a knowledge card.")
    List<SectionView> findBrdSections(
            @McpToolParam(description = "Words to find") String query,
            @McpToolParam(description = "Maximum sections, default 10", required = false) Integer limit) {
        if (query == null || query.isBlank()) {
            throw ValidationFailedException.of("/query", "is required");
        }
        return sources.searchSections(query, limit == null ? 10 : limit);
    }

    @McpTool(name = "get_evidence", annotations = @McpAnnotations(readOnlyHint = true),
            description = "The evidence behind an entity or relationship: source, version, location, extraction "
                    + "method, excerpt and cited section (template evidence-of).")
    GuardedCypher.Execution getEvidence(
            @McpToolParam(description = "Entity or relationship id") String id,
            @McpToolParam(description = "Maximum rows, default 50", required = false) Integer limit) {
        return templates.run("evidence-of", id, limit == null ? 50 : limit);
    }

    @McpTool(name = "compare_snapshots", annotations = @McpAnnotations(readOnlyHint = true),
            description = "What changed between two snapshots (e.g. the UAT and pilot releases): entities and "
                    + "relationships added, changed or invalidated, grouped by type, and configuration changes.")
    SnapshotDiff compareSnapshots(@McpToolParam(description = "Older snapshot id") String fromSnapshotId,
                                  @McpToolParam(description = "Newer snapshot id") String toSnapshotId) {
        return snapshots.diff(fromSnapshotId, toSnapshotId);
    }

    @McpTool(name = "get_schema", annotations = @McpAnnotations(readOnlyHint = true),
            description = "The active schema: entity and relationship types with their versions. With kind and name, "
                    + "one definition in full.")
    Object getSchema(
            @McpToolParam(description = "ENTITY_TYPE or RELATIONSHIP_TYPE; both when omitted", required = false) String kind,
            @McpToolParam(description = "Type name, e.g. ApiOperation (needs kind)", required = false) String name) {
        if (name != null && !name.isBlank()) {
            return schema.get(kind(kind == null ? "ENTITY_TYPE" : kind), name);
        }
        if (kind != null && !kind.isBlank()) {
            return schema.list(kind(kind));
        }
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("entityTypes", schema.list(DefinitionKind.ENTITY_TYPE));
        all.put("relationshipTypes", schema.list(DefinitionKind.RELATIONSHIP_TYPE));
        return all;
    }

    @McpTool(name = "list_skills", annotations = @McpAnnotations(readOnlyHint = true),
            description = "The active AI skills: name, version, description, what they apply to.")
    List<Map<String, Object>> listSkills() {
        return skills.activeSkills().stream().map(s -> SkillRegistry.describe(s, false)).toList();
    }

    @McpTool(name = "list_open_questions", annotations = @McpAnnotations(readOnlyHint = true),
            description = "Open questions the engine could not decide by itself (conflicting requirements, links to "
                    + "review), each with its options. Answer them with answer_question.")
    List<QuestionView> listOpenQuestions(
            @McpToolParam(description = "open (default), answered or all", required = false) String status,
            @McpToolParam(description = "Maximum questions, default 50", required = false) Integer limit) {
        String filter = status == null || status.isBlank() ? "OPEN"
                : "all".equalsIgnoreCase(status) ? null : status.toUpperCase(Locale.ROOT);
        return questions.list(filter, limit == null ? 50 : limit);
    }

    @McpTool(name = "explain_log_trail", annotations = @McpAnnotations(readOnlyHint = true),
            description = "Explains a pasted log line: the log statement it matches, the logs the API should write "
                    + "next, the method that most likely failed and the error codes it can raise.")
    Explanation explainLogTrail(@McpToolParam(description = "The last log line seen") String logLine,
                                @McpToolParam(description = "ApiOperation id, when known", required = false) String apiId) {
        return logTrail.explain(logLine, apiId);
    }

    // ---- changing (CONTRIBUTOR, audited) ----

    @McpTool(name = "answer_question", annotations = @McpAnnotations(destructiveHint = false),
            description = "Answers an open question with one of its options. The decision is applied to the graph "
                    + "as HUMAN_VERIFIED knowledge, with the comment as evidence. Needs CONTRIBUTOR.")
    QuestionService.Answer answerQuestion(@McpToolParam(description = "Question id") String questionId,
                                          @McpToolParam(description = "Option id from the question") String option,
                                          @McpToolParam(description = "Why, kept as evidence", required = false) String comment) {
        return audited("answer_question", () -> questions.answer(questionId, option, comment, access.actor("mcp")));
    }

    @McpTool(name = "trigger_ingestion", annotations = @McpAnnotations(destructiveHint = false),
            description = "Starts a registered pipeline, e.g. code-on-commit {repository} or brd-update {document}, "
                    + "if the execution policy allows it. Returns the execution; it runs in the background. "
                    + "Needs CONTRIBUTOR.")
    ExecutionView triggerIngestion(
            @McpToolParam(description = "Pipeline id, e.g. code-ingest, code-on-commit, brd-ingest, brd-update") String pipeline,
            @McpToolParam(description = "Pipeline input, e.g. {\"repository\": \"transfer-service\"}", required = false)
            Map<String, Object> input,
            @McpToolParam(description = "Run again even if the same input was already processed", required = false)
            Boolean force) {
        return audited("trigger_ingestion", () -> {
            policies.checkPipeline(pipeline);
            return pipelines.start(pipeline, input, access.actor("mcp"), Boolean.TRUE.equals(force), false);
        });
    }

    @McpTool(name = "submit_evolution_request", annotations = @McpAnnotations(destructiveHint = false),
            description = "Asks for a change the engine cannot make with metadata alone (a new entity or relationship "
                    + "type, extractor or skill). Records the request for the evolution pipeline; nothing is executed. "
                    + "Needs CONTRIBUTOR and a kind the execution policy accepts.")
    Map<String, Object> submitEvolutionRequest(
            @McpToolParam(description = "NEW_RELATIONSHIP_TYPE, NEW_ENTITY_TYPE, NEW_EXTRACTOR or NEW_SKILL") String kind,
            @McpToolParam(description = "What it is about, e.g. the proposed type name") String subject,
            @McpToolParam(description = "Why it is needed") String reason,
            @McpToolParam(description = "Details, e.g. source and target types", required = false) Map<String, Object> details) {
        return audited("submit_evolution_request", () -> {
            policies.checkEvolutionRequest(kind);
            if (subject == null || subject.isBlank() || reason == null || reason.isBlank()) {
                throw ValidationFailedException.of("/subject", "subject and reason are required");
            }
            String id = evolution.request(kind, subject, reason, details == null ? Map.of() : details,
                    access.actor("mcp"), null);
            return Map.of("id", id, "kind", kind, "subject", subject, "status", "OPEN");
        });
    }

    @McpTool(name = "start_journey_interview", annotations = @McpAnnotations(destructiveHint = false),
            description = "Starts capturing a user journey by interview. Returns the first question; relay it to the "
                    + "user and send the answer with answer_journey_interview. Needs CONTRIBUTOR.")
    Turn startJourneyInterview(
            @McpToolParam(description = "Journey key, lower-case with dashes, e.g. international-transfer") String journeyKey,
            @McpToolParam(description = "Journey name", required = false) String name,
            @McpToolParam(description = "What the journey achieves", required = false) String description) {
        return audited("start_journey_interview", () -> interviews.start(journeyKey, name, description,
                access.actor("mcp")));
    }

    @McpTool(name = "answer_journey_interview", annotations = @McpAnnotations(destructiveHint = false),
            description = "Sends the user's answer to the current interview question. Returns the next question, or "
                    + "status COMPLETED with the journey written to the graph. Needs CONTRIBUTOR.")
    Turn answerJourneyInterview(@McpToolParam(description = "Session id from start_journey_interview") String sessionId,
                                @McpToolParam(description = "The user's answer, in their words") String answer) {
        return audited("answer_journey_interview", () -> interviews.answer(sessionId, answer, access.actor("mcp")));
    }

    private <T> T audited(String tool, Supplier<T> action) {
        try {
            access.require(Role.CONTRIBUTOR);
            T result = action.get();
            access.audit(tool, "OK");
            return result;
        } catch (AccessDeniedException e) {
            access.audit(tool, "DENIED");
            throw e;
        } catch (RuntimeException e) {
            access.audit(tool, "ERROR");
            throw e;
        }
    }

    private static DefinitionKind kind(String kind) {
        try {
            return DefinitionKind.valueOf(kind.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ValidationFailedException.of("/kind", "must be ENTITY_TYPE or RELATIONSHIP_TYPE");
        }
    }
}

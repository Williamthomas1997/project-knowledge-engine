package com.omnicore.pke.engine.api.rest;

import com.omnicore.pke.engine.graph.QuestionStore.QuestionView;
import com.omnicore.pke.engine.reconcile.QuestionService;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Open questions: conflicts the engine does not resolve by itself. */
@RestController
@RequestMapping("/api/questions")
class QuestionController {

    private final QuestionService questions;

    QuestionController(QuestionService questions) {
        this.questions = questions;
    }

    record AnswerRequest(String option, String comment) {
    }

    /** {@code ?status=open} (default), {@code answered} or {@code all}. */
    @GetMapping
    List<QuestionView> list(@RequestParam(defaultValue = "open") String status, @RequestParam(defaultValue = "50") int limit) {
        String filter = "all".equalsIgnoreCase(status) ? null : status.toUpperCase(Locale.ROOT);
        return questions.list(filter, limit);
    }

    @GetMapping("/{id}")
    QuestionView get(@PathVariable String id) {
        return questions.get(id);
    }

    /**
     * {@code {option, comment?}}: applies the chosen option through the writer with HUMAN_VERIFIED evidence and
     * returns the answered question with the change result.
     */
    @PostMapping("/{id}/answer")
    QuestionService.Answer answer(@PathVariable String id, @RequestBody AnswerRequest request,
                                  @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        return questions.answer(id, request.option(), request.comment(), Actors.of(actor));
    }
}

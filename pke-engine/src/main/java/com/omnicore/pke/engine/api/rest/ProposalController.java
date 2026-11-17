package com.omnicore.pke.engine.api.rest;

import com.omnicore.pke.engine.store.Proposals;
import com.omnicore.pke.engine.store.Proposals.Decision;
import com.omnicore.pke.engine.store.Proposals.Diff;
import com.omnicore.pke.engine.store.Proposals.NewProposal;
import com.omnicore.pke.engine.store.Proposals.ProposalView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proposals (ADR-015). Writes into a proposal use the normal knowledge endpoints with {@code scope=proposal:<id>};
 * this controller creates proposals, shows their diff against main, and approves or rejects them.
 */
@RestController
@RequestMapping("/api/proposals")
class ProposalController {

    private final Proposals proposals;

    ProposalController(Proposals proposals) {
        this.proposals = proposals;
    }

    record Comment(String comment) {
    }

    record Reason(String reason) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProposalView create(@RequestBody NewProposal proposal,
                        @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        return proposals.create(proposal, Actors.of(actor));
    }

    @GetMapping
    List<ProposalView> list(@RequestParam(required = false) String status) {
        return proposals.list(status);
    }

    /** The proposal with its items compared with main: added, changed, removed (and unchanged). */
    @GetMapping("/{id}")
    Diff diff(@PathVariable String id) {
        return proposals.diff(id);
    }

    @PostMapping("/{id}/approve")
    Decision approve(@PathVariable String id, @RequestBody(required = false) Comment body,
                     @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        return proposals.approve(id, Actors.of(actor), body == null ? null : body.comment());
    }

    @PostMapping("/{id}/reject")
    ProposalView reject(@PathVariable String id, @RequestBody(required = false) Reason body,
                        @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        return proposals.reject(id, Actors.of(actor), body == null ? null : body.reason());
    }
}

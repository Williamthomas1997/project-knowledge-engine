package com.demo.reactive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Reactive endpoints: method references, Mono operator chains, a field typed by an interface. */
@RestController
@RequestMapping("/api/v1/reactive-transfers")
public class ReactiveTransferController {

    private static final Logger log = LoggerFactory.getLogger(ReactiveTransferController.class);

    private final TransferPort service;
    private final ErrorMapper errors;
    private final NotifierPort notifier;

    public ReactiveTransferController(TransferPort service, ErrorMapper errors, NotifierPort notifier) {
        this.service = service;
        this.errors = errors;
        this.notifier = notifier;
    }

    @PostMapping("/validate")
    public Mono<ResponseEntity<String>> validate(@RequestBody Mono<ReactiveRequest> body) {
        return body.flatMap(service::validate)
                .map(ResponseEntity::ok)
                .onErrorResume(errors::toResponse);
    }

    @PostMapping("/execute")
    public Mono<String> execute(@RequestBody ReactiveRequest request) {
        return Mono.just(request)
                .doOnNext(this::audit)
                .map((ReactiveRequest r) -> r.normalised())
                .flatMap(r -> this.service.execute(r))
                .doOnNext(id -> notifier.notifyCustomer(id))
                .map(id -> id.trim())
                .switchIfEmpty(Mono.defer(() -> fallback(request)))
                .then(Mono.fromCallable(() -> Receipts.number(request)));
    }

    private void audit(ReactiveRequest request) {
        log.info("Reactive transfer received for {}", request.beneficiary());
    }

    private Mono<String> fallback(ReactiveRequest request) {
        log.warn("No transfer id returned, using the fallback");
        return Mono.empty();
    }
}

package com.demo.reactive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** The only TransferPort: limit check, then the core banking call through a WebClient chain. */
@Service
public class DefaultTransferPort implements TransferPort {

    private static final Logger log = LoggerFactory.getLogger(DefaultTransferPort.class);

    private final WebClient webClient;
    private final LimitChecker limits;
    private final ErrorMapper errors;

    public DefaultTransferPort(WebClient webClient, LimitChecker limits, ErrorMapper errors) {
        this.webClient = webClient;
        this.limits = limits;
        this.errors = errors;
    }

    @Override
    public Mono<String> validate(ReactiveRequest request) {
        log.info("Validating reactive transfer to {}", request.beneficiary());
        return limits.check(request.amount()).map(ok -> "VALID");
    }

    @Override
    public Mono<String> execute(ReactiveRequest request) {
        return webClient.post()
                .uri("/core/transfers")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .doOnNext(id -> log.info("Core banking accepted transfer {}", id))
                .onErrorResume(e -> errors.coreFailure(e));
    }
}

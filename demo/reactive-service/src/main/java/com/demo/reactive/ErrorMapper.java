package com.demo.reactive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class ErrorMapper {

    private static final Logger log = LoggerFactory.getLogger(ErrorMapper.class);

    public Mono<ResponseEntity<String>> toResponse(Throwable error) {
        log.error("Reactive validation failed", error);
        return Mono.just(ResponseEntity.badRequest().body("INVALID"));
    }

    public Mono<String> coreFailure(Throwable error) {
        log.error("Core banking call failed", error);
        return Mono.error(error);
    }
}

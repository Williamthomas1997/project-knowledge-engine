package com.demo.reactive;

import reactor.core.publisher.Mono;

/** Port with a single implementation in this repository. */
public interface TransferPort {

    Mono<String> validate(ReactiveRequest request);

    Mono<String> execute(ReactiveRequest request);
}

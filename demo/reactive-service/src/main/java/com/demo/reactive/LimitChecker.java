package com.demo.reactive;

import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class LimitChecker {

    private static final Logger log = LoggerFactory.getLogger(LimitChecker.class);
    private static final BigDecimal DAILY_LIMIT = new BigDecimal("50000");

    public Mono<Boolean> check(BigDecimal amount) {
        if (amount.compareTo(DAILY_LIMIT) > 0) {
            log.warn("Reactive transfer above the daily limit");
            return Mono.error(new IllegalArgumentException("limit"));
        }
        return Mono.just(true);
    }
}

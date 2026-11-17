package com.demo.reactive;

import java.math.BigDecimal;

public record ReactiveRequest(String beneficiary, BigDecimal amount, String currency) {

    public ReactiveRequest normalised() {
        return new ReactiveRequest(beneficiary.trim(), amount, currency.toUpperCase());
    }
}

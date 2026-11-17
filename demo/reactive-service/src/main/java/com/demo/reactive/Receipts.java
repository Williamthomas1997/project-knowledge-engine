package com.demo.reactive;

/** Static helper reached from a lambda. */
public final class Receipts {

    private Receipts() {
    }

    public static String number(ReactiveRequest request) {
        return "R-" + request.beneficiary().length();
    }
}

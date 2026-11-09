package com.demo.transfer;

import java.math.BigDecimal;

public record TransferRequest(String auid, String buid, BigDecimal amount, String currency, Purpose purpose) {

    public record Purpose(String code) {
    }
}

package com.demo.transfer;

import java.math.BigDecimal;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Shared currency conversion used by several features. */
@Component
public class CurrencyConverter {

    private static final Set<String> SUPPORTED = Set.of("USD", "EUR", "GBP", "INR", "QAR");

    private final FxRateClient fxRates;

    public CurrencyConverter(FxRateClient fxRates) {
        this.fxRates = fxRates;
    }

    public boolean isSupported(String currency) {
        return currency != null && SUPPORTED.contains(currency);
    }

    public BigDecimal convert(BigDecimal amount, String from, String to) {
        BigDecimal rate = fxRates.rate(from, to);
        return amount.multiply(rate);
    }
}

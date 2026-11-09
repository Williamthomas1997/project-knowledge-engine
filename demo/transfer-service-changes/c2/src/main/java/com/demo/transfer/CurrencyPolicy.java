package com.demo.transfer;

import org.springframework.stereotype.Component;

/** Country-specific currency rules (added in c2). */
@Component
public class CurrencyPolicy {

    public boolean isAllowed(String currency) {
        return !"XYZ".equals(currency);
    }
}

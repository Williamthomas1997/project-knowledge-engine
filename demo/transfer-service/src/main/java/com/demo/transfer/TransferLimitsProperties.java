package com.demo.transfer;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "transfer.limits")
public record TransferLimitsProperties(java.math.BigDecimal daily) {
}

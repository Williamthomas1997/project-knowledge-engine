package com.demo.transfer;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Client of the external FX rate API. */
@Component
public class FxRateClient {

    private final RestClient restClient = RestClient.create("https://fx.example.invalid");

    public BigDecimal rate(String from, String to) {
        return restClient.get().uri("/rates/{from}/{to}", from, to).retrieve().body(BigDecimal.class);
    }
}

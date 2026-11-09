package com.demo.transfer;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class RatesListener {

    @KafkaListener(topics = "fx.rates.updated")
    public void onRates(String payload) {
    }
}

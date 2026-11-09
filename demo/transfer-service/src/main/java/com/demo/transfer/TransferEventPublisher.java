package com.demo.transfer;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class TransferEventPublisher {

    static final String TOPIC = "transfer.executed";

    private final KafkaTemplate<String, String> kafka;

    public TransferEventPublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    public void publishExecuted(String reference) {
        kafka.send(TOPIC, reference);
    }
}

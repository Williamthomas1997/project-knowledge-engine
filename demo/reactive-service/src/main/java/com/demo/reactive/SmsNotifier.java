package com.demo.reactive;

import org.springframework.stereotype.Component;

@Component
public class SmsNotifier implements NotifierPort {

    @Override
    public void notifyCustomer(String transferId) {
    }
}

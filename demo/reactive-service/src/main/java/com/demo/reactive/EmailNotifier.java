package com.demo.reactive;

import org.springframework.stereotype.Component;

@Component
public class EmailNotifier implements NotifierPort {

    @Override
    public void notifyCustomer(String transferId) {
    }
}

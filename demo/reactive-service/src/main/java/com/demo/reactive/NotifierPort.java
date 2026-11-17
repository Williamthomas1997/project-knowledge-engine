package com.demo.reactive;

/** Port with two implementations: calls through it are not dispatched (never guessed). */
public interface NotifierPort {

    void notifyCustomer(String transferId);
}

package com.demo.transfer;

public class TransferException extends RuntimeException {

    private final String code;

    public TransferException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

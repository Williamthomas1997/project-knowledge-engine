package com.demo.transfer;

import java.util.List;

public record AccountsResponse(List<Account> accounts) {

    public record Account(String auid, String currency, java.math.BigDecimal balance) {
    }
}

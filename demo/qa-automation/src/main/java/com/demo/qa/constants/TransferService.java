package com.demo.qa.constants;

import java.util.LinkedHashMap;
import java.util.Map;

/** Gateway headers per API, as in the Postman collection. */
public enum TransferService {

    FT_ACCOUNTS(Map.of("moduleId", "TRANSFERS", "subModuleId", "FUND_TRANSFERS", "serviceId", "MENUS",
            "screenId", "SCREEN1", "unit", "DEFAULT", "channel", "MB")),
    INTL_TRANSFER(Map.of("moduleId", "TRANSFER", "subModuleId", "INTERNATIONAL", "serviceId", "INTL_TRANSFER",
            "screenId", "INTL_01", "unit", "DEFAULT", "channel", "MB"));

    private final Map<String, String> headers;

    TransferService(Map<String, String> headers) {
        this.headers = headers;
    }

    public Map<String, String> headers() {
        return new LinkedHashMap<>(headers);
    }
}

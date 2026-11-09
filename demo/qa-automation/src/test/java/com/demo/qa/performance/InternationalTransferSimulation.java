package com.demo.qa.performance;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import java.util.Map;

public class InternationalTransferSimulation extends Simulation {

    static final Map<String, String> FT_HEADERS = Map.of("moduleId", "TRANSFERS", "subModuleId", "FUND_TRANSFERS",
            "serviceId", "MENUS", "screenId", "SCREEN1", "channel", "MB");
    static final Map<String, String> INTL_HEADERS = Map.of("moduleId", "TRANSFER", "subModuleId", "INTERNATIONAL",
            "serviceId", "INTL_TRANSFER", "screenId", "INTL_01", "channel", "MB");

    ScenarioBuilder transfer = scenario("International transfer")
            .exec(http("ft-accounts").post("/api/v1/get-ft-accounts").headers(FT_HEADERS)
                    .body(StringBody("{\"customerType\": \"RETAIL\"}"))
                    .check(jsonPath("$.accounts[0].auid").saveAs("auid")))
            .exec(http("InitiateTransfer").post("/api/v2/international-transfers/validation").headers(INTL_HEADERS)
                    .body(StringBody("{\"auid\": \"#{auid}\", \"amount\": 100}"))
                    .check(jsonPath("$.uuid").saveAs("uuid")))
            .exec(http("FinalSubmit").post("/api/v3/international-transfers/execute").headers(INTL_HEADERS)
                    .body(StringBody("{\"uuid\": \"#{uuid}\", \"otp\": \"111111\"}")));
}

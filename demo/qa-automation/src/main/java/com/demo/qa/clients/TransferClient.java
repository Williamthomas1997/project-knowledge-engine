package com.demo.qa.clients;

import static io.restassured.RestAssured.given;

import com.demo.qa.constants.TransferEndpoints;
import com.demo.qa.constants.TransferService;
import io.restassured.response.Response;
import java.util.Map;

public class TransferClient {

    public Response ftAccounts() {
        return post(TransferService.FT_ACCOUNTS, TransferEndpoints.FT_ACCOUNTS, Map.of("customerType", "RETAIL"));
    }

    public Response validate(String auid) {
        return post(TransferService.INTL_TRANSFER, TransferEndpoints.INTL_VALIDATION, Map.of("auid", auid));
    }

    public Response execute(String uuid) {
        return post(TransferService.INTL_TRANSFER, TransferEndpoints.INTL_EXECUTE, Map.of("uuid", uuid));
    }

    private Response post(TransferService service, String path, Object body) {
        return given().headers(service.headers()).body(body).post(path);
    }
}

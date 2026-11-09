package com.demo.transfer;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(TransferPaths.BASE)
public class TransferController {

    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    @PostMapping("/v1/get-ft-accounts")
    public AccountsResponse ftAccounts(@RequestBody AccountsRequest request) {
        return service.accounts(request);
    }

    @PostMapping(TransferPaths.VALIDATION)
    public TransferResponse validate(@RequestBody TransferRequest request, @RequestHeader("channel") String channel) {
        return service.validate(request);
    }

    @PostMapping("/v3/international-transfers/execute")
    public TransferResponse execute(@RequestBody ExecuteRequest request) {
        return service.execute(request);
    }
}

package com.demo.qa.tests;

import com.demo.qa.clients.TransferClient;

/** ft-accounts -> InitiateTransfer (auid) -> FinalSubmit (uuid). */
public class InternationalTransferJourney {

    private final TransferClient client = new TransferClient();
    private String auid;
    private String uuid;

    public String validate() {
        uuid = client.validate(auid).jsonPath().getString("uuid");
        return uuid;
    }

    public String ftAccounts() {
        auid = client.ftAccounts().jsonPath().getString("accounts[0].auid");
        return auid;
    }

    public void execute() {
        client.execute(uuid);
    }
}

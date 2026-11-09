package com.demo.qa.tests;

import org.testng.annotations.Test;

public class InternationalTransferTest {

    @Test
    public void internationalTransferHappyPath() {
        InternationalTransferJourney journey = new InternationalTransferJourney();
        journey.ftAccounts();
        journey.validate();
        journey.execute();
    }
}

package com.demo.transfer;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TransferServiceTest {

    private final TransferService service = new TransferService(new FxCurrencyConverter(new FxRateClient()), null, null, null);

    @Test
    void rejectsUnsupportedBeneficiaryCurrency() {
        assertThrows(TransferException.class, () -> service.checkCurrency("XYZ"));
    }
}

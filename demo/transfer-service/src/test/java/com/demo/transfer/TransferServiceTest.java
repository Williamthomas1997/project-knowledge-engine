package com.demo.transfer;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TransferServiceTest {

    private final TransferService service = new TransferService(new CurrencyConverter(new FxRateClient()), null, null);

    @Test
    void rejectsUnsupportedBeneficiaryCurrency() {
        assertThrows(TransferException.class, () -> service.validateCurrency("XYZ"));
    }
}

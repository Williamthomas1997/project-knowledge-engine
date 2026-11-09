package com.demo.transfer;

import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final CurrencyConverter converter;
    private final CurrencyPolicy policy;
    private final BeneficiaryRepository beneficiaries;
    private final TransferEventPublisher events;

    @Value("${transfer.max-amount:10000}")
    private BigDecimal maxAmount;

    public AccountsResponse accounts(AccountsRequest request) {
        log.info("Listing transfer accounts for type {}", request.customerType());
        return new AccountsResponse(java.util.List.of());
    }

    public TransferResponse validate(TransferRequest request) {
        log.info("Validating transfer to {}", request.buid());
        validateCurrency(request.currency());
        beneficiaries.findByBuid(request.buid());
        BigDecimal amountInQar = converter.convert(request.amount(), request.currency(), "QAR");
        if (amountInQar.compareTo(maxAmount) > 0) {
            log.warn("Transfer above the daily limit");
            throw new TransferException(TransferErrors.LIMIT_EXCEEDED);
        }
        return new TransferResponse("uuid-1", "VALIDATED");
    }

    public void validateCurrency(String currency) {
        if (!policy.isAllowed(currency)) {
            log.error("Currency " + currency + " is not supported");
            throw new TransferException(TransferErrors.CURRENCY_NOT_SUPPORTED);
        }
    }

    public TransferResponse execute(ExecuteRequest request) {
        beneficiaries.save(new Beneficiary());
        events.publishExecuted(request.uuid());
        log.info("Executed transfer {}", request.uuid());
        return new TransferResponse(request.uuid(), "EXECUTED");
    }
}

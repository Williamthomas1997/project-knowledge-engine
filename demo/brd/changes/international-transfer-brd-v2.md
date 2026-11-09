# International Transfer - Business Requirements (synthetic demo)

Document id: BRD-INTL-TRANSFER. Version 1.1. This document is synthetic and contains no bank data.

## 1 Purpose

Customers send money to beneficiaries abroad from the mobile app. The transfer is validated, confirmed with an
OTP and executed.

## 2 Scope

International transfers from a QAR current account to a saved beneficiary.

## 3 Requirements

### FR-1 Beneficiary currency validation

The system shall reject an international transfer when the beneficiary currency is not supported for the
destination country. The customer sees error 003040.

### FR-2 Transfer limit

The system shall reject a transfer whose amount, converted to QAR, exceeds the customer's daily limit.
The customer sees error 003041. The daily limit for international transfers is QAR 30,000.

### FR-4 Purpose of transfer

The system shall require a purpose code for every international transfer.

### FR-3 Execution

After a successful validation and OTP check, the system shall execute the transfer, store it and publish a
transfer-executed event.

## 4 Error codes

| Code | Meaning |
| --- | --- |
| 003040 | Beneficiary currency not supported |
| 003041 | Daily limit exceeded |

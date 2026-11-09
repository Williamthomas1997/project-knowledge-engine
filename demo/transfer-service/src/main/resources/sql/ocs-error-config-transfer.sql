-- Synthetic error configuration for the demo transfer service.
INSERT INTO OCS_T_ERROR_CONFIG (TXN_ID, UNIT_ID, LANG_CODE, CHANNEL_ID, SERVICE_TYPE, MW_ERR_CODE, OCS_ERR_CODE, OCS_ERR_DESC, STATUS)
VALUES (900001, 'PRD', 'en', 'MB', 'EXTERNAL.TRANSFER', 'INT-003040', '003040', 'This currency is not supported for the beneficiary''s country.', 'ACT');
INSERT INTO OCS_T_ERROR_CONFIG (TXN_ID, UNIT_ID, LANG_CODE, CHANNEL_ID, SERVICE_TYPE, MW_ERR_CODE, OCS_ERR_CODE, OCS_ERR_DESC, STATUS)
VALUES (900002, 'PRD', 'en', 'MB', 'EXTERNAL.TRANSFER', 'INT-003041', '003041', 'The amount exceeds your daily limit.', 'ACT');

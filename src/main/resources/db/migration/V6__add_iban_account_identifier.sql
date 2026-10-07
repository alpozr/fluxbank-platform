CREATE SEQUENCE iban_account_identifier_seq
    START WITH 00000001
    INCREMENT BY 1
    MINVALUE 00000001
    MAXVALUE 99999999
    NO CYCLE;

ALTER TABLE accounts
    ADD COLUMN iban_account_identifier BIGINT;

UPDATE accounts
SET iban_account_identifier = nextval('iban_account_identifier_seq')
WHERE iban_account_identifier IS NULL;

ALTER TABLE accounts
    ALTER COLUMN iban_account_identifier SET NOT NULL;

ALTER TABLE accounts
    ADD CONSTRAINT uq_accounts_iban_account_identifier
        UNIQUE (iban_account_identifier);
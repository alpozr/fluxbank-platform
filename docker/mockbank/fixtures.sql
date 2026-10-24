-- LOCAL DEVELOPMENT ONLY. Matches FluxBank's default provider account numbers.
-- EUR is a supported FluxBank currency; these fixtures are for EUR demo payments.
INSERT INTO accounts (account_number, account_name, account_type, currency) VALUES
('FLOAT-MAIN-001', 'Development settlement float', 'float', 'EUR'),
('EXT-CUST-001', 'Development external recipient', 'external', 'EUR'),
('BANK-CHARGES-001', 'Development bank charges', 'bank_charges', 'EUR'),
('DEV-FUNDING-001', 'Development funding source', 'internal', 'EUR');

-- Same two-entry deposit model as create_funding_transaction in the provider.
INSERT INTO transactions
    (transaction_reference, transaction_type, status, amount, currency, description)
VALUES ('DEV-INITIAL-FUNDING', 'deposit', 'completed', 1000000, 'EUR', 'Development-only initial float funding');

INSERT INTO ledger_entries (transaction_id, account_id, entry_type, amount, currency)
SELECT t.id, a.id,
       CASE WHEN a.account_number = 'DEV-FUNDING-001' THEN 'debit' ELSE 'credit' END,
       1000000, 'EUR'
FROM transactions t CROSS JOIN accounts a
WHERE t.transaction_reference = 'DEV-INITIAL-FUNDING'
  AND a.account_number IN ('DEV-FUNDING-001', 'FLOAT-MAIN-001');

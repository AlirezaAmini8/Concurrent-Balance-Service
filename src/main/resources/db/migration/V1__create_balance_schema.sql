CREATE TABLE accounts
(
    id         VARCHAR(64) PRIMARY KEY,
    balance    BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Last line of defence: even a bug in the service can never persist a negative balance.
    CONSTRAINT accounts_balance_non_negative CHECK (balance >= 0)
);

-- Idempotency registry: exactly one row per transactionId, holding the request
-- fingerprint (type, accounts, amount) and its final outcome.
-- A row is only ever committed as APPLIED or REJECTED; PENDING is visible solely
-- inside the database transaction that is processing the request.
CREATE TABLE transactions
(
    id                     VARCHAR(128) PRIMARY KEY,
    type                   VARCHAR(16)  NOT NULL,
    source_account_id      VARCHAR(64),
    destination_account_id VARCHAR(64),
    amount                 BIGINT       NOT NULL,
    status                 VARCHAR(16)  NOT NULL,
    rejection_reason       VARCHAR(32),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT transactions_type_valid CHECK (type IN ('CREDIT', 'DEBIT', 'TRANSFER')),
    CONSTRAINT transactions_status_valid CHECK (status IN ('PENDING', 'APPLIED', 'REJECTED'))
);

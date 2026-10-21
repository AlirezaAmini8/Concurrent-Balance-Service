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

-- Append-only audit trail. For every account: balance = SUM(amount) of its entries.
CREATE TABLE ledger_entries
(
    id             BIGSERIAL PRIMARY KEY,
    account_id     VARCHAR(64) NOT NULL REFERENCES accounts (id),
    -- NULL only for the opening balance written when the account is created.
    transaction_id VARCHAR(128) REFERENCES transactions (id),
    entry_type     VARCHAR(16) NOT NULL,
    -- Signed: positive for money in, negative for money out.
    amount         BIGINT      NOT NULL,
    balance_after  BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ledger_entries_amount_non_zero CHECK (amount <> 0),
    CONSTRAINT ledger_entries_type_valid CHECK (entry_type IN ('OPENING', 'CREDIT', 'DEBIT'))
);

CREATE INDEX ledger_entries_account_idx ON ledger_entries (account_id, id);
CREATE INDEX ledger_entries_transaction_idx ON ledger_entries (transaction_id);

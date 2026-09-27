CREATE TABLE banking_accounts (
    id uuid PRIMARY KEY,
    owner_subject varchar(255) NOT NULL,
    balance numeric(19,2) NOT NULL CHECK (balance >= 0),
    currency char(3) NOT NULL CHECK (currency = 'SGD')
);
CREATE TABLE banking_operations (
    account_id uuid NOT NULL REFERENCES banking_accounts(id),
    idempotency_key varchar(128) NOT NULL,
    kind varchar(16) NOT NULL CHECK (kind IN ('deposit','withdrawal')),
    amount numeric(19,2) NOT NULL CHECK (amount > 0),
    balance_after numeric(19,2) NOT NULL CHECK (balance_after >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, idempotency_key)
);

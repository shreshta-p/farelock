-- The simulated external payment provider. It has its own schema and is only
-- written in its own transactions, so its records survive independently of
-- whatever happens to the reservation that triggered them (like a real provider).
CREATE TABLE payment_provider.charges (
    idempotency_key TEXT PRIMARY KEY, -- the reservation id: one charge per reservation, ever
    amount          NUMERIC(10,2) NOT NULL,
    currency        CHAR(3)       NOT NULL,
    outcome         TEXT          NOT NULL CHECK (outcome IN ('APPROVED', 'DECLINED', 'PENDING')),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

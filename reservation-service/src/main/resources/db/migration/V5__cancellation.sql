-- Guest cancellation with optional free-cancellation protection, and provider refunds.

-- Each hotel's IANA time zone: "before the stay date" means before the check-in day
-- starts where the hotel is, not on the server's clock.
ALTER TABLE hotels ADD COLUMN timezone TEXT NOT NULL DEFAULT 'UTC';
UPDATE hotels SET timezone = 'America/New_York'    WHERE id IN (1, 2);
UPDATE hotels SET timezone = 'Europe/Paris'        WHERE id IN (3, 4);
UPDATE hotels SET timezone = 'Europe/Lisbon'       WHERE id IN (5, 6);
UPDATE hotels SET timezone = 'Asia/Tokyo'          WHERE id IN (7, 8);
UPDATE hotels SET timezone = 'Africa/Johannesburg' WHERE id = 9;
UPDATE hotels SET timezone = 'America/Mexico_City' WHERE id = 10;

ALTER TABLE reservations
    ADD COLUMN protection_fee  NUMERIC(10,2) CHECK (protection_fee >= 0), -- NULL = no protection bought
    ADD COLUMN cancel_deadline TIMESTAMPTZ,  -- midnight starting the stay date, hotel local time
    ADD COLUMN cancelled_at    TIMESTAMPTZ,
    ADD COLUMN refund_amount   NUMERIC(10,2) CHECK (refund_amount > 0),
    ADD COLUMN refund_status   TEXT CHECK (refund_status IN ('NONE', 'PENDING', 'REFUNDED'));

-- Existing reservations get their deadline the same way new ones do. `timestamp AT TIME ZONE zone`
-- reads a wall-clock time in that zone and returns the matching absolute instant.
UPDATE reservations r SET cancel_deadline = r.stay_date::timestamp AT TIME ZONE h.timezone
FROM hotels h WHERE h.id = r.hotel_id;
ALTER TABLE reservations ALTER COLUMN cancel_deadline SET NOT NULL;

ALTER TABLE reservations DROP CONSTRAINT reservations_status_check;
ALTER TABLE reservations ADD CONSTRAINT reservations_status_check
    CHECK (status IN ('HELD', 'PAYMENT_PENDING', 'CONFIRMED', 'RELEASED', 'CANCELLED'));

-- The refund retry job only looks at refunds the provider has not confirmed yet.
CREATE INDEX reservations_refund_pending_idx ON reservations (updated_at) WHERE refund_status = 'PENDING';

-- The simulated provider's refunds. Keyed like charges, so a retried refund can never pay out twice.
CREATE TABLE payment_provider.refunds (
    idempotency_key TEXT PRIMARY KEY,
    charge_key      TEXT          NOT NULL REFERENCES payment_provider.charges (idempotency_key),
    amount          NUMERIC(10,2) NOT NULL CHECK (amount > 0),
    currency        CHAR(3)       NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX refunds_charge_idx ON payment_provider.refunds (charge_key);

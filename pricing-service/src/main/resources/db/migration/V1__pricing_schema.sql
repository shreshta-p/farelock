-- Base nightly rate per room type. Room type and hotel ids match the
-- reservation service's catalog (same seed ids), but there is no cross-schema FK:
-- each service owns its own data.
CREATE TABLE room_rates (
    room_type_id BIGINT PRIMARY KEY,
    hotel_id     BIGINT        NOT NULL,
    base_price   NUMERIC(10,2) NOT NULL CHECK (base_price > 0),
    currency     CHAR(3)       NOT NULL
);
CREATE INDEX room_rates_hotel_idx ON room_rates (hotel_id);

-- A quote is a fixed price snapshot. Once created its amount never changes;
-- it simply stops being usable after expires_at.
CREATE TABLE quotes (
    id           UUID PRIMARY KEY,
    room_type_id BIGINT        NOT NULL REFERENCES room_rates (room_type_id),
    hotel_id     BIGINT        NOT NULL,
    stay_date    DATE          NOT NULL,
    amount       NUMERIC(10,2) NOT NULL,
    currency     CHAR(3)       NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL,
    expires_at   TIMESTAMPTZ   NOT NULL
);

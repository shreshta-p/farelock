CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         TEXT        NOT NULL UNIQUE, -- stored lower-cased
    password_hash TEXT        NOT NULL,        -- BCrypt, never plaintext
    full_name     TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Only a SHA-256 hash of the session token is stored, so a database leak
-- does not leak usable session cookies.
CREATE TABLE sessions (
    token_hash TEXT PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX sessions_user_idx ON sessions (user_id);

CREATE TABLE hotels (
    id          BIGINT PRIMARY KEY,
    name        TEXT          NOT NULL,
    city        TEXT          NOT NULL,
    country     TEXT          NOT NULL,
    address     TEXT          NOT NULL,
    latitude    NUMERIC(9,6),
    longitude   NUMERIC(9,6),
    star_rating SMALLINT CHECK (star_rating BETWEEN 1 AND 5),
    description TEXT          NOT NULL,
    amenities   TEXT[]        NOT NULL DEFAULT '{}',
    images      TEXT[]        NOT NULL DEFAULT '{}',
    source_url  TEXT          -- where the real-world facts came from (Wikidata)
);
CREATE INDEX hotels_city_idx ON hotels (lower(city));

CREATE TABLE room_types (
    id          BIGINT PRIMARY KEY,
    hotel_id    BIGINT   NOT NULL REFERENCES hotels (id),
    name        TEXT     NOT NULL,
    description TEXT     NOT NULL,
    bed         TEXT     NOT NULL,
    size_sqm    SMALLINT NOT NULL,
    max_guests  SMALLINT NOT NULL,
    image       TEXT
);
CREATE INDEX room_types_hotel_idx ON room_types (hotel_id);

-- One row per room type per night. held = temporarily reserved, booked = paid.
-- The CHECK constraint makes overselling impossible even if application code is wrong.
CREATE TABLE room_inventory (
    room_type_id BIGINT NOT NULL REFERENCES room_types (id),
    stay_date    DATE   NOT NULL,
    total        INT    NOT NULL CHECK (total >= 0),
    held         INT    NOT NULL DEFAULT 0,
    booked       INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (room_type_id, stay_date),
    CHECK (held >= 0 AND booked >= 0 AND held + booked <= total)
);

CREATE TABLE reservations (
    id              UUID PRIMARY KEY,
    user_id         BIGINT        NOT NULL REFERENCES users (id),
    hotel_id        BIGINT        NOT NULL REFERENCES hotels (id),
    room_type_id    BIGINT        NOT NULL REFERENCES room_types (id),
    stay_date       DATE          NOT NULL,
    guest_name      TEXT          NOT NULL,
    quote_id        UUID          NOT NULL,
    amount          NUMERIC(10,2) NOT NULL,
    currency        CHAR(3)       NOT NULL,
    status          TEXT          NOT NULL CHECK (status IN ('HELD', 'PAYMENT_PENDING', 'CONFIRMED', 'RELEASED')),
    release_reason  TEXT          CHECK (release_reason IN ('EXPIRED', 'DECLINED', 'PAYMENT_NOT_FOUND')),
    hold_expires_at TIMESTAMPTZ   NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX reservations_user_idx ON reservations (user_id, created_at DESC);
-- Background jobs only scan unfinished reservations, so index just those.
CREATE INDEX reservations_open_idx ON reservations (status, updated_at) WHERE status IN ('HELD', 'PAYMENT_PENDING');

-- Remembers the outcome of each Idempotency-Key so retries get the same answer.
CREATE TABLE idempotency_keys (
    user_id         BIGINT      NOT NULL REFERENCES users (id),
    idempotency_key TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_status INT,
    response_body   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idempotency_key)
);

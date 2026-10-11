-- Resets ONLY load-test data: the hidden hotel 99 and users named loadtest+...@farelock.test.
-- Demo hotels and real accounts are never touched.
--   docker compose --env-file .env exec -T postgres psql -U postgres -d farelock -f - < load-tests/reset.sql
\set ON_ERROR_STOP on
-- Guard: only ever run against the local FareLock database.
DO $$
BEGIN
  IF current_database() <> 'farelock' THEN
    RAISE EXCEPTION 'Refusing to reset: unexpected database %', current_database();
  END IF;
END $$;

BEGIN;
CREATE TEMP TABLE lt_users AS SELECT id FROM reservation.users WHERE email LIKE 'loadtest+%@farelock.test';
CREATE TEMP TABLE lt_reservations AS
  SELECT id FROM reservation.reservations WHERE hotel_id = 99 OR user_id IN (SELECT id FROM lt_users);

DELETE FROM payment_provider.charges WHERE idempotency_key IN (SELECT id::text FROM lt_reservations);
DELETE FROM reservation.reservations WHERE id IN (SELECT id FROM lt_reservations);
DELETE FROM reservation.idempotency_keys WHERE user_id IN (SELECT id FROM lt_users);
DELETE FROM reservation.sessions WHERE user_id IN (SELECT id FROM lt_users);
DELETE FROM reservation.users WHERE id IN (SELECT id FROM lt_users);
UPDATE reservation.room_inventory SET held = 0, booked = 0 WHERE room_type_id IN (9901, 9902);
DELETE FROM pricing.quotes WHERE hotel_id = 99;
COMMIT;

SELECT 'load-test data reset' AS result;

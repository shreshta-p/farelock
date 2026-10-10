#!/bin/sh
# Runs once, on the first start of an empty Postgres volume.
# Each service gets its own login role and owns only its own schema(s).
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
  CREATE ROLE pricing_svc LOGIN PASSWORD '${PRICING_DB_PASSWORD}';
  CREATE ROLE reservation_svc LOGIN PASSWORD '${RESERVATION_DB_PASSWORD}';
  CREATE SCHEMA pricing AUTHORIZATION pricing_svc;
  CREATE SCHEMA reservation AUTHORIZATION reservation_svc;
  -- The simulated payment provider lives in the reservation service process,
  -- but in its own schema to model an external system with its own storage.
  CREATE SCHEMA payment_provider AUTHORIZATION reservation_svc;
  REVOKE ALL ON SCHEMA public FROM PUBLIC;
EOSQL

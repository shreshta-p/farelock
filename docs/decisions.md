# Architecture decisions

Short notes on choices that a reviewer might question. Newest at the bottom.

### 1. Two services, one Postgres, service-owned schemas
Pricing and Reservation are separate Spring Boot apps that talk over REST. They share one
Postgres *instance*, but each logs in with its own role and owns only its own schema
(`pricing`, `reservation`, `payment_provider`). Neither service can read the other's tables.
**Tradeoff:** one instance is a single point of failure, but separate instances would add
nothing to a local demo.

### 2. Spring JDBC + plain SQL, no JPA
Inventory correctness depends on exact SQL (conditional `UPDATE ... WHERE held + booked < total`).
Writing that SQL directly is easier to read and explain than JPA locking annotations.

### 3. Configuration through a repo-root `.env`
Docker Compose reads `.env`, and each service imports the same file with
`spring.config.import: optional:file:../.env[.properties]`, so there is one place for
local secrets. Real deployments would use environment variables instead; the file is optional.

-- The price of optional free-cancellation protection, locked into the quote with the room price.
-- Quotes created before this column existed (all long expired) offered no protection, hence 0.
ALTER TABLE quotes ADD COLUMN protection_fee NUMERIC(10,2) NOT NULL DEFAULT 0 CHECK (protection_fee >= 0);

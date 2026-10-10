-- How many rooms of each type exist per night. The inventory job uses this to keep
-- room_inventory filled for the booking window as days pass.
ALTER TABLE room_types ADD COLUMN rooms_per_night INT NOT NULL DEFAULT 0 CHECK (rooms_per_night >= 0);

-- Unlisted hotels (the load-test hotel) are bookable through the API but hidden from search.
ALTER TABLE hotels ADD COLUMN listed BOOLEAN NOT NULL DEFAULT true;

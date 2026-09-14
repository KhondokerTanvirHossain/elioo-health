-- An item the model read but whose source span could not be located on the page is dropped: it has no
-- crop, so the family could never check it, and it must never reach a message. Dropping it silently would
-- hide a real signal, though — a value the family sent that we could not stand behind. Count it per section.
ALTER TABLE ${flyway:defaultSchema}.document
    ADD COLUMN unverified_values    integer NOT NULL DEFAULT 0,
    ADD COLUMN unverified_medicines integer NOT NULL DEFAULT 0,
    ADD COLUMN unverified_follow_up integer NOT NULL DEFAULT 0;

COMMENT ON COLUMN ${flyway:defaultSchema}.document.unverified_values
    IS 'Values the model reported that had no resolvable crop, so they were not stored. A high count means the page is hard to read, not that the family sent nothing.';

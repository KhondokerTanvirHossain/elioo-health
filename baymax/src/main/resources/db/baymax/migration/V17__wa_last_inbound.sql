-- Sign-in is free (PO 2026-10-18, DR-39 amended): when a family asks for a sign-in code on the web, Medioo sends it to
-- their WhatsApp automatically, but only inside the 24-hour customer-service window that the family's OWN last message
-- opened. Inside it a free-form message is free; outside it only a paid template could be sent, and codes never use a
-- template. This table is the one fact that decision needs: when did this number last write to Medioo.
--
-- One row per number, keyed by the HMAC of the E.164 number (PhoneHasher, baymax.auth.hmac-secret), never the number:
-- the CHECK refuses anything that is not 64 lowercase hex characters, so no code path can store a number here.
-- Written only for senders who have an account (family_id NOT NULL). family_id is a foreign key WITHOUT cascade, so a
-- family delete that forgets this table fails loudly rather than leaving the row behind; deleteFamily removes it.

CREATE TABLE ${flyway:defaultSchema}.wa_last_inbound (
    phone_hash       varchar(64)  PRIMARY KEY CHECK (phone_hash ~ '^[0-9a-f]{64}$'),
    family_id        uuid         NOT NULL REFERENCES ${flyway:defaultSchema}.family_account (id),
    last_inbound_at  timestamptz  NOT NULL
);
CREATE INDEX wa_last_inbound_family_idx ON ${flyway:defaultSchema}.wa_last_inbound (family_id);

COMMENT ON TABLE  ${flyway:defaultSchema}.wa_last_inbound IS 'When each account holder''s WhatsApp number last wrote to Medioo; decides whether a sign-in code may be sent free (DR-39).';
COMMENT ON COLUMN ${flyway:defaultSchema}.wa_last_inbound.phone_hash IS 'HMAC-SHA256 of the E.164 number, hex. Never the number (CHECK).';
COMMENT ON COLUMN ${flyway:defaultSchema}.wa_last_inbound.last_inbound_at IS 'Only moves forward: a late, older webhook callback never shortens the window.';

-- BMX-5: OTP login, web sessions and the audit trail behind them.
-- Phone numbers never appear in these tables: every row carries an HMAC of the number (baymax.auth.hmac-secret),
-- so a leaked table or log line cannot be walked back to a person, and family_account stays the only place
-- a number is stored in clear.

CREATE TABLE ${flyway:defaultSchema}.otp_code (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_hash   varchar(64)  NOT NULL,
    code_hash    varchar(64)  NOT NULL,
    family_id    uuid         NULL REFERENCES ${flyway:defaultSchema}.family_account (id) ON DELETE SET NULL,
    expires_at   timestamptz  NOT NULL,
    attempts     integer      NOT NULL DEFAULT 0,
    consumed_at  timestamptz  NULL,
    burned_at    timestamptz  NULL,
    created_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX otp_code_phone_created_idx ON ${flyway:defaultSchema}.otp_code (phone_hash, created_at);

COMMENT ON TABLE  ${flyway:defaultSchema}.otp_code IS 'One row per 6-digit code issued; the code itself is stored hashed.';
COMMENT ON COLUMN ${flyway:defaultSchema}.otp_code.phone_hash IS 'HMAC-SHA256 of the E.164 number, hex. Never the number.';
COMMENT ON COLUMN ${flyway:defaultSchema}.otp_code.family_id IS 'Resolved when the code is issued, from the number the request carried; NULL when no account has that number. Never visible to the requester: the response is identical either way.';
COMMENT ON COLUMN ${flyway:defaultSchema}.otp_code.attempts IS 'Wrong guesses so far; at the configured maximum the code is burned.';
COMMENT ON COLUMN ${flyway:defaultSchema}.otp_code.consumed_at IS 'Set when the code was used successfully; a code is single-use.';
COMMENT ON COLUMN ${flyway:defaultSchema}.otp_code.burned_at IS 'Set when too many wrong attempts killed the code; a new request is required.';

CREATE TABLE ${flyway:defaultSchema}.web_session (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash    varchar(64)  NOT NULL UNIQUE,
    family_id     uuid         NOT NULL REFERENCES ${flyway:defaultSchema}.family_account (id) ON DELETE CASCADE,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    last_seen_at  timestamptz  NOT NULL DEFAULT now(),
    expires_at    timestamptz  NOT NULL,
    revoked_at    timestamptz  NULL
);
CREATE INDEX web_session_family_idx ON ${flyway:defaultSchema}.web_session (family_id);

COMMENT ON TABLE  ${flyway:defaultSchema}.web_session IS 'A logged-in browser. Identity is the family_account; the cookie carries a random token stored here hashed.';
COMMENT ON COLUMN ${flyway:defaultSchema}.web_session.expires_at IS 'Sliding: pushed forward on use, up to the configured session lifetime from the last use.';

CREATE TABLE ${flyway:defaultSchema}.audit_event (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kind         varchar(32)  NOT NULL CHECK (kind IN (
                     'otp_request', 'otp_verify_ok', 'otp_verify_fail', 'otp_burned',
                     'timeline_view', 'document_view', 'document_upload', 'document_delete', 'logout')),
    phone_hash   varchar(64)  NULL,
    family_id    uuid         NULL,
    patient_id   uuid         NULL,
    document_id  uuid         NULL,
    created_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX audit_event_kind_created_idx ON ${flyway:defaultSchema}.audit_event (kind, created_at);
CREATE INDEX audit_event_family_created_idx ON ${flyway:defaultSchema}.audit_event (family_id, created_at);

COMMENT ON TABLE ${flyway:defaultSchema}.audit_event IS 'Every OTP request and verify, every timeline and document view. UUIDs and hashes only; feeds the weekly export.';

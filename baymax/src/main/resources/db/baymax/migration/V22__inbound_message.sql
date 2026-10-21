-- F9a (Phase 0, PR 8): every inbound WhatsApp message, keyed so that "the same message twice is answered once" is a
-- property of the database, not of the code path that happens to run. Meta re-delivers a webhook it thinks was not
-- acknowledged; the webhook claims each message here BEFORE it answers 200, and only the claim that inserted the row
-- goes on to reply.
--
-- The key is HMAC-SHA256(server secret, "wamid:" || the provider id) through PhoneHasher, never the id itself: a wamid is
-- base64 of the sender's phone number, and family_account stays the only table that holds a number (V8, V17). The CHECK
-- refuses anything but 64 lowercase hex characters, so no future writer can store a raw id either.
--
-- Text and the media id (a handle that fetches the photo from Meta while Meta keeps it) are kept only for a sender who
-- resolved to a family, the only rows a delete on request can reach; enforced by a CHECK. The code also never keeps text
-- carrying a standalone six-digit run (a login code, however it is pasted back). A row with no family is purged after
-- 8 days (Meta retries for 7, and the key is all the dedupe needs). family_id / patient_id carry NO cascade, like
-- document: a delete path that forgets this table fails loudly. document_id is SET NULL as a backstop; the document
-- delete itself clears the row's patient, document and media id, keeping only the key and the family. Later the same
-- rows are the WhatsApp side of chat history (spec §7.5).
CREATE TABLE ${flyway:defaultSchema}.inbound_message (
    id                  uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_key        char(64)     NOT NULL,
    family_id           uuid         NULL REFERENCES ${flyway:defaultSchema}.family_account (id),
    patient_id          uuid         NULL REFERENCES ${flyway:defaultSchema}.patient_profile (id),
    document_id         uuid         NULL REFERENCES ${flyway:defaultSchema}.document (id) ON DELETE SET NULL,
    type                varchar(32)  NOT NULL,
    body                text         NULL,
    media_id            varchar(128) NULL,
    provider_timestamp  timestamptz  NULL,
    received_at         timestamptz  NOT NULL DEFAULT now(),
    handled_at          timestamptz  NULL,
    outcome             varchar(32)  NULL,
    CONSTRAINT inbound_message_provider_key_key UNIQUE (provider_key),
    CONSTRAINT inbound_message_provider_key_check CHECK (provider_key ~ '^[0-9a-f]{64}$'),
    CONSTRAINT inbound_message_content_needs_family CHECK ((body IS NULL AND media_id IS NULL) OR family_id IS NOT NULL)
);
CREATE INDEX inbound_message_family_received_idx ON ${flyway:defaultSchema}.inbound_message (family_id, received_at);
CREATE INDEX inbound_message_patient_idx ON ${flyway:defaultSchema}.inbound_message (patient_id);
CREATE INDEX inbound_message_document_idx ON ${flyway:defaultSchema}.inbound_message (document_id);
CREATE INDEX inbound_message_unknown_sender_idx ON ${flyway:defaultSchema}.inbound_message (received_at) WHERE family_id IS NULL;
CREATE INDEX inbound_message_unhandled_idx ON ${flyway:defaultSchema}.inbound_message (received_at) WHERE handled_at IS NULL;

COMMENT ON TABLE ${flyway:defaultSchema}.inbound_message IS
    'F9a: one row per inbound WhatsApp message, claimed before the webhook answers; the unique key is what makes a Meta re-delivery answered once.';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbound_message.provider_key IS
    'HMAC-SHA256 of "wamid:" || the provider message id, hex. Never the id: a wamid is base64 of the phone number (CHECK).';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbound_message.body IS
    'What the family typed, only when the sender resolved to a family (CHECK) and never text with a six-digit run. NULL otherwise.';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbound_message.media_id IS
    'Meta media id of a photo or file, only for a known family (CHECK); cleared when its document is deleted.';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbound_message.outcome IS
    'WaIntakeService.Outcome name, written once (handled_at IS NULL guard). NULL = in flight. CUT_OFF = a later startup closed it unanswered (counted once); a Meta re-delivery reclaims it.';

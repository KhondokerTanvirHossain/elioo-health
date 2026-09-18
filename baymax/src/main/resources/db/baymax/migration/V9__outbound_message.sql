-- BMX-6: every message Baymax composes for a family, gated or not. The body is the family-facing Bangla text;
-- urgency_reasons are machine codes (value_critical, diagnosis_present, ...), never document text.
CREATE TABLE ${flyway:defaultSchema}.outbound_message (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id        uuid         NOT NULL REFERENCES ${flyway:defaultSchema}.family_account (id) ON DELETE CASCADE,
    patient_id       uuid         NULL,
    document_id      uuid         NULL,
    kind             varchar(16)  NOT NULL CHECK (kind IN ('explanation', 'detail', 'retake')),
    urgency          varchar(16)  NOT NULL CHECK (urgency IN ('routine', 'this_week', 'now')),
    urgency_reasons  text         NULL,
    body             text         NULL,
    gate_status      varchar(16)  NOT NULL CHECK (gate_status IN ('released', 'pending', 'approved', 'rejected', 'failed_safety')),
    reviewer         varchar(64)  NULL,
    reject_reason    text         NULL,
    decided_at       timestamptz  NULL,
    sent_at          timestamptz  NULL,
    created_at       timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX outbound_message_gate_idx ON ${flyway:defaultSchema}.outbound_message (gate_status, created_at);
CREATE INDEX outbound_message_document_idx ON ${flyway:defaultSchema}.outbound_message (document_id, created_at);

COMMENT ON TABLE  ${flyway:defaultSchema}.outbound_message IS 'BMX-6: composed messages. Nothing with gate_status pending is ever delivered without an explicit approve.';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_message.body IS 'NULL when the safety check failed closed: the row records the attempt, no text was produced for the family.';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_message.sent_at IS 'When the message was handed to the delivery port (log in v1, WhatsApp in BMX-10).';

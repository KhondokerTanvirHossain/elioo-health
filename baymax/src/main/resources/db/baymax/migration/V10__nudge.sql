-- BMX-8: the proactive engine. One row per (patient, rule, trigger): the dedupe key. Every outcome is recorded —
-- sent, gated, held for the send window, or dropped with its reason — so the export can say why a family did
-- not hear from us. Nudges are never NOW: the CHECK below is the type-level assertion at the database.
CREATE TABLE ${flyway:defaultSchema}.nudge (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id    uuid NOT NULL REFERENCES ${flyway:defaultSchema}.family_account(id) ON DELETE CASCADE,
    patient_id   uuid NOT NULL REFERENCES ${flyway:defaultSchema}.patient_profile(id) ON DELETE CASCADE,
    rule         varchar(20) NOT NULL CHECK (rule IN ('follow_up_due', 'course_ending', 'medicine_changed', 'trend', 'silence')),
    trigger_key  text NOT NULL,
    urgency      varchar(16) NOT NULL CHECK (urgency IN ('routine', 'this_week')),
    status       varchar(12) NOT NULL CHECK (status IN ('sent', 'gated', 'held', 'dropped', 'failed')),
    drop_reason  text NULL,
    vars_json    jsonb NULL,
    message_id   uuid NULL,
    hold_until   timestamptz NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    resolved_at  timestamptz NULL,
    UNIQUE (patient_id, rule, trigger_key)
);
CREATE INDEX nudge_patient_created_idx ON ${flyway:defaultSchema}.nudge (patient_id, created_at);
CREATE INDEX nudge_held_idx ON ${flyway:defaultSchema}.nudge (status, hold_until);
COMMENT ON TABLE ${flyway:defaultSchema}.nudge IS 'BMX-8: one row per rule trigger per patient; the row is the dedupe key and the audit of what happened to it.';

-- a nudge is an outbound message of its own kind; the review gate and delivery are the same as BMX-6
ALTER TABLE ${flyway:defaultSchema}.outbound_message DROP CONSTRAINT IF EXISTS outbound_message_kind_check;
ALTER TABLE ${flyway:defaultSchema}.outbound_message ADD CONSTRAINT outbound_message_kind_check
    CHECK (kind IN ('explanation', 'detail', 'retake', 'nudge'));

-- opt-out, effective immediately: per patient or for the whole family. NULL = nudges allowed.
ALTER TABLE ${flyway:defaultSchema}.patient_profile ADD COLUMN nudges_opted_out_at timestamptz NULL;
ALTER TABLE ${flyway:defaultSchema}.family_account ADD COLUMN nudges_opted_out_at timestamptz NULL;

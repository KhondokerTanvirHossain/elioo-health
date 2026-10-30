-- DR-50 (PO ruling P7, 2026-10-30): one row per question typed in the web চ্যাট, and exactly what Medioo showed in answer.
-- Plan CHAT-api Task 1. The raw model text of a failed check is never stored: `answer` is the assembled text the family saw.
-- No cascade on any key (as V28), so a delete that forgets this table fails loudly: PostgresHealthRecordAdapter.deleteFamily
-- and deletePatient each delete its rows before the patient's and the family's. document_ids carries no foreign key: a turn
-- whose paper was deleted is kept and no longer listed (S10's rule, PostgresChatTurnAdapter.answered).
CREATE TABLE ${flyway:defaultSchema}.chat_turn (
    id            uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id     uuid          NOT NULL REFERENCES ${flyway:defaultSchema}.family_account (id),
    patient_id    uuid          NOT NULL REFERENCES ${flyway:defaultSchema}.patient_profile (id),
    lang          varchar(2)    NOT NULL CHECK (lang IN ('bn', 'en')),
    question      text          NOT NULL CHECK (char_length(question) BETWEEN 1 AND 1000),
    answer        text,
    kind          varchar(16)   CHECK (kind IN ('answer', 'emergency', 'limit', 'fallback', 'declined')),
    counted       boolean       NOT NULL DEFAULT false,
    handler       varchar(24)   CHECK (handler IN ('red_flag', 'off', 'no_model', 'cap', 'model', 'repair', 'fixed_after_repair', 'model_error')),
    violations    text,
    model_calls   integer       NOT NULL DEFAULT 0 CHECK (model_calls BETWEEN 0 AND 2),
    input_tokens  integer       NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens integer       NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    cost_usd      numeric(12, 8),
    latency_ms    bigint,
    document_ids  uuid[]        NOT NULL DEFAULT '{}',
    created_at    timestamptz   NOT NULL DEFAULT now(),
    answered_at   timestamptz,
    CONSTRAINT chat_turn_answered_has_kind CHECK ((answer IS NULL) = (kind IS NULL) AND (answer IS NULL) = (answered_at IS NULL)),
    CONSTRAINT chat_turn_emergency_no_model CHECK (kind IS DISTINCT FROM 'emergency' OR (model_calls = 0 AND NOT counted)),
    CONSTRAINT chat_turn_refusal_no_model CHECK (kind IS NULL OR kind NOT IN ('limit', 'declined') OR (model_calls = 0 AND NOT counted)),
    CONSTRAINT chat_turn_answer_was_modelled CHECK (kind IS DISTINCT FROM 'answer' OR model_calls >= 1)
);
CREATE INDEX chat_turn_family_idx ON ${flyway:defaultSchema}.chat_turn (family_id, created_at);
CREATE INDEX chat_turn_patient_idx ON ${flyway:defaultSchema}.chat_turn (patient_id, created_at);

COMMENT ON TABLE ${flyway:defaultSchema}.chat_turn IS
    'DR-50: the family''s own words and Medioo''s shown answers, one row per question asked in the web chat. Deleted with the family and with the patient; never logged above DEBUG.';
COMMENT ON COLUMN ${flyway:defaultSchema}.chat_turn.family_id IS
    'the account holder who asked: the viewer''s own family_account (a share member''s, for a shared patient), never the patient''s family';
COMMENT ON COLUMN ${flyway:defaultSchema}.chat_turn.answer IS
    'exactly what the family was shown, appended lines included; the raw model text of a refused answer is never stored';
COMMENT ON COLUMN ${flyway:defaultSchema}.chat_turn.counted IS
    'counted against baymax.chat.daily-cap (Asia/Dhaka day). Emergency, limit and declined turns are never counted';
COMMENT ON COLUMN ${flyway:defaultSchema}.chat_turn.violations IS
    'the violation codes of the refused model answers, codes only (AnswerCheck.forLog), never patient text';
COMMENT ON COLUMN ${flyway:defaultSchema}.chat_turn.document_ids IS
    'the papers the answer''s facts came from; a turn naming a deleted paper is no longer listed';

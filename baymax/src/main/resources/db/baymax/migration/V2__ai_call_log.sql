-- One row per LLM or Vision call made from the Baymax module (BMX-1).
-- Numbers and ids only. Never add prompt or response bodies, OCR text or any other patient text here.
CREATE TABLE ${flyway:defaultSchema}.ai_call_log (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id   uuid NULL,
    purpose       varchar(16) NOT NULL
                  CHECK (purpose IN ('ocr', 'extract', 'explain', 'chat', 'nudge', 'summary')),
    provider      varchar(32) NOT NULL,
    model         varchar(128) NOT NULL,
    input_tokens  integer NOT NULL DEFAULT 0,
    output_tokens integer NOT NULL DEFAULT 0,
    cost_usd      numeric(12, 8) NULL,       -- NULL = no price configured for provider/model; never guessed
    latency_ms    integer NULL,
    confidence    double precision NULL,     -- 0..1 where the call yields one (OCR average, extraction overall)
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ai_call_log_document_idx ON ${flyway:defaultSchema}.ai_call_log (document_id);
CREATE INDEX ai_call_log_created_idx ON ${flyway:defaultSchema}.ai_call_log (created_at);

COMMENT ON TABLE ${flyway:defaultSchema}.ai_call_log
    IS 'Per-call AI cost/usage log. No PHI: ids, counts, money, timings only.';

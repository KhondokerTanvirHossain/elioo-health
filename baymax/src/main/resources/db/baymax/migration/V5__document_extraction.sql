-- Extraction pipeline (BMX-2): one document = ordered pages, one structured LLM call, verified items.
-- Every item row carries the crop that proves it; an item without a resolvable crop is never written here.

CREATE TABLE ${flyway:defaultSchema}.document (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id         uuid NOT NULL REFERENCES ${flyway:defaultSchema}.patient_profile (id),
    family_id          uuid NOT NULL REFERENCES ${flyway:defaultSchema}.family_account (id),
    document_type      varchar(24) NULL
                       CHECK (document_type IN ('lab_report', 'prescription', 'discharge_summary',
                                                'imaging_report', 'other')),
    doc_date           date NULL,
    facility           varchar(200) NULL,
    extraction_json    jsonb NULL,              -- the model's structured output, as returned
    confidence_overall double precision NULL,
    status             varchar(16) NOT NULL DEFAULT 'RECEIVED'
                       CHECK (status IN ('RECEIVED', 'PROCESSING', 'DONE', 'NEEDS_RETAKE', 'FAILED')),
    status_reason      varchar(200) NULL,       -- why NEEDS_RETAKE or FAILED; developer-facing, no patient text
    model_final        varchar(128) NULL,       -- provider/model whose result was kept
    cost_usd           numeric(12, 8) NULL,     -- SUM(ai_call_log.cost_usd) for this document
    page_count         integer NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX document_patient_idx ON ${flyway:defaultSchema}.document (patient_id);
CREATE INDEX document_family_created_idx ON ${flyway:defaultSchema}.document (family_id, created_at);

-- One measured value, e.g. HbA1c 8.2 %. canonical_name is set only when it matched the configured marker list.
CREATE TABLE ${flyway:defaultSchema}.observation (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id     uuid NOT NULL REFERENCES ${flyway:defaultSchema}.patient_profile (id),
    document_id    uuid NOT NULL REFERENCES ${flyway:defaultSchema}.document (id) ON DELETE CASCADE,
    name           varchar(200) NOT NULL,
    canonical_name varchar(64) NULL,
    value          varchar(100) NOT NULL,
    unit           varchar(40) NULL,
    ref_low        varchar(40) NULL,
    ref_high       varchar(40) NULL,
    flag           varchar(10) NULL CHECK (flag IN ('low', 'normal', 'high', 'critical')),
    crop_key       text NOT NULL,               -- NOT NULL on purpose: no number without its source crop
    observed_at    timestamptz NOT NULL
);
CREATE INDEX observation_patient_canonical_idx
    ON ${flyway:defaultSchema}.observation (patient_id, canonical_name, observed_at);
CREATE INDEX observation_document_idx ON ${flyway:defaultSchema}.observation (document_id);

CREATE TABLE ${flyway:defaultSchema}.medication_event (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id     uuid NOT NULL REFERENCES ${flyway:defaultSchema}.patient_profile (id),
    document_id    uuid NOT NULL REFERENCES ${flyway:defaultSchema}.document (id) ON DELETE CASCADE,
    name           varchar(200) NOT NULL,
    dose_text      varchar(120) NULL,
    frequency_text varchar(120) NULL,
    duration_text  varchar(120) NULL,
    action         varchar(12) NOT NULL DEFAULT 'recorded'
                   CHECK (action IN ('recorded', 'started', 'continued', 'stopped', 'changed')),
    crop_key       text NOT NULL,
    at             timestamptz NOT NULL
);
CREATE INDEX medication_event_patient_idx ON ${flyway:defaultSchema}.medication_event (patient_id, at);
CREATE INDEX medication_event_document_idx ON ${flyway:defaultSchema}.medication_event (document_id);

CREATE TABLE ${flyway:defaultSchema}.follow_up (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id  uuid NOT NULL REFERENCES ${flyway:defaultSchema}.patient_profile (id),
    document_id uuid NOT NULL REFERENCES ${flyway:defaultSchema}.document (id) ON DELETE CASCADE,
    instruction varchar(400) NOT NULL,
    due_date    date NULL,
    status      varchar(12) NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'done', 'cancelled')),
    crop_key    text NOT NULL
);
CREATE INDEX follow_up_due_idx ON ${flyway:defaultSchema}.follow_up (status, due_date);
CREATE INDEX follow_up_document_idx ON ${flyway:defaultSchema}.follow_up (document_id);

-- BMX-1 carry-over: a failed provider call is recorded too, with zero tokens, NULL cost and its real latency.
ALTER TABLE ${flyway:defaultSchema}.ai_call_log
    ADD COLUMN status varchar(8) NOT NULL DEFAULT 'ok' CHECK (status IN ('ok', 'failed'));

COMMENT ON TABLE ${flyway:defaultSchema}.document IS 'One uploaded document: pages in object storage, extraction result here.';
COMMENT ON COLUMN ${flyway:defaultSchema}.observation.crop_key IS 'Object key of the crop proving this value; never null, so no number can surface unverified.';
COMMENT ON COLUMN ${flyway:defaultSchema}.ai_call_log.status IS 'ok = provider answered; failed = call errored (zero tokens, NULL cost, real latency).';

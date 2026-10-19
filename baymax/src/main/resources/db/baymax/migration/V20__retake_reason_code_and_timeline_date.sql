-- F5 (Phase 1b, B4): why a document needs a retake, as a machine code mapped to Bangla copy (RetakeReason). status_reason
-- stays developer-facing English and is never rendered, on a page or in the JSON API. 'legacy' marks a pre-V20 retake
-- whose prose matched no known form; no code path writes it.
--
-- Ownership: V16 and V18 already ALTERed baymax.document in production, so this role owns the table. Every statement
-- below runs in Flyway's one transaction (Postgres DDL is transactional): a failure leaves the schema at V19.
ALTER TABLE ${flyway:defaultSchema}.document ADD COLUMN status_reason_code varchar(48) NULL
    CONSTRAINT document_status_reason_code_check
    CHECK (status_reason_code ~ '^(no_text|low_overall|legacy|expected_section_empty:[a-z_]+|low_section:[a-z_]+)$');

-- The four prose forms are DocumentExtractionService's REASON_* constants; DocumentReasonCodeConstraintTest feeds this
-- block those constants, so the SQL and the Java cannot drift. A pre-V20 low-section retake never named its section.
-- backfill:begin
UPDATE ${flyway:defaultSchema}.document SET status_reason_code = CASE
    WHEN status_reason = 'no readable text found on the page' THEN 'no_text'
    WHEN status_reason LIKE 'the document was not read confidently enough%' THEN 'low_overall'
    WHEN status_reason ~ '^nothing was read from the [a-z_]+ of this '
        THEN 'expected_section_empty:' || substring(status_reason FROM '^nothing was read from the ([a-z_]+) of this ')
    WHEN status_reason LIKE 'part of the document was not read confidently enough%' THEN 'low_section:unknown'
    ELSE 'legacy'
END
WHERE status = 'NEEDS_RETAKE'
-- backfill:end
;

-- Added after the backfill and VALID (no NOT VALID): every existing row is checked here, so a later write to an old
-- row can never trip a constraint the row was let off. As with V14, the rule lives where every writer meets it.
-- constraint:begin
ALTER TABLE ${flyway:defaultSchema}.document
    ADD CONSTRAINT document_reason_code_only_on_retake CHECK (status_reason_code IS NULL OR status = 'NEEDS_RETAKE'),
    ADD CONSTRAINT document_retake_has_reason_code CHECK (status <> 'NEEDS_RETAKE' OR status_reason_code IS NOT NULL)
-- constraint:end
;

-- The timeline key (spec 4.15: newest DOCUMENT date first). An upload with no printed date uses its upload day in
-- Dhaka, the patient's calendar (F7, PatientCalendar). Generated, so the date a page shows and the order it sorts by
-- can never disagree, and nothing writes it.
ALTER TABLE ${flyway:defaultSchema}.document ADD COLUMN timeline_date date
    GENERATED ALWAYS AS (coalesce(doc_date, (created_at AT TIME ZONE 'Asia/Dhaka')::date)) STORED;
CREATE INDEX document_patient_timeline_idx
    ON ${flyway:defaultSchema}.document (patient_id, timeline_date DESC, created_at DESC, id DESC);

COMMENT ON COLUMN ${flyway:defaultSchema}.document.status_reason_code IS
    'F5: why a retake, as a RetakeReason code; the only reason a page or the JSON API may carry';
COMMENT ON COLUMN ${flyway:defaultSchema}.document.timeline_date IS
    'printed date, else the Asia/Dhaka day of created_at; the timeline orders by it (C2)';

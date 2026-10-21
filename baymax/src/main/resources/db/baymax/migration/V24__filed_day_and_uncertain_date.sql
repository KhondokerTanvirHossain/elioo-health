-- D2: a retake is filed under the day it was uploaded, never under a date misread off the page it could not read; and
-- the model's document_date_uncertain is stored, so the timeline can mark the date and a trend can drop its direction.
--
-- Every statement below runs in Flyway's one transaction (Postgres DDL is transactional): a failure leaves the schema at
-- the previous version. This role owns baymax.document (V16, V18 and V20 already ALTERed it in production).

-- The model's flag, as stored. NOT NULL DEFAULT false: a model that said nothing did not say the date was uncertain.
ALTER TABLE ${flyway:defaultSchema}.document ADD COLUMN doc_date_uncertain boolean NOT NULL DEFAULT false;

-- extraction_json is the model's output as returned (V5), so a legacy or off-schema value ("yes", 1, null) is never cast:
-- it stays false. A cast that threw here would fail Flyway at startup after deploy.sh has removed the old container.
-- backfill:begin
UPDATE ${flyway:defaultSchema}.document
   SET doc_date_uncertain = (extraction_json -> 'document_date_uncertain')::boolean
 WHERE jsonb_typeof(extraction_json -> 'document_date_uncertain') = 'boolean'
-- backfill:end
;

-- The filing day, redefined where every writer meets it. V20 filed every paper under coalesce(doc_date, upload day), so
-- a blurred retake sent today could sit in March 2021. A retake (NEEDS_RETAKE) or a paper with no printed date is filed
-- under its upload day in Asia/Dhaka, the patient's calendar; every other paper under its printed date. The printed
-- doc_date is kept on a retake (a later reading of the same paper may confirm it) and is never shown as its date.
-- Dropped and re-added rather than ALTER COLUMN ... SET EXPRESSION, which needs Postgres 17.
DROP INDEX ${flyway:defaultSchema}.document_patient_timeline_idx;
ALTER TABLE ${flyway:defaultSchema}.document DROP COLUMN timeline_date;
ALTER TABLE ${flyway:defaultSchema}.document
    ADD COLUMN timeline_date date GENERATED ALWAYS AS (
        CASE WHEN status = 'NEEDS_RETAKE' OR doc_date IS NULL
             THEN (created_at AT TIME ZONE 'Asia/Dhaka')::date
             ELSE doc_date END) STORED,
    ADD COLUMN timeline_date_is_upload_day boolean GENERATED ALWAYS AS (status = 'NEEDS_RETAKE' OR doc_date IS NULL) STORED;
-- exactly as V20 made it
CREATE INDEX document_patient_timeline_idx
    ON ${flyway:defaultSchema}.document (patient_id, timeline_date DESC, created_at DESC, id DESC);

COMMENT ON COLUMN ${flyway:defaultSchema}.document.timeline_date IS
    'D2: the only definition of a paper''s date: the printed date, else (a retake, or nothing printed) the Asia/Dhaka day of created_at; every reader reads it, nothing re-derives it';
COMMENT ON COLUMN ${flyway:defaultSchema}.document.timeline_date_is_upload_day IS
    'D2: true when timeline_date is the Asia/Dhaka upload day rather than the printed date; the only definition of that flag';
COMMENT ON COLUMN ${flyway:defaultSchema}.document.doc_date_uncertain IS
    'D2: the model marked the printed date uncertain (document_date_uncertain); the paper is still filed under it, and marked';

-- The clinical narrative of a document: what the patient said, what the doctor found, what was advised.
-- Stored as JSONB on the document rather than in new tables: these are verbatim lines from one page, read
-- together, never queried field by field. The trend engine reads `observation`; this is what BMX-6 turns
-- into a Bangla explanation, and `advice` in particular is load-bearing there.
ALTER TABLE ${flyway:defaultSchema}.document
    ADD COLUMN clinical_context jsonb NULL,
    ADD COLUMN unverified_clinical_context integer NOT NULL DEFAULT 0;

COMMENT ON COLUMN ${flyway:defaultSchema}.document.clinical_context
    IS 'Verbatim clinical sections: chief_complaint, history, examination, diagnosis, investigations_advised, advice, referral. Each item keeps the crop key that proves it. Bangla stays Bangla; nothing is translated or inferred here.';
COMMENT ON COLUMN ${flyway:defaultSchema}.document.unverified_clinical_context
    IS 'Clinical-context items read but not locatable on the page, so not kept. Counted like every other section.';

-- Medicines gain the two fields the v1 schema transcribes: how it is taken, and when.
-- Both verbatim from the page ("রাত", "খাওয়ার পর"), never normalised.
ALTER TABLE ${flyway:defaultSchema}.medication_event
    ADD COLUMN route       varchar(60) NULL,
    ADD COLUMN timing_text varchar(120) NULL;

COMMENT ON COLUMN ${flyway:defaultSchema}.medication_event.timing_text
    IS 'When to take it, exactly as written on the page and in its original language.';

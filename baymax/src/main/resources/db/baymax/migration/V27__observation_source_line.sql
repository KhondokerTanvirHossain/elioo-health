-- Plan B6: the OCR line a value was read from, so the two halves of one printed blood-pressure reading (BP 130 / 80) can
-- be paired. They cannot be paired on crop_key: extraction gives every value its own crop (…/crop-v1.jpg, …/crop-v2.jpg)
-- even when both are cut from the same line, and stored_object keeps no content hash.
--
-- Written at extraction (DocumentExtractionService.cropForValue, from CropCutter.Cut#line) for every value cropped from
-- OCR words; NULL for a value recovered from a region of the page image (it has no OCR line) and for every value stored
-- before this migration. A paper read before B6 is backfilled only by re-cropping it (the admin re-crop route runs the
-- same extraction path; running it over production is a PO call, Q8). No backfill here: the line cannot be derived from
-- what is stored.
ALTER TABLE ${flyway:defaultSchema}.observation
    ADD COLUMN source_line varchar(40) NULL
        CONSTRAINT observation_source_line_shape CHECK (source_line IS NULL OR source_line ~ '^[0-9]+:[0-9]+-[0-9]+$');

COMMENT ON COLUMN ${flyway:defaultSchema}.observation.source_line IS
    'B6: the OCR line the value was read from, <page>:<start>-<end> (character offsets of SpanLocator.lineAround on that page''s text); NULL for a value recovered from the page image (no OCR line) and for every value stored before plan B6. Two values on one line of one document are one printed reading (BP 130 / 80); nothing else pairs values';

-- Review of #166, finding 5 (DR-12, DR-16): a prescription line an answer quotes ({{rx:n}}, inserted by code) is shown with
-- its crop, as every prescription line is everywhere else, so the family can check the transcription against the paper.
-- A turn stores which lines it quoted: the paper and the stored crop key of each, in the order the answer quotes them.
-- Two arrays of one length, never a crop without its paper; and each quoted line's paper is one of the papers the answer
-- came from (document_ids), so S10's rule (a turn citing a deleted paper is no longer listed) covers the crops too.
ALTER TABLE ${flyway:defaultSchema}.chat_turn
    ADD COLUMN medicine_document_ids uuid[] NOT NULL DEFAULT '{}',
    ADD COLUMN medicine_crop_keys    text[] NOT NULL DEFAULT '{}',
    ADD CONSTRAINT chat_turn_medicine_pairs CHECK (cardinality(medicine_document_ids) = cardinality(medicine_crop_keys)),
    ADD CONSTRAINT chat_turn_medicine_on_a_cited_paper CHECK (medicine_document_ids <@ document_ids);

COMMENT ON COLUMN ${flyway:defaultSchema}.chat_turn.medicine_document_ids IS
    'the paper of each prescription line the answer quotes, in the order quoted; each is one of document_ids';
COMMENT ON COLUMN ${flyway:defaultSchema}.chat_turn.medicine_crop_keys IS
    'the stored crop key of each quoted line (medication_event.crop_key), paired by position with medicine_document_ids';

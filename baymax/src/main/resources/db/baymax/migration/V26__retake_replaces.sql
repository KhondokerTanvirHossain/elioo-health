-- Plan D1: a retake names the paper it replaces. Sent from the retake page (or the JSON upload with `replaces`), the new
-- photo links back to the NEEDS_RETAKE paper, so the old paper's retake card leaves the চ্যাট stack once the new one is
-- in (PostgresTimelineReadAdapter.IN_FLIGHT). Nothing recorded this before: the stale card stayed for up to 30 days,
-- asking for a photo the family had already sent. WhatsApp retakes carry no such signal and stay unlinked (Q7).
--
-- A column, not a table: it goes when the document goes, so both delete paths already cover it. ON DELETE SET NULL keeps
-- a retake when the paper it replaced is deleted (the owner's delete, B4c).
--
-- Ownership: V16, V18, V20 and V21 already ALTERed baymax.document in production, so this role owns the table. Every
-- statement runs in Flyway's one transaction: a failure leaves the schema at the version before this one.
ALTER TABLE ${flyway:defaultSchema}.document
    ADD COLUMN replaces_document_id uuid NULL REFERENCES ${flyway:defaultSchema}.document (id) ON DELETE SET NULL;

CREATE INDEX document_replaces_idx ON ${flyway:defaultSchema}.document (replaces_document_id)
    WHERE replaces_document_id IS NOT NULL;

-- The rule every writer meets (the V14 lesson): a retake replaces an older paper of the same patient, and never itself.
-- Checked here, not only in UploadService, so no later writer can link across patients (or families). "Older" (by
-- upload) rules out a cycle for every writer: a replay of the press that made A, sent naming B (A's own replacement),
-- would otherwise link A to B while B links A, and IN_FLIGHT would then hide both, so a paper still owed a photo would
-- leave the stack. A retake's new photo is always uploaded after the paper it replaces.
--
-- The NULL pass is required, not a nicety: ON DELETE SET NULL is itself an UPDATE OF replaces_document_id to NULL, so
-- without it deleting the replaced paper (B4c's owner-only delete, the HTML delete, a family or patient delete) would
-- fail with this trigger's message and answer 500.
CREATE FUNCTION ${flyway:defaultSchema}.document_replaces_same_patient() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.replaces_document_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF NEW.replaces_document_id = NEW.id OR NOT EXISTS (
            SELECT 1 FROM ${flyway:defaultSchema}.document r
            WHERE r.id = NEW.replaces_document_id AND r.patient_id = NEW.patient_id) THEN
        RAISE EXCEPTION 'a retake replaces a paper of the same patient' USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (
            SELECT 1 FROM ${flyway:defaultSchema}.document r
            WHERE r.id = NEW.replaces_document_id AND r.created_at < NEW.created_at) THEN
        RAISE EXCEPTION 'a retake replaces an older paper' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER document_replaces_same_patient BEFORE INSERT OR UPDATE OF replaces_document_id
    ON ${flyway:defaultSchema}.document
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.document_replaces_same_patient();

COMMENT ON COLUMN ${flyway:defaultSchema}.document.replaces_document_id IS
    'D1: the NEEDS_RETAKE paper this upload was sent to replace (an older paper of the same patient, trigger-checked; first link wins); NULL when it replaces none';

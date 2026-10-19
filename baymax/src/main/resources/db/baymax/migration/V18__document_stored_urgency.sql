-- F1 (Phase 1b, A10): a document's urgency, stored on the document, written by one writer
-- (StoredUrgencyService), and only ever raised. Every surface (timeline, result, chat, WhatsApp) reads these columns,
-- never a message row. Until now urgency lived only on outbound_message: if explain() threw, nothing was stored, and
-- a later detail() could assess higher than the family had been told.
--
-- The invariant is asserted HERE, where every writer meets it, not in the writer (CLAUDE.md, the V14 lesson):
-- a BEFORE UPDATE trigger with no column list rejects a lower level, a NULL after a value, and a dropped reason.
ALTER TABLE ${flyway:defaultSchema}.document
    ADD COLUMN urgency             varchar(16) NULL CHECK (urgency IN ('routine', 'this_week', 'now')),
    ADD COLUMN urgency_reasons     jsonb       NULL CHECK (urgency_reasons IS NULL OR jsonb_typeof(urgency_reasons) = 'array'),
    ADD COLUMN urgency_assessed_at timestamptz NULL;

ALTER TABLE ${flyway:defaultSchema}.document ADD CONSTRAINT document_urgency_complete
    CHECK ((urgency IS NULL) = (urgency_reasons IS NULL) AND (urgency IS NULL) = (urgency_assessed_at IS NULL));

CREATE FUNCTION ${flyway:defaultSchema}.urgency_rank(u varchar) RETURNS int LANGUAGE sql IMMUTABLE
    AS $$ SELECT array_position(ARRAY['routine', 'this_week', 'now']::varchar[], u) $$;

CREATE FUNCTION ${flyway:defaultSchema}.document_urgency_never_lowers() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.urgency IS NULL THEN
        RETURN NEW;                                   -- a first assessment may be any level
    END IF;
    IF NEW.urgency IS NULL
            OR ${flyway:defaultSchema}.urgency_rank(NEW.urgency) < ${flyway:defaultSchema}.urgency_rank(OLD.urgency) THEN
        RAISE EXCEPTION 'document urgency may not be lowered (% -> %)', OLD.urgency, coalesce(NEW.urgency, 'NULL')
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.urgency_reasons IS NULL OR NOT (NEW.urgency_reasons @> OLD.urgency_reasons) THEN
        RAISE EXCEPTION 'document urgency reasons may not be removed' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER document_urgency_never_lowers BEFORE UPDATE ON ${flyway:defaultSchema}.document
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.document_urgency_never_lowers();

-- A11's sweep stamps an attempt before assessing, so a document whose assessment throws goes to the back of the queue
-- instead of being picked first every hour (it is not urgency: the trigger ignores it).
ALTER TABLE ${flyway:defaultSchema}.document ADD COLUMN urgency_attempted_at timestamptz NULL;

-- backfill:begin
-- The highest level any explanation or detail ever carried: never store less than a family was told.
-- outbound_message.urgency_reasons is text holding a JSON array (since PR 5) or the older comma-joined form.
-- The legacy form cannot be split correctly when a marker name has a comma; those rows keep a fragment.
UPDATE ${flyway:defaultSchema}.document d
SET urgency = m.top, urgency_reasons = m.reasons, urgency_assessed_at = m.at
FROM (SELECT o.document_id,
             (ARRAY['routine', 'this_week', 'now'])[max(${flyway:defaultSchema}.urgency_rank(o.urgency))] AS top,
             coalesce(jsonb_agg(DISTINCT trim(r.reason) ORDER BY trim(r.reason))
                      FILTER (WHERE coalesce(trim(r.reason), '') <> ''), '[]'::jsonb) AS reasons,
             max(o.created_at) AS at
      FROM ${flyway:defaultSchema}.outbound_message o
      LEFT JOIN LATERAL jsonb_array_elements_text(CASE
              WHEN o.urgency_reasons LIKE '[%' THEN o.urgency_reasons::jsonb
              WHEN coalesce(o.urgency_reasons, '') = '' THEN '[]'::jsonb
              ELSE to_jsonb(string_to_array(o.urgency_reasons, ',')) END) AS r(reason) ON true
      WHERE o.document_id IS NOT NULL AND o.kind IN ('explanation', 'detail')
      GROUP BY o.document_id) m
WHERE d.id = m.document_id;
-- backfill:end

COMMENT ON COLUMN ${flyway:defaultSchema}.document.urgency IS
    'F1: code-computed urgency, max over every assessment; a trigger rejects lowering. NULL = never assessed.';
COMMENT ON COLUMN ${flyway:defaultSchema}.document.urgency_reasons IS
    'F1: JSON array of machine codes (value_critical:<marker>|stopgap, ...); only grows. Marker names, never document text.';

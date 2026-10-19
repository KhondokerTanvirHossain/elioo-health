-- F2 (Phase 1b, B1): each shown value's verdict, written by the F1 writer (PostgresDocumentUrgencyAdapter, through
-- StoredUrgencyService) in the same transaction as the document's urgency, from the same rule branch that set it.
-- Value rows, result-now's "what caused it" and chat cards render ONLY from these columns.
--
-- Severity order: inside < unknown < outside < critical. UNKNOWN outranks INSIDE: a value the code cannot place
-- withholds reassurance. As with V18, the invariant lives HERE, where every writer meets it (the V14 lesson): a
-- BEFORE UPDATE trigger rejects any softening — a lower kind, the same kind at a lower level, or a NULL after a value.
ALTER TABLE ${flyway:defaultSchema}.observation
    ADD COLUMN verdict        varchar(8)  NULL CHECK (verdict IN ('inside', 'unknown', 'outside', 'critical')),
    ADD COLUMN verdict_level  varchar(16) NULL CHECK (verdict_level IN ('routine', 'this_week', 'now')),
    ADD COLUMN verdict_side   varchar(4)  NULL CHECK (verdict_side IN ('high', 'low')),
    ADD COLUMN verdict_reason text        NULL;

ALTER TABLE ${flyway:defaultSchema}.observation
    ADD CONSTRAINT observation_verdict_complete
        CHECK ((verdict IS NULL) = (verdict_level IS NULL) AND (verdict IS NULL) = (verdict_reason IS NULL)),
    ADD CONSTRAINT observation_verdict_reason_names_its_rule
        CHECK (verdict_reason IS NULL OR verdict_reason ~ '\|(stopgap|threshold:.+)$'),
    ADD CONSTRAINT observation_inside_is_routine CHECK (verdict IS DISTINCT FROM 'inside' OR verdict_level = 'routine'),
    ADD CONSTRAINT observation_critical_is_now CHECK (verdict IS DISTINCT FROM 'critical' OR verdict_level = 'now'),
    -- outside and critical say which end; inside and unknown have no end. A page never guesses a direction.
    ADD CONSTRAINT observation_verdict_side_matches_kind
        CHECK ((verdict IN ('outside', 'critical')) = (verdict_side IS NOT NULL));

CREATE FUNCTION ${flyway:defaultSchema}.verdict_rank(v varchar) RETURNS int LANGUAGE sql IMMUTABLE
    AS $$ SELECT array_position(ARRAY['inside', 'unknown', 'outside', 'critical']::varchar[], v) $$;

CREATE FUNCTION ${flyway:defaultSchema}.observation_verdict_never_softens() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.verdict IS NOT NULL AND (NEW.verdict IS NULL
            OR ${flyway:defaultSchema}.verdict_rank(NEW.verdict) < ${flyway:defaultSchema}.verdict_rank(OLD.verdict)
            OR (NEW.verdict = OLD.verdict AND ${flyway:defaultSchema}.urgency_rank(NEW.verdict_level)
                                              < ${flyway:defaultSchema}.urgency_rank(OLD.verdict_level))) THEN
        RAISE EXCEPTION 'observation verdict may not soften (% -> %)', OLD.verdict, coalesce(NEW.verdict, 'NULL')
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER observation_verdict_never_softens BEFORE UPDATE ON ${flyway:defaultSchema}.observation
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.observation_verdict_never_softens();

-- No backfill here: UrgencySweepJob assesses every DONE document with an observation that has no verdict yet
-- (PostgresDocumentUrgencyAdapter.NEEDING_ASSESSMENT), at startup and hourly, through the one writer.

COMMENT ON COLUMN ${flyway:defaultSchema}.observation.verdict IS
    'F2: the value''s verdict from the rule that set the document''s urgency; only rises (trigger). NULL = not yet assessed.';
COMMENT ON COLUMN ${flyway:defaultSchema}.observation.verdict_reason IS
    'F2: a raising row''s exact urgency reason, else value_inside/value_unknown/value_outside_no_escalation; ends |stopgap or |threshold:<src>.';

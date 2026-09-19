-- DR-19 (2026-09-19): a date-bound nudge refused by a cap is deferred to the next day, not discarded.
ALTER TABLE ${flyway:defaultSchema}.nudge DROP CONSTRAINT IF EXISTS nudge_status_check;
ALTER TABLE ${flyway:defaultSchema}.nudge ADD CONSTRAINT nudge_status_check
    CHECK (status IN ('sent', 'gated', 'held', 'deferred', 'dropped', 'failed'));
COMMENT ON COLUMN ${flyway:defaultSchema}.nudge.status IS
    'sent | gated | held (waiting for the send window) | deferred (cap refused a date-bound trigger; retried) | dropped | failed';

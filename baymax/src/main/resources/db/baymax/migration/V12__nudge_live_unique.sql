-- DR-22 (2026-09-19): nudge uniqueness is a partial unique index over LIVE rows, not a blanket constraint.
--
-- V10's UNIQUE (patient_id, rule, trigger_key) covered every row whatever its status. DR-19's retry marks the
-- consumed deferral 'dropped' and keeps it (the audit trail is the whole point of the deferred column), then
-- inserts the retry — which collided with the row it had just retired. In production that surfaced as a 500 on
-- the evaluation with the deferral already consumed: the nudge was retired, no replacement row was written, no
-- message was composed, and the family was never told about the appointment. DR-19 and DR-20 both defeated one
-- day later, by the very mechanism meant to save the nudge.
--
-- One ACTIVE nudge per trigger; history is preserved. 'dropped' is the retired state (a superseded loser, a cap
-- refusal that expired, or a consumed deferral); 'failed' is terminal too and must not block a later retry.
ALTER TABLE ${flyway:defaultSchema}.nudge DROP CONSTRAINT IF EXISTS nudge_patient_id_rule_trigger_key_key;

CREATE UNIQUE INDEX nudge_live_trigger_idx
    ON ${flyway:defaultSchema}.nudge (patient_id, rule, trigger_key)
    WHERE status NOT IN ('dropped', 'failed');

COMMENT ON INDEX ${flyway:defaultSchema}.nudge_live_trigger_idx IS
    'DR-22: at most one live (sent|gated|held|deferred) nudge per (patient, rule, trigger). Retired rows —
     dropped, failed — are exempt so a consumed deferral can be retried without colliding with its own history.
     Dedupe against retired rows is NudgePort.exists, which still counts every row: the index frees the key,
     it does not decide whether a trigger may fire again.';

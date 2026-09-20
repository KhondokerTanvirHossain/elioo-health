-- The invariant behind V13, asserted once at the database instead of at each call site.
--
-- V13 was scoped to ReviewGateService.approve, the path under discussion, rather than to the rule it protects.
-- Both release paths (ExplanationService, NudgeComposer) kept stamping sent_at at save time and discarding the
-- delivery outcome, so the first real WhatsApp delivery produced a row that said delivery_status NULL with no
-- wamid while the message had demonstrably gone out. A fix scoped to one path leaves the defect live on every
-- other one; the rule has to live where every writer meets it.
--
-- The rule: sent_at means "the provider accepted it". It may be set when the provider answered (delivery_status
-- = 'sent'), or on the log adapter, which delivers nothing and records no delivery_status at all — that case
-- keeps its v1 meaning of "handed over". It may never be set alongside a failure.
ALTER TABLE ${flyway:defaultSchema}.outbound_message
    ADD CONSTRAINT outbound_message_sent_at_requires_acceptance
        CHECK (sent_at IS NULL OR delivery_status IS NULL OR delivery_status = 'sent');

ALTER TABLE ${flyway:defaultSchema}.outbound_message
    ADD CONSTRAINT outbound_message_sent_status_has_provider_id
        CHECK (delivery_status <> 'sent' OR provider_message_id IS NOT NULL);

ALTER TABLE ${flyway:defaultSchema}.outbound_message
    ADD CONSTRAINT outbound_message_failed_status_has_reason
        CHECK (delivery_status <> 'failed' OR delivery_error IS NOT NULL);

COMMENT ON CONSTRAINT outbound_message_sent_at_requires_acceptance ON ${flyway:defaultSchema}.outbound_message IS
    'sent_at may not coexist with a failed or pending delivery. A row can never claim the family received a
     message the provider refused — whichever code path wrote it.';
COMMENT ON CONSTRAINT outbound_message_sent_status_has_provider_id ON ${flyway:defaultSchema}.outbound_message IS
    'A sent delivery carries the provider''s own id; without one there is nothing to correlate the status
     callback against and no evidence the send happened.';
COMMENT ON CONSTRAINT outbound_message_failed_status_has_reason ON ${flyway:defaultSchema}.outbound_message IS
    'A failure always records why, as a machine code — window_expired is expected in phase 1 and is a signal
     about review latency, not an error to hide.';

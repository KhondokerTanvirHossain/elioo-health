-- BMX-10: delivery is a real network call now, so the row has to record what actually happened to it.
--
-- Before this, ReviewGateService.approve marked the row APPROVED, set sent_at, and called delivery.deliver()
-- without looking at the result. With the log adapter that was harmless — logging does not fail. Over WhatsApp
-- it does: an expired token, a closed 24-hour window, a Meta 4xx. A failed send would have left a row saying
-- APPROVED with sent_at populated and nothing delivered, which is the crop-invariant defect again: a field
-- satisfied by something happening nearby rather than by the thing itself.
--
-- sent_at now means "the provider accepted it", and nothing else sets it.
ALTER TABLE ${flyway:defaultSchema}.outbound_message
    ADD COLUMN delivery_status      varchar(16) NULL
        CHECK (delivery_status IN ('pending', 'sent', 'failed')),
    ADD COLUMN provider_message_id  varchar(128) NULL,
    ADD COLUMN delivery_error       varchar(64)  NULL;

CREATE INDEX outbound_message_delivery_idx
    ON ${flyway:defaultSchema}.outbound_message (delivery_status, created_at);

COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_message.sent_at IS
    'When the PROVIDER ACCEPTED the message. NULL whenever it did not — a failed send leaves this null and
     records why in delivery_error. A reviewer must be able to read sent_at as "the family received it".';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_message.delivery_status IS
    'pending (handed over, no outcome yet) | sent | failed. NULL when the log adapter handled it, which is not
     a delivery at all.';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_message.provider_message_id IS
    'The provider''s own id (WhatsApp wamid), for correlating the delivery status callback.';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_message.delivery_error IS
    'Machine code, never provider prose: window_expired | token_invalid | not_allowlisted | send_failed.
     window_expired is an EXPECTED outcome in phase 1, not an error to engineer around — if it is common that
     is a finding about review latency, which is what the pilot needs to learn before the gate opens.';

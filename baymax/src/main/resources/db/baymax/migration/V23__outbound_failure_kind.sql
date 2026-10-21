-- Phase 0, PR 9: a document that ends FAILED sends the family a notice, because they were told "reading it" and
-- silence after that is the worst outcome. The notice is an outbound message of its own kind, so it passes the same
-- gate stage and records its provider outcome (V13/V14).
ALTER TABLE ${flyway:defaultSchema}.outbound_message DROP CONSTRAINT IF EXISTS outbound_message_kind_check;
ALTER TABLE ${flyway:defaultSchema}.outbound_message ADD CONSTRAINT outbound_message_kind_check
    CHECK (kind IN ('explanation', 'detail', 'retake', 'nudge', 'failure'));

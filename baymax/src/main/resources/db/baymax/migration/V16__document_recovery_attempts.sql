-- F9b (Phase 0, PR 4): how many times a startup sweep has re-run this document. Incremented and committed BEFORE
-- the work, so a document that kills the JVM (1200 MB container, deploy.sh:50) is retried a bounded number of times
-- and then failed, instead of crash-looping a container that restarts unless-stopped.
ALTER TABLE ${flyway:defaultSchema}.document ADD COLUMN recovery_attempts integer NOT NULL DEFAULT 0;

-- DR-41 item 5: দেখেছি hides the global NOW banner for the account holder who tapped, for exactly what they saw.
-- It is a display preference, never a change to the record: nothing here touches document, observation or outbound_message.
-- Plan B3. No cascade on any key, so a delete that forgets this table fails loudly: PostgresHealthRecordAdapter.deleteFamily
-- and deletePatient, and PostgresDocumentRecordAdapter.deleteDocument, each delete its rows before the document's.
ALTER TABLE ${flyway:defaultSchema}.document ADD CONSTRAINT document_id_patient_key UNIQUE (id, patient_id);
CREATE TABLE ${flyway:defaultSchema}.now_ack (
    family_id      uuid        NOT NULL REFERENCES ${flyway:defaultSchema}.family_account (id),
    patient_id     uuid        NOT NULL REFERENCES ${flyway:defaultSchema}.patient_profile (id),
    document_id    uuid        NOT NULL,
    urgency        varchar(16) NOT NULL CHECK (urgency = 'now'),
    reasons_digest char(64)    NOT NULL CHECK (reasons_digest ~ '^[0-9a-f]{64}$'),
    release_state  varchar(16) NOT NULL CHECK (release_state IN ('none', 'pending', 'released', 'rejected', 'failed_safety')),
    acked_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (family_id, document_id, urgency, reasons_digest, release_state),
    FOREIGN KEY (document_id, patient_id) REFERENCES ${flyway:defaultSchema}.document (id, patient_id)
);
CREATE INDEX now_ack_patient_idx ON ${flyway:defaultSchema}.now_ack (patient_id);
CREATE INDEX now_ack_document_idx ON ${flyway:defaultSchema}.now_ack (document_id);

COMMENT ON TABLE ${flyway:defaultSchema}.now_ack IS
    'DR-41 item 5: a দেখেছি tap. Hides the global NOW banner only, for the account holder who tapped; the stored urgency, the paper''s NOW block and WhatsApp delivery are unchanged.';
COMMENT ON COLUMN ${flyway:defaultSchema}.now_ack.family_id IS
    'the account holder who tapped: the viewer''s own family_account (a share member''s, for a shared patient), never the patient''s family';
COMMENT ON COLUMN ${flyway:defaultSchema}.now_ack.reasons_digest IS
    'sha256 hex of document.urgency_reasons::text as it stood when the banner was drawn (RAISE stores the reasons sorted and distinct, so the same reasons give the same digest)';
COMMENT ON CONSTRAINT now_ack_pkey ON ${flyway:defaultSchema}.now_ack IS
    'names exactly what was shown: the level, its reasons and the release state. A raise, a new reason or a release is a different key, so the banner shows again; a re-assessment that changes none of them (ExplanationService.detail, composeChecked, RecropHandler and the hourly sweep all restamp urgency_assessed_at) is the same key, so it does not';

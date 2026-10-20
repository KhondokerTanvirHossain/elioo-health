-- D3 (web upload, W4.2): the key a browser sends with one press of "পাঠান". A retry after a dropped connection, a double
-- tap or a browser resubmit sends the same key and gets the same document back: never a second document, a second free
-- slot or a second explanation. Scoped per family. NULL for WhatsApp and the JSON API, which have their own idempotency.
-- A column, not a table: it goes when the document goes, so both delete paths already cover it.
--
-- Ownership: V16, V18 and V20 already ALTERed baymax.document in production, so this role owns the table. Both
-- statements run in Flyway's one transaction: a failure leaves the schema at V20.
ALTER TABLE ${flyway:defaultSchema}.document ADD COLUMN upload_key varchar(80) NULL
    CONSTRAINT document_upload_key_check CHECK (upload_key ~ '^[A-Za-z0-9:-]{8,80}$');

-- The rule every writer meets (the V14 lesson): one row per family and key, whoever inserts it. Partial, so the many
-- rows with no key never conflict. PostgresDocumentRecordAdapter.createOnce names this index's predicate in its
-- ON CONFLICT clause.
CREATE UNIQUE INDEX document_upload_key_uq ON ${flyway:defaultSchema}.document (family_id, upload_key)
    WHERE upload_key IS NOT NULL;

COMMENT ON COLUMN ${flyway:defaultSchema}.document.upload_key IS
    'D3: one press of the web sheet''s send button; a retry with the same key returns the same document';

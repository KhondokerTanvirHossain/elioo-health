-- Ledger of objects Baymax keeps in object storage (BMX-3): one row per page image or crop.
-- Holds keys and sizes only. Image bytes never enter Postgres; there is deliberately no bytea column.
CREATE TABLE ${flyway:defaultSchema}.stored_object (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id     uuid NOT NULL,
    patient_id    uuid NOT NULL,
    document_id   uuid NOT NULL,
    kind          varchar(8) NOT NULL CHECK (kind IN ('page', 'crop')),
    page_no       integer NULL,             -- pages: 1-based page number
    item_id       varchar(64) NULL,         -- crops: id of the extracted item the crop proves
    storage_key   text NOT NULL UNIQUE,     -- {family_id}/{patient_id}/{document_id}/page-{n}.jpg | crop-{item_id}.jpg
    content_type  varchar(64) NOT NULL,
    size_bytes    bigint NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CHECK ((kind = 'page' AND page_no IS NOT NULL) OR (kind = 'crop' AND item_id IS NOT NULL))
);

CREATE INDEX stored_object_document_idx ON ${flyway:defaultSchema}.stored_object (document_id);
CREATE INDEX stored_object_patient_idx ON ${flyway:defaultSchema}.stored_object (patient_id);

COMMENT ON TABLE ${flyway:defaultSchema}.stored_object
    IS 'Object-storage ledger: keys and sizes only, never bytes. Bytes stored per document = SUM(size_bytes).';

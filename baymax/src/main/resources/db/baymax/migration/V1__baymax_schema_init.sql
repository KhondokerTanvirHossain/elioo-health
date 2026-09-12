-- Baymax owns this schema. It is created by the Baymax Flyway instance (createSchemas) and is
-- migrated independently of the medscribe schema; MedScribe tables are never touched from here.
-- This first migration only stamps the schema; tables arrive with the tickets that define them (BMX-1+).
COMMENT ON SCHEMA ${flyway:defaultSchema}
    IS 'Baymax: family health memory (MVP). Owned by the baymax module; migrated separately from medscribe.';

-- Family accounts, patient profiles and sibling sharing (BMX-4). FHIR-aligned naming, no external FHIR server (DR-1).
-- Phone numbers live only here; storage keys, log lines and ai_call_log carry UUIDs.

CREATE TABLE ${flyway:defaultSchema}.family_account (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    whatsapp_number   varchar(20) NOT NULL UNIQUE,          -- E.164, e.g. +8801XXXXXXXXX
    owner_name        varchar(120) NOT NULL,
    plan              varchar(16) NOT NULL DEFAULT 'free' CHECK (plan IN ('free', 'family')),
    terms_accepted_at timestamptz NOT NULL,                 -- server-side timestamp of the owner's consent; immutable
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ${flyway:defaultSchema}.patient_profile (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id         uuid NOT NULL REFERENCES ${flyway:defaultSchema}.family_account (id),
    name              varchar(120) NOT NULL,
    age               integer NOT NULL CHECK (age BETWEEN 0 AND 130),
    sex               varchar(8) NOT NULL CHECK (sex IN ('male', 'female', 'other', 'unknown')),  -- FHIR administrativeGender
    chronic_flags     text[] NOT NULL DEFAULT '{}',
    proxy_consent_at  timestamptz NOT NULL,                 -- "I am authorised to manage this person's records"; immutable
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX patient_profile_family_idx ON ${flyway:defaultSchema}.patient_profile (family_id);

CREATE TABLE ${flyway:defaultSchema}.share_member (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id        uuid NOT NULL REFERENCES ${flyway:defaultSchema}.patient_profile (id),
    whatsapp_number   varchar(20) NOT NULL,
    added_at          timestamptz NOT NULL DEFAULT now(),
    notified_at       timestamptz NULL,
    UNIQUE (patient_id, whatsapp_number)
);
CREATE INDEX share_member_patient_idx ON ${flyway:defaultSchema}.share_member (patient_id);

-- Consent timestamps are immutable after insert: no code path updates them, and the database refuses too.
-- One function per table: PL/pgSQL resolves NEW.<column> for the whole expression, so a shared function
-- would fail on the table that lacks the column.
CREATE FUNCTION ${flyway:defaultSchema}.forbid_terms_update() RETURNS trigger AS $$
BEGIN
    IF NEW.terms_accepted_at IS DISTINCT FROM OLD.terms_accepted_at THEN
        RAISE EXCEPTION 'terms_accepted_at is immutable';
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE FUNCTION ${flyway:defaultSchema}.forbid_proxy_consent_update() RETURNS trigger AS $$
BEGIN
    IF NEW.proxy_consent_at IS DISTINCT FROM OLD.proxy_consent_at THEN
        RAISE EXCEPTION 'proxy_consent_at is immutable';
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER family_account_consent_immutable
    BEFORE UPDATE ON ${flyway:defaultSchema}.family_account
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.forbid_terms_update();

CREATE TRIGGER patient_profile_consent_immutable
    BEFORE UPDATE ON ${flyway:defaultSchema}.patient_profile
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.forbid_proxy_consent_update();

COMMENT ON TABLE ${flyway:defaultSchema}.family_account IS 'One WhatsApp number = one family owner (the payer). Plan: free | family.';
COMMENT ON TABLE ${flyway:defaultSchema}.patient_profile IS 'The people whose records the family keeps; consent is by proxy from the owner.';
COMMENT ON TABLE ${flyway:defaultSchema}.share_member IS 'Extra numbers (siblings) that may view a patient; at most one per patient in v1, enforced in the service.';

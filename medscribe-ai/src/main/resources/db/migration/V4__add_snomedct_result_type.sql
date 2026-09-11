-- ============================================================================
-- V4: Add SNOMEDCT to medical_report_result result_type check constraint
-- ============================================================================
-- This migration adds 'SNOMEDCT' as a valid result_type for storing
-- SNOMED CT clinical terminology codes inferred from medical reports.
-- Also includes 'TRANSLATION' which was added in a previous release.
-- ============================================================================

-- Drop the existing check constraint
ALTER TABLE medical_report_result
DROP CONSTRAINT IF EXISTS medical_report_result_result_type_check;

-- Add the updated check constraint with SNOMEDCT and TRANSLATION included
ALTER TABLE medical_report_result
ADD CONSTRAINT medical_report_result_result_type_check
CHECK (result_type IN (
    'OCR',
    'TRANSLATION',
    'CLASSIFICATION',
    'ICD10',
    'RXNORM',
    'SNOMEDCT',
    'CLINICAL_INSIGHTS',
    'RISK_ASSESSMENT',
    'RECOMMENDATIONS',
    'EDUCATIONAL_CONTENT'
));

-- Add comment to document the change
COMMENT ON COLUMN medical_report_result.result_type IS
'Type of result stored: OCR, TRANSLATION, CLASSIFICATION, ICD10, RXNORM, SNOMEDCT, CLINICAL_INSIGHTS, RISK_ASSESSMENT, RECOMMENDATIONS, EDUCATIONAL_CONTENT';

-- ============================================================================
-- V5: Add SNOMEDCT_INFERENCE to medical_report_process_stage check constraint
-- ============================================================================
-- This migration adds 'SNOMEDCT_INFERENCE' as a valid stage for tracking
-- SNOMED CT clinical terminology code inference in the processing pipeline.
-- ============================================================================

-- Drop the existing check constraint
ALTER TABLE medical_report_process_stage
DROP CONSTRAINT IF EXISTS medical_report_process_stage_stage_check;

-- Add the updated check constraint with SNOMEDCT_INFERENCE included
ALTER TABLE medical_report_process_stage
ADD CONSTRAINT medical_report_process_stage_stage_check
CHECK (stage IN (
    'IMAGE_VALIDATION',
    'OCR_PROCESSING',
    'TRANSLATION',
    'ENTITY_DETECTION',
    'ICD10_INFERENCE',
    'RXNORM_INFERENCE',
    'SNOMEDCT_INFERENCE',
    'CLINICAL_INSIGHTS',
    'PATIENT_SUMMARY',
    'RISK_ASSESSMENT',
    'RECOMMENDATIONS',
    'EDUCATIONAL_CONTENT'
));

-- Add comment to document the change
COMMENT ON COLUMN medical_report_process_stage.stage IS
'Processing stage type: IMAGE_VALIDATION, OCR_PROCESSING, TRANSLATION, ENTITY_DETECTION, ICD10_INFERENCE, RXNORM_INFERENCE, SNOMEDCT_INFERENCE, CLINICAL_INSIGHTS, PATIENT_SUMMARY, RISK_ASSESSMENT, RECOMMENDATIONS, EDUCATIONAL_CONTENT';

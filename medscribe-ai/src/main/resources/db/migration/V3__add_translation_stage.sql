-- ============================================================================
-- Migration: V3__add_translation_stage
-- Description: Add TRANSLATION stage to medical_report_process_stage constraint
-- Author: System
-- Date: 2026-01-07
-- ============================================================================

-- Drop the existing constraint
ALTER TABLE medical_report_process_stage
DROP CONSTRAINT IF EXISTS medical_report_process_stage_stage_check;

-- Add the new constraint with TRANSLATION stage included
ALTER TABLE medical_report_process_stage
ADD CONSTRAINT medical_report_process_stage_stage_check
CHECK (stage IN (
    'IMAGE_VALIDATION',
    'OCR_PROCESSING',
    'TRANSLATION',           -- NEW: Stage 2.5 added
    'ENTITY_DETECTION',
    'ICD10_INFERENCE',
    'RXNORM_INFERENCE',
    'CLINICAL_INSIGHTS',
    'PATIENT_SUMMARY',
    'RISK_ASSESSMENT',
    'RECOMMENDATIONS',
    'EDUCATIONAL_CONTENT'
));

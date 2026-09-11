-- Migration V2: Fix confidence_score precision overflow
-- Date: 2025-12-04
-- Purpose: Increase confidence_score precision to handle values up to 99.9999
--
-- Root Cause: Previous DECIMAL(5,4) only allowed -9.9999 to 9.9999
-- New Type: DECIMAL(6,4) allows -99.9999 to 99.9999 (suitable for percentages)

-- Fix confidence_score in medical_report_process_stage
ALTER TABLE medical_report_process_stage
    ALTER COLUMN confidence_score TYPE DECIMAL(6,4);

-- Fix confidence_score in medical_report_result
ALTER TABLE medical_report_result
    ALTER COLUMN confidence_score TYPE DECIMAL(6,4);

-- Comments
COMMENT ON COLUMN medical_report_process_stage.confidence_score IS 'Confidence score (0-100 range) with 4 decimal precision';
COMMENT ON COLUMN medical_report_result.confidence_score IS 'Confidence score (0-100 range) with 4 decimal precision';

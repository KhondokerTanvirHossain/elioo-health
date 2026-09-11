-- ============================================================================
-- V6: Add translation cache columns to medical_report_result
-- ============================================================================
-- This migration adds columns to cache translated content in the result table.
-- Translations are stored alongside the original English content for efficient
-- retrieval without requiring re-translation on every request.
-- ============================================================================

-- Add translation cache columns to medical_report_result
ALTER TABLE medical_report_result
ADD COLUMN IF NOT EXISTS translated_data_json JSONB,
ADD COLUMN IF NOT EXISTS translated_language VARCHAR(5),
ADD COLUMN IF NOT EXISTS translated_at TIMESTAMP;

-- Create index for efficient translation lookups
-- This allows quick checking if a translation exists for a given report/type/language
CREATE INDEX IF NOT EXISTS idx_medical_report_result_translation
ON medical_report_result (report_id, result_type, translated_language)
WHERE translated_language IS NOT NULL;

-- Add comments for documentation
COMMENT ON COLUMN medical_report_result.translated_data_json IS
'Cached translation of result_data_json. Language specified in translated_language column. NULL if not yet translated.';

COMMENT ON COLUMN medical_report_result.translated_language IS
'ISO 639-1 language code of the cached translation (e.g., bn for Bangla). NULL if no translation cached.';

COMMENT ON COLUMN medical_report_result.translated_at IS
'Timestamp when the translation was created. Used for cache invalidation if needed.';

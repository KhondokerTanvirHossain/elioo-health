-- Medical Report Processing Database Schema
-- Version: 1.0
-- Date: 2025-12-02
-- Purpose: Track complete medical report processing workflow with audit trail

-- ============================================================================
-- Table 1: medical_report_process
-- Purpose: Track overall report processing lifecycle (1 record per request)
-- ============================================================================
CREATE TABLE IF NOT EXISTS medical_report_process (
    report_id VARCHAR(50) PRIMARY KEY,
    patient_id VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'PARTIAL_SUCCESS')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    processing_time_ms BIGINT,

    -- Request data
    image_base64 TEXT,
    patient_context_json JSONB,
    workflow_options_json JSONB,

    -- Summary counters
    completed_stages INTEGER DEFAULT 0,
    failed_stages INTEGER DEFAULT 0,
    total_stages INTEGER DEFAULT 10,

    -- Metadata
    created_by VARCHAR(100),
    error_message TEXT
);

-- Indexes for medical_report_process
CREATE INDEX IF NOT EXISTS idx_process_patient_id ON medical_report_process(patient_id);
CREATE INDEX IF NOT EXISTS idx_process_status ON medical_report_process(status);
CREATE INDEX IF NOT EXISTS idx_process_created_at ON medical_report_process(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_process_patient_context ON medical_report_process USING GIN (patient_context_json);

-- Comments for medical_report_process
COMMENT ON TABLE medical_report_process IS 'Tracks overall medical report processing workflow';
COMMENT ON COLUMN medical_report_process.report_id IS 'Unique report identifier (e.g., RPT-ABC12345)';
COMMENT ON COLUMN medical_report_process.patient_id IS 'Patient identifier from request';
COMMENT ON COLUMN medical_report_process.status IS 'Processing status: PENDING, IN_PROGRESS, COMPLETED, FAILED, PARTIAL_SUCCESS';
COMMENT ON COLUMN medical_report_process.patient_context_json IS 'Patient demographics, history, medications as JSONB';
COMMENT ON COLUMN medical_report_process.workflow_options_json IS 'Workflow configuration as JSONB';

-- ============================================================================
-- Table 2: medical_report_process_stage
-- Purpose: Track each processing stage (10 stages per report)
-- ============================================================================
CREATE TABLE IF NOT EXISTS medical_report_process_stage (
    id VARCHAR(50) PRIMARY KEY,
    report_id VARCHAR(50) NOT NULL,
    stage VARCHAR(50) NOT NULL CHECK (stage IN (
        'IMAGE_VALIDATION',
        'OCR_PROCESSING',
        'ENTITY_DETECTION',
        'ICD10_INFERENCE',
        'RXNORM_INFERENCE',
        'CLINICAL_INSIGHTS',
        'PATIENT_SUMMARY',
        'RISK_ASSESSMENT',
        'RECOMMENDATIONS',
        'EDUCATIONAL_CONTENT'
    )),
    status VARCHAR(30) NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'SKIPPED')),
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    duration_ms BIGINT,

    -- Stage data
    input_data_json JSONB,
    output_data_json JSONB,
    error_message TEXT,
    is_retryable BOOLEAN DEFAULT FALSE,
    attempt_number INTEGER DEFAULT 1,

    -- Quality metrics
    confidence_score DECIMAL(5,4),
    quality_metrics_json JSONB,

    -- Foreign key constraint
    CONSTRAINT fk_stage_report
        FOREIGN KEY (report_id)
        REFERENCES medical_report_process(report_id)
        ON DELETE CASCADE
);

-- Indexes for medical_report_process_stage
CREATE INDEX IF NOT EXISTS idx_stage_report_id ON medical_report_process_stage(report_id);
CREATE INDEX IF NOT EXISTS idx_stage_stage ON medical_report_process_stage(stage);
CREATE INDEX IF NOT EXISTS idx_stage_status ON medical_report_process_stage(status);
CREATE INDEX IF NOT EXISTS idx_stage_started_at ON medical_report_process_stage(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_stage_output_data ON medical_report_process_stage USING GIN (output_data_json);

-- Comments for medical_report_process_stage
COMMENT ON TABLE medical_report_process_stage IS 'Tracks individual processing stages (10 per report)';
COMMENT ON COLUMN medical_report_process_stage.stage IS 'Stage type: IMAGE_VALIDATION, OCR_PROCESSING, etc.';
COMMENT ON COLUMN medical_report_process_stage.output_data_json IS 'Stage results as JSONB for flexible querying';
COMMENT ON COLUMN medical_report_process_stage.is_retryable IS 'Whether error is transient and can be retried';

-- ============================================================================
-- Table 3: medical_report_error
-- Purpose: Track all processing errors for debugging and analytics
-- ============================================================================
CREATE TABLE IF NOT EXISTS medical_report_error (
    id VARCHAR(50) PRIMARY KEY,
    report_id VARCHAR(50) NOT NULL,
    stage_id VARCHAR(50),
    stage VARCHAR(50),
    error_code VARCHAR(100) NOT NULL,
    error_message TEXT NOT NULL,
    stack_trace TEXT,
    is_retryable BOOLEAN DEFAULT FALSE,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    severity VARCHAR(20) NOT NULL CHECK (severity IN ('ERROR', 'WARNING', 'INFO')),

    -- Foreign key constraints
    CONSTRAINT fk_error_report
        FOREIGN KEY (report_id)
        REFERENCES medical_report_process(report_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_error_stage
        FOREIGN KEY (stage_id)
        REFERENCES medical_report_process_stage(id)
        ON DELETE SET NULL
);

-- Indexes for medical_report_error
CREATE INDEX IF NOT EXISTS idx_error_report_id ON medical_report_error(report_id);
CREATE INDEX IF NOT EXISTS idx_error_code ON medical_report_error(error_code);
CREATE INDEX IF NOT EXISTS idx_error_occurred_at ON medical_report_error(occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_error_severity ON medical_report_error(severity);

-- Comments for medical_report_error
COMMENT ON TABLE medical_report_error IS 'Tracks all processing errors with full details';
COMMENT ON COLUMN medical_report_error.error_code IS 'Error code: OCR_PROCESSING_FAILED, RATE_LIMIT_EXCEEDED, etc.';
COMMENT ON COLUMN medical_report_error.is_retryable IS 'Whether error is transient (network, rate limit, timeout)';
COMMENT ON COLUMN medical_report_error.severity IS 'Error severity: ERROR (critical), WARNING, INFO';

-- ============================================================================
-- Table 4: medical_report_result
-- Purpose: Store all processing results with extracted fields for fast queries
-- ============================================================================
CREATE TABLE IF NOT EXISTS medical_report_result (
    id VARCHAR(50) PRIMARY KEY,
    report_id VARCHAR(50) NOT NULL,
    result_type VARCHAR(50) NOT NULL CHECK (result_type IN (
        'OCR',
        'CLASSIFICATION',
        'ICD10',
        'RXNORM',
        'CLINICAL_INSIGHTS',
        'RISK_ASSESSMENT',
        'RECOMMENDATIONS',
        'EDUCATIONAL_CONTENT'
    )),
    result_data_json JSONB NOT NULL,
    confidence_score DECIMAL(5,4),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Extracted fields for quick queries (denormalized)
    test_count INTEGER,
    entity_count INTEGER,
    code_count INTEGER,
    risk_level VARCHAR(20) CHECK (risk_level IN ('LOW', 'MODERATE', 'HIGH', 'CRITICAL')),

    -- Foreign key constraint
    CONSTRAINT fk_result_report
        FOREIGN KEY (report_id)
        REFERENCES medical_report_process(report_id)
        ON DELETE CASCADE
);

-- Indexes for medical_report_result
CREATE INDEX IF NOT EXISTS idx_result_report_id ON medical_report_result(report_id);
CREATE INDEX IF NOT EXISTS idx_result_type ON medical_report_result(result_type);
CREATE INDEX IF NOT EXISTS idx_result_created_at ON medical_report_result(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_result_risk_level ON medical_report_result(risk_level);
CREATE INDEX IF NOT EXISTS idx_result_data ON medical_report_result USING GIN (result_data_json);

-- Comments for medical_report_result
COMMENT ON TABLE medical_report_result IS 'Stores all processing results with JSONB data';
COMMENT ON COLUMN medical_report_result.result_type IS 'Result type: OCR, CLASSIFICATION, ICD10, RXNORM, etc.';
COMMENT ON COLUMN medical_report_result.result_data_json IS 'Complete result data as JSONB for flexible querying';
COMMENT ON COLUMN medical_report_result.test_count IS 'Number of tests (OCR results) - extracted for fast filtering';
COMMENT ON COLUMN medical_report_result.risk_level IS 'Risk level (RISK_ASSESSMENT results) - extracted for fast filtering';

-- ============================================================================
-- Triggers for automatic updated_at timestamp
-- ============================================================================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_medical_report_process_updated_at
    BEFORE UPDATE ON medical_report_process
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- Sample queries for verification
-- ============================================================================

-- Query 1: Get all reports for a patient
-- SELECT * FROM medical_report_process WHERE patient_id = 'P12345' ORDER BY created_at DESC;

-- Query 2: Get all stages for a report
-- SELECT stage, status, duration_ms, confidence_score
-- FROM medical_report_process_stage
-- WHERE report_id = 'RPT-ABC123'
-- ORDER BY started_at;

-- Query 3: Find slow processing stages
-- SELECT stage, AVG(duration_ms) as avg_duration_ms, COUNT(*) as count
-- FROM medical_report_process_stage
-- WHERE status = 'COMPLETED' AND started_at > NOW() - INTERVAL '7 days'
-- GROUP BY stage
-- ORDER BY avg_duration_ms DESC;

-- Query 4: Find error patterns
-- SELECT error_code, stage, COUNT(*) as count
-- FROM medical_report_error
-- WHERE occurred_at > NOW() - INTERVAL '7 days'
-- GROUP BY error_code, stage
-- ORDER BY count DESC;

-- Query 5: Get high-risk reports
-- SELECT r.report_id, r.patient_id, res.risk_level, res.created_at
-- FROM medical_report_process r
-- JOIN medical_report_result res ON r.report_id = res.report_id
-- WHERE res.result_type = 'RISK_ASSESSMENT'
--   AND res.risk_level IN ('HIGH', 'CRITICAL')
-- ORDER BY res.created_at DESC
-- LIMIT 20;

-- ============================================================================
-- Database verification script
-- ============================================================================

-- Verify all tables created
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'medscribe'
  AND table_name LIKE 'medical_report%'
ORDER BY table_name;

-- Verify all indexes created
SELECT indexname, tablename
FROM pg_indexes
WHERE schemaname = 'medscribe'
  AND tablename LIKE 'medical_report%'
ORDER BY tablename, indexname;

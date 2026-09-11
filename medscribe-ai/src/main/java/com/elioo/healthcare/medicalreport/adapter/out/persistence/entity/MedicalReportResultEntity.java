package com.elioo.healthcare.medicalreport.adapter.out.persistence.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Entity for storing processing results.
 *
 * <p>Architecture: Persistence Entity in Hexagonal Architecture</p>
 * <ul>
 *   <li>Maps to medical_report_result table in PostgreSQL</li>
 *   <li>Multiple records per report (one per result type)</li>
 *   <li>Stores complete result data as JSONB for flexibility</li>
 *   <li>Extracted fields for quick queries without parsing JSON</li>
 * </ul>
 *
 * <p>Result Types:</p>
 * <ul>
 *   <li>OCR - Extracted test results from image</li>
 *   <li>CLASSIFICATION - Medical entities and their types</li>
 *   <li>ICD10 - Diagnosis codes (ICD-10-CM)</li>
 *   <li>RXNORM - Medication codes (RxNorm)</li>
 *   <li>CLINICAL_INSIGHTS - AI-generated clinical insights</li>
 *   <li>RISK_ASSESSMENT - Multi-category risk evaluation</li>
 *   <li>RECOMMENDATIONS - Evidence-based recommendations</li>
 *   <li>EDUCATIONAL_CONTENT - Patient education materials</li>
 * </ul>
 *
 * <p>Query Optimization:</p>
 * <ul>
 *   <li>JSONB fields allow complex queries with PostgreSQL operators</li>
 *   <li>Extracted fields (test_count, risk_level) enable fast filtering</li>
 *   <li>GIN indexes on JSONB columns for efficient searches</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("medical_report_result")
public class MedicalReportResultEntity {

    /**
     * Unique identifier for this result record.
     * Generated UUID.
     */
    @Id
    @Column("id")
    private String id;

    /**
     * Reference to the report process that produced this result.
     * Foreign key to medical_report_process.report_id.
     */
    @Column("report_id")
    private String reportId;

    /**
     * Type of result stored in this record.
     * One of: OCR, CLASSIFICATION, ICD10, RXNORM, CLINICAL_INSIGHTS, etc.
     */
    @Column("result_type")
    private String resultType;

    /**
     * Complete result data as JSON string.
     * Stored as JSONB in PostgreSQL for efficient querying.
     * Structure varies by result type.
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>OCR: Array of test results with values, units, ranges</li>
     *   <li>ICD10: Array of diagnosis codes with descriptions</li>
     *   <li>CLINICAL_INSIGHTS: Object with summary, findings, risks</li>
     * </ul>
     */
    @Column("result_data_json")
    private String resultDataJson;

    /**
     * Overall confidence score for this result (0.0 to 1.0).
     * Average or aggregate confidence across all items in result.
     */
    @Column("confidence_score")
    private Double confidenceScore;

    /**
     * Timestamp when result was saved.
     */
    @Column("created_at")
    private LocalDateTime createdAt;

    // ========================================
    // Extracted fields for quick queries
    // (denormalized from JSON for performance)
    // ========================================

    /**
     * Number of tests extracted (for OCR results).
     * Extracted from result_data_json for quick filtering.
     * Example: 9 tests found in blood test report.
     */
    @Column("test_count")
    private Integer testCount;

    /**
     * Number of medical entities detected (for CLASSIFICATION results).
     * Extracted from result_data_json for quick filtering.
     * Example: 42 entities (medications, conditions, anatomy).
     */
    @Column("entity_count")
    private Integer entityCount;

    /**
     * Number of medical codes mapped (for ICD10/RXNORM results).
     * Extracted from result_data_json for quick filtering.
     * Example: 3 ICD-10 codes, 2 RxNorm codes.
     */
    @Column("code_count")
    private Integer codeCount;

    /**
     * Risk level from risk assessment (for RISK_ASSESSMENT results).
     * One of: LOW, MODERATE, HIGH, CRITICAL
     * Extracted from result_data_json for quick filtering.
     * Used to query high-risk reports quickly.
     */
    @Column("risk_level")
    private String riskLevel;

    // ========================================
    // Translation cache fields
    // (for multilingual support)
    // ========================================

    /**
     * Cached translation of result_data_json.
     * Stored as JSONB for efficient querying.
     * NULL if content has not been translated yet.
     */
    @Column("translated_data_json")
    private String translatedDataJson;

    /**
     * ISO 639-1 language code of the cached translation.
     * Examples: "bn" for Bangla, "hi" for Hindi.
     * NULL if no translation is cached.
     */
    @Column("translated_language")
    private String translatedLanguage;

    /**
     * Timestamp when the translation was created.
     * Used for cache invalidation if needed.
     */
    @Column("translated_at")
    private LocalDateTime translatedAt;

    /**
     * Check if this result has a cached translation.
     */
    public boolean hasTranslation() {
        return translatedDataJson != null && translatedLanguage != null;
    }

    /**
     * Check if this result has a translation for a specific language.
     */
    public boolean hasTranslationFor(String languageCode) {
        return languageCode != null &&
               languageCode.equalsIgnoreCase(translatedLanguage) &&
               translatedDataJson != null;
    }

    /**
     * Check if this result has high confidence.
     */
    public boolean isHighConfidence() {
        return confidenceScore != null && confidenceScore >= 0.8;
    }

    /**
     * Check if this result indicates high risk.
     */
    public boolean isHighRisk() {
        return "HIGH".equals(riskLevel) || "CRITICAL".equals(riskLevel);
    }

    /**
     * Get result type category.
     */
    public String getResultCategory() {
        if (resultType == null) {
            return "UNKNOWN";
        }
        return switch (resultType) {
            case "OCR" -> "EXTRACTION";
            case "CLASSIFICATION", "ICD10", "RXNORM" -> "CODING";
            case "CLINICAL_INSIGHTS", "RISK_ASSESSMENT", "RECOMMENDATIONS" -> "ANALYSIS";
            case "EDUCATIONAL_CONTENT" -> "EDUCATION";
            default -> "OTHER";
        };
    }
}

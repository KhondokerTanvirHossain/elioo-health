package com.elioo.healthcare.medicalreport.adapter.out.persistence.repository;

import com.elioo.healthcare.medicalreport.adapter.out.persistence.entity.MedicalReportResultEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * R2DBC repository for medical report results storage.
 *
 * <p>Architecture: Repository in Hexagonal Architecture (Outbound Adapter)</p>
 * <ul>
 *   <li>Reactive database access using R2DBC</li>
 *   <li>Stores processing results as JSONB for flexibility</li>
 *   <li>Extracted fields enable fast filtering without JSON parsing</li>
 * </ul>
 *
 * <p>Result Types:</p>
 * <ul>
 *   <li>OCR - Extracted test results</li>
 *   <li>CLASSIFICATION - Medical entities</li>
 *   <li>ICD10 - Diagnosis codes</li>
 *   <li>RXNORM - Medication codes</li>
 *   <li>CLINICAL_INSIGHTS - AI analysis</li>
 *   <li>RISK_ASSESSMENT - Risk evaluation</li>
 *   <li>RECOMMENDATIONS - Clinical recommendations</li>
 *   <li>EDUCATIONAL_CONTENT - Patient education</li>
 * </ul>
 */
@Repository
public interface MedicalReportResultRepository extends R2dbcRepository<MedicalReportResultEntity, String> {

    /**
     * Find all results for a specific report.
     *
     * @param reportId Report identifier
     * @return Flux of results ordered by creation time
     */
    @Query("SELECT * FROM medical_report_result " +
           "WHERE report_id = :reportId " +
           "ORDER BY created_at ASC")
    Flux<MedicalReportResultEntity> findByReportIdOrderByCreatedAt(String reportId);

    /**
     * Find result by report and type.
     *
     * @param reportId Report identifier
     * @param resultType Result type (OCR, CLASSIFICATION, etc.)
     * @return Mono of the result
     */
    Mono<MedicalReportResultEntity> findByReportIdAndResultType(String reportId, String resultType);

    /**
     * Find results by type.
     *
     * @param resultType Result type
     * @return Flux of results of that type
     */
    Flux<MedicalReportResultEntity> findByResultType(String resultType);

    /**
     * Find high-risk reports.
     *
     * @param riskLevel Risk level (HIGH or CRITICAL)
     * @return Flux of high-risk results
     */
    Flux<MedicalReportResultEntity> findByRiskLevel(String riskLevel);

    /**
     * Find results with high confidence.
     *
     * @param minConfidence Minimum confidence score (e.g., 0.9)
     * @return Flux of high-confidence results
     */
    @Query("SELECT * FROM medical_report_result " +
           "WHERE confidence_score >= :minConfidence " +
           "ORDER BY confidence_score DESC")
    Flux<MedicalReportResultEntity> findHighConfidenceResults(Double minConfidence);

    /**
     * Find results with low confidence (need review).
     *
     * @param maxConfidence Maximum confidence score (e.g., 0.7)
     * @return Flux of low-confidence results
     */
    @Query("SELECT * FROM medical_report_result " +
           "WHERE confidence_score < :maxConfidence " +
           "ORDER BY confidence_score ASC")
    Flux<MedicalReportResultEntity> findLowConfidenceResults(Double maxConfidence);

    /**
     * Find OCR results with high test count.
     *
     * @param minTestCount Minimum number of tests
     * @return Flux of OCR results
     */
    @Query("SELECT * FROM medical_report_result " +
           "WHERE result_type = 'OCR' AND test_count >= :minTestCount " +
           "ORDER BY test_count DESC")
    Flux<MedicalReportResultEntity> findOcrResultsWithManyTests(Integer minTestCount);

    /**
     * Find classification results with high entity count.
     *
     * @param minEntityCount Minimum number of entities
     * @return Flux of classification results
     */
    @Query("SELECT * FROM medical_report_result " +
           "WHERE result_type = 'CLASSIFICATION' AND entity_count >= :minEntityCount " +
           "ORDER BY entity_count DESC")
    Flux<MedicalReportResultEntity> findClassificationResultsWithManyEntities(Integer minEntityCount);

    /**
     * Find all high-risk or critical reports.
     *
     * @return Flux of high-risk reports
     */
    @Query("SELECT * FROM medical_report_result " +
           "WHERE result_type = 'RISK_ASSESSMENT' " +
           "AND risk_level IN ('HIGH', 'CRITICAL') " +
           "ORDER BY created_at DESC")
    Flux<MedicalReportResultEntity> findHighRiskReports();

    /**
     * Calculate average confidence score by result type.
     *
     * @param resultType Result type
     * @param since Timestamp to calculate from
     * @return Mono of average confidence
     */
    @Query("SELECT AVG(confidence_score) FROM medical_report_result " +
           "WHERE result_type = :resultType AND created_at > :since")
    Mono<Double> calculateAverageConfidence(String resultType, LocalDateTime since);

    /**
     * Get result statistics for analytics.
     *
     * @param since Timestamp to analyze from
     * @return Flux of result statistics
     */
    @Query("SELECT " +
           "  result_type, " +
           "  COUNT(*) as total_count, " +
           "  AVG(confidence_score) as avg_confidence, " +
           "  MIN(confidence_score) as min_confidence, " +
           "  MAX(confidence_score) as max_confidence " +
           "FROM medical_report_result " +
           "WHERE created_at > :since " +
           "GROUP BY result_type " +
           "ORDER BY total_count DESC")
    Flux<ResultStatistics> getResultStatistics(LocalDateTime since);

    /**
     * Count results by type and risk level.
     *
     * @return Flux of risk level distribution
     */
    @Query("SELECT risk_level, COUNT(*) as count " +
           "FROM medical_report_result " +
           "WHERE result_type = 'RISK_ASSESSMENT' AND risk_level IS NOT NULL " +
           "GROUP BY risk_level " +
           "ORDER BY CASE risk_level " +
           "  WHEN 'CRITICAL' THEN 1 " +
           "  WHEN 'HIGH' THEN 2 " +
           "  WHEN 'MODERATE' THEN 3 " +
           "  WHEN 'LOW' THEN 4 " +
           "END")
    Flux<RiskLevelCount> getRiskLevelDistribution();

    /**
     * Find results created in a date range.
     *
     * @param start Start timestamp
     * @param end End timestamp
     * @return Flux of results in range
     */
    Flux<MedicalReportResultEntity> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    /**
     * Custom insert with explicit JSONB casting.
     * Required because Spring Data R2DBC doesn't automatically cast String to JSONB.
     *
     * @param id Result ID
     * @param reportId Report ID
     * @param resultType Result type
     * @param resultDataJson Result data as JSON string
     * @param confidenceScore Confidence score
     * @param createdAt Creation timestamp
     * @param testCount Test count
     * @param entityCount Entity count
     * @param codeCount Code count
     * @param riskLevel Risk level
     * @return Mono of number of rows inserted
     */
    @Query("""
            INSERT INTO medical_report_result (
                id, report_id, result_type, result_data_json, confidence_score,
                created_at, test_count, entity_count, code_count, risk_level
            ) VALUES (
                :id, :reportId, :resultType, :resultDataJson::jsonb, :confidenceScore,
                :createdAt, :testCount, :entityCount, :codeCount, :riskLevel
            )
            """)
    Mono<Integer> insertWithJsonbCast(
            String id, String reportId, String resultType, String resultDataJson,
            Double confidenceScore, LocalDateTime createdAt, Integer testCount,
            Integer entityCount, Integer codeCount, String riskLevel
    );

    /**
     * DTO for result statistics query result.
     */
    record ResultStatistics(
            String resultType,
            Long totalCount,
            Double avgConfidence,
            Double minConfidence,
            Double maxConfidence
    ) {}

    /**
     * DTO for risk level distribution query result.
     */
    record RiskLevelCount(
            String riskLevel,
            Long count
    ) {}
}

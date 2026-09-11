package com.elioo.healthcare.medicalreport.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO returned when medical report processing is initiated.
 *
 * <p>This response is returned immediately (HTTP 202 Accepted) when the master
 * orchestration API receives a processing request. The actual processing continues
 * in the background, and the client can poll the status endpoints to check progress.
 *
 * <p><b>Usage:</b>
 * <pre>
 * POST /api/v1/medical-report/process
 * → Returns ProcessingInitiatedResponse (< 1 second)
 * → Client polls GET /api/v1/medical-report/query/status/{reportId}/detailed
 * </pre>
 *
 * <p><b>Response Example:</b>
 * <pre>
 * {
 *   "reportId": "RPT-20250312-ABC123",
 *   "status": "PENDING",
 *   "statusUrl": "/api/v1/medical-report/query/status/RPT-20250312-ABC123",
 *   "detailedStatusUrl": "/api/v1/medical-report/query/status/RPT-20250312-ABC123/detailed",
 *   "acceptedAt": "2025-03-12T10:30:00",
 *   "estimatedCompletionTime": "2025-03-12T10:40:00"
 * }
 * </pre>
 *
 * @see com.elioo.healthcare.medicalreport.application.service.MedicalReportOrchestrationService#initiateProcessing
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessingInitiatedResponse {

    /**
     * Unique report identifier generated for this processing request.
     * Use this ID to poll for status updates and retrieve results.
     */
    private String reportId;

    /**
     * Current processing status (typically "PENDING" or "IN_PROGRESS" at this stage).
     * Possible values: PENDING, IN_PROGRESS, COMPLETED, FAILED, PARTIAL_SUCCESS, VALIDATION_FAILED, TIMEOUT
     */
    private String status;

    /**
     * URL to poll for basic processing status.
     * Returns summary information about the current processing state.
     */
    private String statusUrl;

    /**
     * URL to poll for detailed processing status with partial results.
     * Returns full details including completed stages, failed stages, and partial results.
     * Recommended for frontend progress display.
     */
    private String detailedStatusUrl;

    /**
     * Timestamp when the processing request was accepted.
     */
    private LocalDateTime acceptedAt;

    /**
     * Estimated time when processing is expected to complete.
     * Typically 10 minutes from acceptance for medical report workflows.
     * Note: This is an estimate - actual completion time may vary.
     */
    private LocalDateTime estimatedCompletionTime;
}

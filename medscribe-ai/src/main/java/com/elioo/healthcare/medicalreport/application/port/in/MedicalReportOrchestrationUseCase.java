package com.elioo.healthcare.medicalreport.application.port.in;

import com.elioo.healthcare.medicalreport.domain.MasterProcessingRequest;
import com.elioo.healthcare.medicalreport.domain.MasterProcessingResponse;
import com.elioo.healthcare.medicalreport.domain.ProcessingStage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Inbound port (use case) for master medical report orchestration.
 * This interface defines the contract for processing complete medical reports.
 *
 * <p>Architecture: Driving Port in Hexagonal Architecture</p>
 * <ul>
 *   <li>Defines what the application can do (business capabilities)</li>
 *   <li>Independent of infrastructure (web, database, AWS)</li>
 *   <li>Web adapters (handlers) depend on this interface</li>
 *   <li>Service implementation provides the business logic</li>
 * </ul>
 *
 * <p>This is the primary entry point for complete medical report processing.</p>
 */
public interface MedicalReportOrchestrationUseCase {

    /**
     * Initiate asynchronous processing of a medical report.
     * Returns report ID immediately (< 1 second) and processing continues in background.
     *
     * <p><b>Async Processing Flow:</b></p>
     * <ol>
     *   <li>Quick image validation (< 500ms)</li>
     *   <li>Create process record in database with PENDING status</li>
     *   <li>Return report ID immediately</li>
     *   <li>Background processing executes full 10-stage workflow (up to 10 minutes)</li>
     *   <li>Client polls status endpoints for progress updates</li>
     * </ol>
     *
     * <p><b>Status Tracking:</b></p>
     * <ul>
     *   <li>Each stage result is saved to database as it completes</li>
     *   <li>Use query APIs to poll for status and partial results</li>
     *   <li>GET /api/v1/medical-report/query/status/{reportId} - Basic status</li>
     *   <li>GET /api/v1/medical-report/query/status/{reportId}/detailed - Detailed status with partial results</li>
     * </ul>
     *
     * <p><b>Error Handling:</b></p>
     * <ul>
     *   <li>Validation errors return immediately (before background processing starts)</li>
     *   <li>Background processing errors are saved to database and returned via status APIs</li>
     *   <li>Failed stages are tracked in process status with error details</li>
     * </ul>
     *
     * @param request Master processing request with image and patient context
     * @return Mono containing report ID for status polling
     * @throws IllegalArgumentException if image validation fails
     */
    Mono<String> initiateProcessing(MasterProcessingRequest request);

    /**
     * Process a complete medical report through the 10-step workflow.
     * Returns a comprehensive response containing all analysis results.
     *
     * <p>Workflow Stages:</p>
     * <ol>
     *   <li>Image Validation - Check quality, format, size</li>
     *   <li>OCR Processing - Extract text and structured data</li>
     *   <li>Entity Detection - Identify medical entities</li>
     *   <li>ICD-10 Inference - Map to diagnosis codes</li>
     *   <li>RxNorm Inference - Map to medication codes</li>
     *   <li>Clinical Insights - AI-powered analysis</li>
     *   <li>Patient Summary - Generate summary</li>
     *   <li>Risk Assessment - Evaluate clinical risk</li>
     *   <li>Recommendations - Provide evidence-based suggestions</li>
     *   <li>Educational Content - Generate patient education</li>
     * </ol>
     *
     * <p>Error Handling:</p>
     * <ul>
     *   <li>Critical stage failures (validation, OCR, entity detection) cause complete failure</li>
     *   <li>Non-critical stage failures (codes, insights) result in partial success</li>
     *   <li>Response includes errors and warnings for all issues</li>
     * </ul>
     *
     * @param request Master processing request with image and patient context
     * @return Mono containing complete processing results
     * @throws com.elioo.healthcare.medicalreport.domain.exception.OrchestrationException
     *         if a critical stage fails
     */
    Mono<MasterProcessingResponse> processCompleteMedicalReport(MasterProcessingRequest request);

    /**
     * Process a medical report with real-time progress updates.
     * Emits progress events as each workflow stage completes.
     *
     * <p>Use this method for long-running processing where the client needs
     * to show progress to the user (e.g., in a UI with progress bar).</p>
     *
     * <p>Implementation Note: Uses Server-Sent Events (SSE) pattern.
     * The Flux emits a ProcessingProgress event after each stage completion.</p>
     *
     * <p>Example Usage:</p>
     * <pre>{@code
     * orchestrationUseCase.processWithProgressUpdates(request)
     *     .subscribe(progress -> {
     *         System.out.printf("Stage: %s, Progress: %.1f%%\n",
     *             progress.currentStage(),
     *             progress.progressPercentage());
     *     });
     * }</pre>
     *
     * @param request Master processing request with image and patient context
     * @return Flux of processing progress events
     */
    Flux<ProcessingProgress> processWithProgressUpdates(MasterProcessingRequest request);

    /**
     * Initiate asynchronous processing of multiple medical report images.
     * Returns report ID immediately (< 1 second) and processing continues in background.
     *
     * <p><b>Async Processing Flow:</b></p>
     * <ol>
     *   <li>Quick image validation (< 500ms)</li>
     *   <li>Create process record in database with PENDING status</li>
     *   <li>Return report ID immediately</li>
     *   <li>Background processing executes multi-image workflow (up to 15 minutes)</li>
     *   <li>Client polls status endpoints for progress updates</li>
     * </ol>
     *
     * <p><b>Multi-Image Workflow (Background):</b></p>
     * <ol>
     *   <li>Validate all images in parallel</li>
     *   <li>OCR all images in parallel</li>
     *   <li>Concatenate raw text from all OCR results</li>
     *   <li>Validate total text length (AWS Comprehend Medical limit: 20,000 chars)</li>
     *   <li>Merge test results using confidence-based deduplication</li>
     *   <li>Run classification on concatenated text</li>
     *   <li>Generate clinical insights on merged results</li>
     * </ol>
     *
     * <p><b>Status Tracking:</b></p>
     * <ul>
     *   <li>Each stage result is saved to database as it completes</li>
     *   <li>Use query APIs to poll for status and partial results</li>
     *   <li>GET /api/v1/medical-report/query/status/{reportId} - Basic status</li>
     *   <li>GET /api/v1/medical-report/query/status/{reportId}/detailed - Detailed status with partial results</li>
     * </ul>
     *
     * <p><b>Error Handling:</b></p>
     * <ul>
     *   <li>Validation errors return immediately (before background processing starts)</li>
     *   <li>Background processing errors are saved to database and returned via status APIs</li>
     *   <li>Failed stages are tracked in process status with error details</li>
     *   <li>If ALL images fail: workflow fails completely</li>
     *   <li>If SOME images fail: continues with partial results, includes warnings in response</li>
     * </ul>
     *
     * <p><b>Deduplication Strategy:</b></p>
     * <ul>
     *   <li>When same test appears in multiple images, keeps result with highest confidence score</li>
     *   <li>Test names are normalized (case-insensitive) for duplicate detection</li>
     * </ul>
     *
     * @param multiImageRequest Request with multiple images and patient context
     * @return Mono containing report ID for status polling
     * @throws IllegalArgumentException if image validation fails
     */
    Mono<String> initiateMultiImageProcessing(
            com.elioo.healthcare.medicalreport.dto.MultiImageRequest multiImageRequest
    );

    /**
     * Process multiple medical report images as a single cohesive analysis (Internal/Synchronous).
     *
     * <p><b>INTERNAL USE ONLY</b> - This method is used by the async background processor.
     * External callers should use {@link #initiateMultiImageProcessing} instead.</p>
     *
     * <p>This method handles multi-page reports or multiple test reports from the same visit.
     * Images are processed in parallel for maximum throughput, then results are merged using
     * confidence-based deduplication.</p>
     *
     * <p>Workflow:</p>
     * <ol>
     *   <li>Validate all images in parallel</li>
     *   <li>OCR all images in parallel</li>
     *   <li>Concatenate raw text from all OCR results</li>
     *   <li>Validate total text length (AWS Comprehend Medical limit: 20,000 chars)</li>
     *   <li>Merge test results using confidence-based deduplication</li>
     *   <li>Run classification on concatenated text</li>
     *   <li>Generate clinical insights on merged results</li>
     * </ol>
     *
     * <p>Error Handling:</p>
     * <ul>
     *   <li>If ALL images fail validation or OCR: workflow fails completely</li>
     *   <li>If SOME images fail: continues with partial results, includes warnings in response</li>
     *   <li>If concatenated text exceeds 20,000 chars: fails fast with clear error message</li>
     * </ul>
     *
     * <p>Deduplication Strategy:</p>
     * <ul>
     *   <li>When same test appears in multiple images, keeps result with highest confidence score</li>
     *   <li>Test names are normalized (case-insensitive) for duplicate detection</li>
     * </ul>
     *
     * @param multiImageRequest Request with multiple images and patient context
     * @return Mono containing aggregated processing response
     * @throws com.elioo.healthcare.medicalreport.domain.exception.OrchestrationException
     *         if all images fail or text limit exceeded
     */
    Mono<MasterProcessingResponse> processMultipleImages(
            com.elioo.healthcare.medicalreport.dto.MultiImageRequest multiImageRequest
    );

    /**
     * Progress event emitted during processing.
     * Contains information about current stage and overall progress.
     *
     * @param reportId Unique report identifier
     * @param currentStage Currently executing stage
     * @param completedStages Number of stages completed so far
     * @param totalStages Total number of stages in workflow
     * @param progressPercentage Overall progress (0-100)
     * @param message Human-readable progress message
     * @param elapsedMs Time elapsed since workflow started (milliseconds)
     */
    record ProcessingProgress(
            String reportId,
            ProcessingStage currentStage,
            Integer completedStages,
            Integer totalStages,
            Double progressPercentage,
            String message,
            Long elapsedMs
    ) {
        /**
         * Check if processing is complete.
         */
        public boolean isComplete() {
            return completedStages.equals(totalStages);
        }

        /**
         * Get formatted progress string.
         */
        public String getFormattedProgress() {
            return String.format("%s - %.1f%% complete (%d/%d stages)",
                    currentStage.getDisplayName(),
                    progressPercentage,
                    completedStages,
                    totalStages);
        }
    }
}

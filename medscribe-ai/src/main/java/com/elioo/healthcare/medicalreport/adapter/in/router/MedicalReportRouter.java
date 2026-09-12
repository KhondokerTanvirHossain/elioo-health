package com.elioo.healthcare.medicalreport.adapter.in.router;

import com.elioo.healthcare.medicalreport.adapter.in.handler.MedicalReportHandler;
import com.elioo.healthcare.medicalreport.adapter.in.handler.MedicalReportOrchestrationHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;

@Configuration
@RequiredArgsConstructor
public class MedicalReportRouter {

    private final MedicalReportHandler medicalReportHandler;
    private final MedicalReportOrchestrationHandler orchestrationHandler;

    /**
     * Routes for individual medical report processing steps.
     * These endpoints allow step-by-step processing for testing and debugging.
     */
    @Bean
    public RouterFunction<ServerResponse> medicalReportRoutes() {
        return RouterFunctions
                .route(POST("/api/v1/medical-report/ocr"), medicalReportHandler::processOcr)
                .andRoute(POST("/api/v1/medical-report/classify"), medicalReportHandler::classifyMedicalData)
                .andRoute(POST("/api/v1/medical-report/suggestions"), medicalReportHandler::generateSuggestions)
                .andRoute(POST("/api/v1/medical-report/free-text-insights"), medicalReportHandler::generateFreeTextInsights);
    }

    /**
     * Master orchestration routes for complete end-to-end processing.
     * These are the primary endpoints for production use.
     *
     * <p><b>Single Image Processing:</b></p>
     * POST /api/v1/medical-report/process
     * - Accepts: MasterProcessingRequest (single image + patient context)
     * - Returns: HTTP 202 Accepted with report ID (async processing)
     * - Orchestrates all 10 workflow stages automatically
     * - Estimated completion: 10 minutes
     * - Poll status: GET /api/v1/medical-report/query/status/{reportId}
     *
     * <p><b>Multi-Image Processing:</b></p>
     * POST /api/v1/medical-report/process-multi-image
     * - Accepts: MultiImageRequest (1-10 images + patient context)
     * - Returns: HTTP 202 Accepted with report ID (async processing)
     * - Processes images in parallel, merges results by confidence
     * - Orchestrates validation → OCR → classification → insights workflow
     * - Estimated completion: 15 minutes
     * - Poll status: GET /api/v1/medical-report/query/status/{reportId}
     *
     * <p><b>Status Polling:</b></p>
     * Both endpoints return immediately with a report ID. Use these endpoints to poll for results:
     * - GET /api/v1/medical-report/query/status/{reportId} - Basic status
     * - GET /api/v1/medical-report/query/details/{reportId} - Detailed status with partial results
     */
    @Bean
    public RouterFunction<ServerResponse> masterOrchestrationRoutes() {
        return RouterFunctions
                .route(POST("/api/v1/medical-report/process"),
                       orchestrationHandler::processCompleteMedicalReport)
                .andRoute(POST("/api/v1/medical-report/process-multi-image"),
                         orchestrationHandler::processMultiImageReport);
    }
}

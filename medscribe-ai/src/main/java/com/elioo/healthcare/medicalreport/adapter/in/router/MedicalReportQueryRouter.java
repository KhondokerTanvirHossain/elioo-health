package com.elioo.healthcare.medicalreport.adapter.in.router;

import com.elioo.healthcare.core.MedScribePaths;
import com.elioo.healthcare.medicalreport.adapter.in.handler.MedicalReportQueryHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

/**
 * Router configuration for medical report query APIs.
 *
 * <p>Architecture: Inbound Adapter (Web) in Hexagonal Architecture</p>
 * <ul>
 *   <li>Defines HTTP routes for query operations</li>
 *   <li>Maps routes to handler methods</li>
 *   <li>Uses functional reactive routing (not @RequestMapping)</li>
 * </ul>
 *
 * <p>Base Path: /api/v1/medical-report/query</p>
 *
 * <p>Route Groups:</p>
 * <ul>
 *   <li>Process Status: /status, /details, /patient</li>
 *   <li>Results: /results</li>
 *   <li>Errors: /errors</li>
 *   <li>Analytics: /analytics</li>
 *   <li>High Risk: /high-risk</li>
 * </ul>
 */
@Configuration
public class MedicalReportQueryRouter {

    private static final String BASE_PATH = MedScribePaths.PREFIX + "/api/v1/medical-report/query";

    @Bean
    public RouterFunction<ServerResponse> queryRoutes(MedicalReportQueryHandler handler) {
        return RouterFunctions.route()
                // Process Status Routes
                .GET(BASE_PATH + "/status/{reportId}", handler::getProcessingStatus)
                .GET(BASE_PATH + "/details/{reportId}", handler::getProcessingDetails)
                .GET(BASE_PATH + "/patient/{patientId}/reports", handler::getPatientReports)

                // Result Routes
                .GET(BASE_PATH + "/results/{reportId}/ocr", handler::getOcrResults)
                .GET(BASE_PATH + "/results/{reportId}/classification", handler::getClassificationResults)
                .GET(BASE_PATH + "/results/{reportId}/icd10", handler::getIcd10Results)
                .GET(BASE_PATH + "/results/{reportId}/rxnorm", handler::getRxNormResults)
                .GET(BASE_PATH + "/results/{reportId}/snomedct", handler::getSnomedCtResults)
                .GET(BASE_PATH + "/results/{reportId}/risk-assessment", handler::getRiskAssessment)
                .GET(BASE_PATH + "/results/{reportId}/recommendations", handler::getRecommendations)
                .GET(BASE_PATH + "/results/{reportId}/educational-content", handler::getEducationalContent)
                .GET(BASE_PATH + "/results/{reportId}/insights", handler::getClinicalInsights)
                .GET(BASE_PATH + "/results/{reportId}/all", handler::getAllResults)

                // High Risk Reports
                .GET(BASE_PATH + "/high-risk", handler::getHighRiskReports)

                // Error Routes
                .GET(BASE_PATH + "/errors/{reportId}", handler::getReportErrors)
                .GET(BASE_PATH + "/errors/recent", handler::getRecentErrors)

                // Analytics Routes
                .GET(BASE_PATH + "/analytics/metrics", handler::getProcessingMetrics)
                .GET(BASE_PATH + "/analytics/stage-stats", handler::getStageStatistics)
                .GET(BASE_PATH + "/analytics/error-patterns", handler::getErrorPatterns)

                .build();
    }
}

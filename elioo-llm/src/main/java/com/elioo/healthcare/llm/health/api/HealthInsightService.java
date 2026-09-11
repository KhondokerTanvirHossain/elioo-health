package com.elioo.healthcare.llm.health.api;

import com.elioo.healthcare.llm.health.dto.*;
import reactor.core.publisher.Mono;

/**
 * High-level interface for health/clinical operations on any LLM provider.
 *
 * <p>This interface provides pre-built prompt engineering and response parsing
 * for common clinical AI tasks. It shields applications from low-level model
 * invocation details while maintaining flexibility and customization options.</p>
 *
 * <p><b>Key Features:</b></p>
 * <ul>
 *   <li>Domain-agnostic DTOs for maximum reusability across health applications</li>
 *   <li>Built-in prompt templates optimized for medical AI tasks</li>
 *   <li>Reactive API (returns Mono/Flux for non-blocking execution)</li>
 *   <li>Caching support for cost optimization</li>
 *   <li>Extensible via custom prompts</li>
 * </ul>
 *
 * <p><b>Use Cases:</b></p>
 * <ul>
 *   <li>Generate comprehensive clinical insights from medical test results</li>
 *   <li>Create patient-friendly or provider-friendly summaries</li>
 *   <li>Assess clinical risk across multiple organ systems</li>
 *   <li>Generate evidence-based clinical recommendations</li>
 *   <li>Analyze temporal trends in medical data</li>
 *   <li>Create educational content for patients</li>
 * </ul>
 *
 * <p><b>Implementation:</b></p>
 * <p>The default implementation ({@code HealthInsightServiceImpl}) uses the configured LlmClient
 * with Claude models, but applications can provide custom implementations for other
 * AI providers.</p>
 *
 * <p><b>Example Usage:</b></p>
 * <pre>{@code
 * // Inject the service (Spring Boot auto-configuration)
 * @Autowired
 * private HealthInsightService healthService;
 *
 * // Generate clinical insights
 * ClinicalInsightRequest request = ClinicalInsightRequest.simple(
 *     medicalData, patientAge, patientGender
 * );
 *
 * Mono<ClinicalInsightResponse> insights = healthService
 *     .generateClinicalInsights(request);
 * }</pre>
 *
 * @see ClinicalInsightRequest
 * @see ClinicalInsightResponse
 * @since 0.2.0
 */
public interface HealthInsightService {

    /**
     * Generate comprehensive clinical insights from medical data.
     *
     * <p>This method analyzes medical test results, patient context, and clinical data
     * to generate a comprehensive report including:</p>
     * <ul>
     *   <li>Executive summary of findings</li>
     *   <li>Key clinical findings with severity levels</li>
     *   <li>Evidence-based recommendations</li>
     *   <li>Risk assessment across organ systems (if requested)</li>
     *   <li>Prioritized action plan with timeframes</li>
     * </ul>
     *
     * <p><b>Example:</b></p>
     * <pre>{@code
     * Map<String, Object> medicalData = Map.of(
     *     "creatinine", Map.of("value", 135.0, "unit", "µmol/L", "status", "ABNORMAL"),
     *     "glucose", Map.of("value", 7.2, "unit", "mmol/L", "status", "ELEVATED")
     * );
     *
     * ClinicalInsightRequest request = new ClinicalInsightRequest(
     *     medicalData,
     *     PatientContext.minimal(45, "MALE"),
     *     InsightOptions.defaultPatient()
     * );
     *
     * ClinicalInsightResponse response = healthService
     *     .generateClinicalInsights(request)
     *     .block();
     * }</pre>
     *
     * @param request Contains medical data, patient context, and generation options
     * @return Mono emitting clinical insights with findings, recommendations, and risk assessment
     * @throws com.elioo.healthcare.llm.health.exception.HealthInsightException if generation fails
     */
    Mono<ClinicalInsightResponse> generateClinicalInsights(ClinicalInsightRequest request);

    /**
     * Generate a patient-friendly or provider-friendly summary of medical data.
     *
     * <p>Creates a concise, audience-appropriate summary of medical findings.
     * The summary can be brief (1-2 sentences), standard (1 paragraph), or
     * detailed (multiple paragraphs) based on options.</p>
     *
     * <p><b>Example:</b></p>
     * <pre>{@code
     * SummaryRequest request = SummaryRequest.simple(medicalData);
     *
     * SummaryResponse response = healthService
     *     .generateSummary(request)
     *     .block();
     *
     * System.out.println(response.summary());
     * // Output: "Your test results show elevated creatinine levels (135 µmol/L),
     * //          which may indicate kidney function concerns..."
     * }</pre>
     *
     * @param request Medical data and summary options (audience, length, focus)
     * @return Mono emitting formatted summary with title and key points
     * @throws com.elioo.healthcare.llm.health.exception.HealthInsightException if generation fails
     */
    Mono<SummaryResponse> generateSummary(SummaryRequest request);

    /**
     * Assess clinical risk across multiple organ systems.
     *
     * <p>Analyzes medical data to identify risk factors and assign risk scores
     * for different clinical categories (cardiovascular, metabolic, renal, etc.).
     * Optionally includes prevention strategies.</p>
     *
     * <p><b>Example:</b></p>
     * <pre>{@code
     * RiskAssessmentRequest request = RiskAssessmentRequest.simple(
     *     medicalData,
     *     PatientContext.withHistory(45, "MALE", List.of("Diabetes Type 2"))
     * );
     *
     * RiskAssessmentResponse response = healthService
     *     .assessRisk(request)
     *     .block();
     *
     * if (response.isElevatedRisk()) {
     *     // Handle high-risk scenario
     * }
     * }</pre>
     *
     * @param request Medical data and risk assessment options
     * @return Mono emitting risk scores, prevention strategies, and overall assessment
     * @throws com.elioo.healthcare.llm.health.exception.HealthInsightException if assessment fails
     */
    Mono<RiskAssessmentResponse> assessRisk(RiskAssessmentRequest request);

    /**
     * Generate evidence-based clinical recommendations.
     *
     * <p>Produces actionable clinical recommendations based on medical findings.
     * Recommendations are categorized (IMMEDIATE_ACTION, DIAGNOSTIC_TESTS,
     * MEDICATION, LIFESTYLE, etc.) and prioritized (URGENT, HIGH, MEDIUM, LOW).</p>
     *
     * <p><b>Example:</b></p>
     * <pre>{@code
     * RecommendationRequest request = RecommendationRequest.simple(
     *     medicalFindings,
     *     patientContext
     * );
     *
     * RecommendationResponse response = healthService
     *     .generateRecommendations(request)
     *     .block();
     *
     * response.recommendations().stream()
     *     .filter(ClinicalRecommendation::isUrgent)
     *     .forEach(rec -> System.out.println(rec.recommendation()));
     * }</pre>
     *
     * @param request Medical findings and recommendation options
     * @return Mono emitting categorized recommendations with evidence levels
     * @throws com.elioo.healthcare.llm.health.exception.HealthInsightException if generation fails
     */
    Mono<RecommendationResponse> generateRecommendations(RecommendationRequest request);

    /**
     * Analyze temporal trends in medical test results.
     *
     * <p>Performs time-series analysis on historical medical data to identify
     * patterns, detect anomalies, and predict future values. Useful for
     * tracking disease progression or treatment efficacy.</p>
     *
     * <p><b>Example:</b></p>
     * <pre>{@code
     * List<TimeSeriesDataPoint> history = List.of(
     *     TimeSeriesDataPoint.simple("2024-01-01T00:00:00Z", "creatinine", 100.0, "µmol/L"),
     *     TimeSeriesDataPoint.simple("2024-02-01T00:00:00Z", "creatinine", 110.0, "µmol/L"),
     *     TimeSeriesDataPoint.simple("2024-03-01T00:00:00Z", "creatinine", 135.0, "µmol/L")
     * );
     *
     * TrendAnalysisRequest request = TrendAnalysisRequest.simple(history);
     *
     * TrendAnalysisResponse response = healthService
     *     .analyzeTrends(request)
     *     .block();
     *
     * if (response.getConcerningPatternCount() > 0) {
     *     // Alert: Concerning trend detected
     * }
     * }</pre>
     *
     * @param request Historical test data and trend analysis options
     * @return Mono emitting patterns, anomalies, predictions, and trend summary
     * @throws com.elioo.healthcare.llm.health.exception.HealthInsightException if analysis fails
     */
    Mono<TrendAnalysisResponse> analyzeTrends(TrendAnalysisRequest request);

    /**
     * Generate patient education materials.
     *
     * <p>Creates educational content about medical topics at an appropriate
     * reading level. Content can be formatted as narrative text, bullet points,
     * or FAQ style. Optionally personalized with patient context.</p>
     *
     * <p><b>Example:</b></p>
     * <pre>{@code
     * EducationalContentRequest request = EducationalContentRequest.faq(
     *     "high creatinine levels",
     *     ReadingLevel.SIMPLE
     * );
     *
     * EducationalContentResponse response = healthService
     *     .generateEducationalContent(request)
     *     .block();
     *
     * response.faqs().forEach(faq ->
     *     System.out.println("Q: " + faq.question() + "\nA: " + faq.answer())
     * );
     * }</pre>
     *
     * @param request Medical topic and educational content options
     * @return Mono emitting educational content with key takeaways and FAQs
     * @throws com.elioo.healthcare.llm.health.exception.HealthInsightException if generation fails
     */
    Mono<EducationalContentResponse> generateEducationalContent(EducationalContentRequest request);

    /**
     * Execute a custom prompt with structured response parsing.
     *
     * <p>Allows applications to use custom prompts while benefiting from
     * the library's response parsing and error handling. Useful when the
     * pre-built methods don't fit specific use cases.</p>
     *
     * <p><b>Example:</b></p>
     * <pre>{@code
     * String customPrompt = """
     *     Analyze this differential diagnosis:
     *     Symptoms: %s
     *     Labs: %s
     *
     *     Respond with JSON: {"diagnosis": [...], "confidence": 0.0-1.0}
     *     """.formatted(symptoms, labs);
     *
     * Mono<DiagnosisResponse> response = healthService
     *     .executeCustomPrompt(customPrompt, DiagnosisResponse.class);
     * }</pre>
     *
     * @param prompt Custom prompt text
     * @param responseClass Expected response type (must be deserializable from JSON)
     * @param <T> Response type
     * @return Mono emitting parsed response
     * @throws com.elioo.healthcare.llm.health.exception.HealthInsightException if execution or parsing fails
     */
    <T> Mono<T> executeCustomPrompt(String prompt, Class<T> responseClass);
}

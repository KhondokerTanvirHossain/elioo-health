package com.elioo.healthcare.medicalreport.application.port.out;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Outbound port for clinical insight generation using AI/LLM services.
 * This interface defines the contract for generating medical summaries, recommendations, and risk assessments.
 *
 * Implementation Note: This is a driven port in hexagonal architecture.
 * The business logic depends on this interface, not on specific AI/LLM providers.
 *
 * Possible implementations:
 * - AWS Bedrock (Claude, Llama, etc.)
 * - Azure OpenAI
 * - Google Cloud Vertex AI
 * - OpenAI API
 * - Custom fine-tuned models
 */
public interface ClinicalInsightPort {

    /**
     * Generate comprehensive clinical insights and recommendations.
     * Includes summary, key findings, AI suggestions, risk assessment, and action plan.
     *
     * @param request Insight generation request with patient context and options
     * @return Mono containing generated clinical insights
     */
    Mono<ClinicalInsightResult> generateClinicalInsights(InsightRequest request);

    /**
     * Generate a patient-friendly summary from medical data.
     *
     * @param medicalData Structured medical data
     * @param targetAudience PATIENT or PROVIDER
     * @return Mono containing generated summary
     */
    Mono<String> generateSummary(Map<String, Object> medicalData, String targetAudience);

    /**
     * Assess clinical risk based on test results and patient context.
     *
     * @param request Risk assessment request
     * @return Mono containing risk assessment result
     */
    Mono<RiskAssessment> assessRisk(RiskAssessmentRequest request);

    /**
     * Generate evidence-based recommendations.
     *
     * @param findings List of clinical findings
     * @param patientContext Patient demographics and history
     * @return Mono containing list of recommendations
     */
    Mono<List<Recommendation>> generateRecommendations(
            List<KeyFinding> findings,
            PatientContext patientContext
    );

    /**
     * Analyze trends from historical medical data.
     *
     * @param historicalData Time-series medical data
     * @param metric Specific metric to analyze (e.g., "creatinine", "glucose")
     * @return Mono containing trend analysis
     */
    Mono<TrendAnalysis> analyzeTrends(List<Map<String, Object>> historicalData, String metric);

    /**
     * Generate educational content for patients.
     *
     * @param topic Medical topic or condition
     * @param readingLevel Target reading level (BASIC, INTERMEDIATE, ADVANCED)
     * @return Mono containing educational content
     */
    Mono<EducationalContent> generateEducationalContent(String topic, String readingLevel);

    /**
     * Request object for clinical insight generation.
     */
    record InsightRequest(
            String reportId,
            Map<String, Object> extractedData,
            Map<String, Object> classificationResult,
            PatientContext patientContext,
            SummaryOptions summaryOptions,
            RiskAssessmentOptions riskOptions,
            Map<String, Object> additionalContext
    ) {}

    /**
     * Patient context for personalized insights.
     */
    record PatientContext(
            String patientId,
            Integer age,
            String gender,
            List<String> medicalHistory,
            List<String> currentMedications,
            List<String> allergies,
            Map<String, Object> vitalSigns,  // Changed from Double to Object for flexibility
            Map<String, Object> lifestyle
    ) {}

    /**
     * Options for summary generation.
     */
    record SummaryOptions(
            String targetAudience,
            String language,
            boolean includeEducationalContent,
            String summaryLength
    ) {}

    /**
     * Options for risk assessment.
     */
    record RiskAssessmentOptions(
            List<String> riskCategories,
            boolean includePreventiveMeasures,
            String timeframe
    ) {}

    /**
     * Complete clinical insight result.
     */
    record ClinicalInsightResult(
            String reportId,
            String summary,
            List<KeyFinding> keyFindings,
            List<Recommendation> aiSuggestions,
            RiskAssessment riskAssessment,
            TrendAnalysis trendAnalysis,
            ActionPlan actionPlan,
            EducationalContent educationalContent,
            String riskLevel,
            boolean requiresImmediateAttention,
            Map<String, Object> metadata
    ) {}

    /**
     * Key finding from medical data.
     */
    record KeyFinding(
            String finding,
            String severity,
            String interpretation,
            String clinicalSignificance,
            List<String> relatedTests,
            Map<String, Object> details
    ) {}

    /**
     * AI-generated recommendation.
     */
    record Recommendation(
            String category,
            String priority,
            String recommendation,
            String rationale,
            String evidenceLevel,
            String timeframe,
            List<String> prerequisites
    ) {}

    /**
     * Risk assessment result.
     */
    record RiskAssessment(
            String overallRisk,
            Map<String, RiskCategory> categoryRisks,
            List<String> riskFactors,
            List<String> protectiveFactors,
            String assessment
    ) {}

    /**
     * Risk category assessment.
     */
    record RiskCategory(
            String level,
            double score,
            String description,
            List<String> contributors
    ) {}

    /**
     * Request for risk assessment.
     */
    record RiskAssessmentRequest(
            Map<String, Object> medicalData,
            PatientContext patientContext,
            List<String> focusAreas
    ) {}

    /**
     * Trend analysis result.
     */
    record TrendAnalysis(
            String metric,
            String direction,
            String interpretation,
            List<DataPoint> dataPoints,
            List<String> observations
    ) {}

    /**
     * Data point in trend analysis.
     */
    record DataPoint(
            String date,
            Double value,
            String status
    ) {}

    /**
     * Action plan with prioritized actions.
     */
    record ActionPlan(
            List<Action> immediateActions,
            List<Action> shortTermActions,
            List<Action> longTermActions
    ) {}

    /**
     * Recommended action.
     */
    record Action(
            String action,
            String priority,
            String timeframe,
            String category
    ) {}

    /**
     * Educational content for patients.
     */
    record EducationalContent(
            String topic,
            String content,
            List<String> keyPoints,
            List<String> resources,
            List<FrequentlyAskedQuestion> faqs
    ) {}

    /**
     * FAQ item for educational content.
     */
    record FrequentlyAskedQuestion(
            String question,
            String answer
    ) {}

    /**
     * Generate clinical insights from free-form medical text.
     *
     * <p>Analyzes unstructured medical text (conversations, notes, symptoms, observations)
     * and extracts clinical insights, findings, and recommendations without requiring
     * structured test data or patient context.</p>
     *
     * <p>This method is designed for scenarios where:</p>
     * <ul>
     *   <li>User provides free-form text (symptom descriptions, clinical notes, conversations)</li>
     *   <li>No structured lab test data is available</li>
     *   <li>Patient context may be embedded in the text itself</li>
     *   <li>Quick clinical impressions are needed</li>
     * </ul>
     *
     * @param text Free-form medical text to analyze
     * @param targetAudience PATIENT or PROVIDER (affects language complexity)
     * @param includeRiskAssessment Whether to include risk assessment
     * @param includeRecommendations Whether to include recommendations
     * @return Mono containing clinical insights extracted from text
     */
    Mono<FreeTextInsightResult> generateFreeTextInsights(
            String text,
            String targetAudience,
            boolean includeRiskAssessment,
            boolean includeRecommendations
    );

    /**
     * Chat with AI about a medical report using contextual information.
     *
     * <p>This method enables interactive Q&A sessions where patients can ask questions
     * about their medical reports. The AI provides contextual answers based on:</p>
     * <ul>
     *   <li>Full report data (OCR results, classification, insights, risk assessment)</li>
     *   <li>Patient demographics and medical history</li>
     *   <li>Previous conversation history (for context continuity)</li>
     *   <li>Current user question</li>
     * </ul>
     *
     * <p>The AI is instructed to:</p>
     * <ul>
     *   <li>Provide patient-friendly, clear explanations</li>
     *   <li>Base answers strictly on the provided report data</li>
     *   <li>Indicate when information is not available in the report</li>
     *   <li>Avoid making diagnoses or treatment recommendations</li>
     *   <li>Encourage consulting healthcare providers for medical decisions</li>
     * </ul>
     *
     * @param reportId Report identifier (for logging/tracking)
     * @param userMessage User's question about the report
     * @param conversationHistory Previous messages in the conversation (for context)
     * @param reportContext Complete report context (OCR, classification, insights, etc.)
     * @param patientContext Patient demographics and medical history
     * @return Mono containing AI's response text
     */
    Mono<String> chatAboutReport(
            String reportId,
            String userMessage,
            java.util.List<com.elioo.healthcare.medicalreport.domain.ChatMessage> conversationHistory,
            Map<String, Object> reportContext,
            PatientContext patientContext
    );

    /**
     * Result object for free-text clinical insights.
     *
     * <p>Contains the same structured information as {@link ClinicalInsightResult} but
     * derived from free-form text analysis rather than structured medical data.</p>
     *
     * @param summary Overall summary of findings (5-10 sentences)
     * @param keyFindings List of clinically significant findings identified in text
     * @param aiSuggestions List of evidence-based recommendations
     * @param riskLevel Overall risk level (LOW, MODERATE, HIGH, CRITICAL)
     * @param requiresImmediateAttention Whether immediate medical attention is recommended
     * @param confidence AI confidence score (0.0-1.0), typically lower than structured data analysis
     */
    record FreeTextInsightResult(
            String summary,
            List<KeyFinding> keyFindings,
            List<Recommendation> aiSuggestions,
            String riskLevel,
            boolean requiresImmediateAttention,
            double confidence
    ) {}
}

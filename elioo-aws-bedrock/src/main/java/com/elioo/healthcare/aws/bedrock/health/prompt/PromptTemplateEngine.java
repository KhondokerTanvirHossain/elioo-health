package com.elioo.healthcare.aws.bedrock.health.prompt;

import com.elioo.healthcare.aws.bedrock.health.dto.*;

import java.util.List;
import java.util.Map;

/**
 * Interface for building structured prompts for clinical AI operations.
 *
 * <p>This interface encapsulates prompt engineering best practices for medical AI,
 * providing pre-built templates optimized for different clinical tasks while
 * maintaining flexibility for customization.</p>
 *
 * <p><b>Design Principles:</b></p>
 * <ul>
 *   <li>Prompt templates are optimized for Claude 3 models but work with other LLMs</li>
 *   <li>JSON-structured output for consistent parsing</li>
 *   <li>Medical terminology and clinical context awareness</li>
 *   <li>Audience adaptation (patient vs provider language)</li>
 *   <li>Extensible via custom template overrides</li>
 * </ul>
 *
 * @since 0.2.0
 */
public interface PromptTemplateEngine {

    /**
     * Build prompt for generating comprehensive clinical insights.
     *
     * <p>Constructs a prompt that instructs the AI to:</p>
     * <ul>
     *   <li>Analyze medical data and patient context</li>
     *   <li>Identify key clinical findings with severity levels</li>
     *   <li>Generate evidence-based recommendations</li>
     *   <li>Assess risk across organ systems</li>
     *   <li>Create prioritized action plan</li>
     * </ul>
     *
     * @param medicalData Medical test results and clinical data
     * @param patientContext Patient demographics and history
     * @param options Generation options (audience, focus, etc.)
     * @return Structured prompt string
     */
    String buildClinicalInsightPrompt(
            Map<String, Object> medicalData,
            PatientContext patientContext,
            InsightOptions options
    );

    /**
     * Build prompt for generating medical data summary.
     *
     * <p>Constructs a prompt that creates concise, audience-appropriate
     * summaries of medical findings.</p>
     *
     * @param medicalData Medical data to summarize
     * @param patientContext Patient context for personalization
     * @param options Summary options (audience, length, focus)
     * @return Structured prompt string
     */
    String buildSummaryPrompt(
            Map<String, Object> medicalData,
            PatientContext patientContext,
            SummaryOptions options
    );

    /**
     * Build prompt for clinical risk assessment.
     *
     * <p>Constructs a prompt that assesses clinical risk across multiple
     * organ systems and identifies risk factors.</p>
     *
     * @param medicalData Medical data for risk assessment
     * @param patientContext Patient context
     * @param options Risk assessment options
     * @return Structured prompt string
     */
    String buildRiskAssessmentPrompt(
            Map<String, Object> medicalData,
            PatientContext patientContext,
            RiskAssessmentOptions options
     );

    /**
     * Build prompt for generating clinical recommendations.
     *
     * <p>Constructs a prompt that generates evidence-based clinical
     * recommendations with priority levels and evidence grades.</p>
     *
     * @param medicalFindings Medical findings requiring recommendations
     * @param patientContext Patient context
     * @param options Recommendation options
     * @return Structured prompt string
     */
    String buildRecommendationPrompt(
            Map<String, Object> medicalFindings,
            PatientContext patientContext,
            RecommendationOptions options
    );

    /**
     * Build prompt for temporal trend analysis.
     *
     * <p>Constructs a prompt that analyzes time-series medical data to
     * identify patterns, anomalies, and predict future values.</p>
     *
     * @param historicalData Historical time-series data points
     * @param patientContext Patient context
     * @param options Trend analysis options
     * @return Structured prompt string
     */
    String buildTrendAnalysisPrompt(
            List<TimeSeriesDataPoint> historicalData,
            PatientContext patientContext,
            TrendAnalysisOptions options
    );

    /**
     * Build prompt for generating educational content.
     *
     * <p>Constructs a prompt that creates patient education materials
     * at appropriate reading levels with clear explanations.</p>
     *
     * @param topic Medical topic for educational content
     * @param patientContext Optional patient context for personalization
     * @param options Educational content options
     * @return Structured prompt string
     */
    String buildEducationalContentPrompt(
            String topic,
            PatientContext patientContext,
            EducationalContentOptions options
    );

    /**
     * Get system prompt for clinical AI assistant.
     *
     * <p>Returns the base system prompt that defines the AI's role,
     * behavior, and output format for clinical operations.</p>
     *
     * @return System prompt string
     */
    String getSystemPrompt();

    /**
     * Get system prompt for specific audience.
     *
     * <p>Returns an audience-specific system prompt that adjusts
     * language complexity and terminology.</p>
     *
     * @param audience Target audience
     * @return Audience-specific system prompt
     */
    String getSystemPrompt(TargetAudience audience);
}

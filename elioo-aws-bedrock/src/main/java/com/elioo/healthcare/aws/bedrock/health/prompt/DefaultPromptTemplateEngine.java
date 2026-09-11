package com.elioo.healthcare.aws.bedrock.health.prompt;

import com.elioo.healthcare.aws.bedrock.health.dto.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Default implementation of PromptTemplateEngine with optimized prompts for clinical AI.
 *
 * <p>This implementation provides carefully crafted prompt templates that:</p>
 * <ul>
 *   <li>Instruct the AI to respond in structured JSON format</li>
 *   <li>Include medical context and patient-specific information</li>
 *   <li>Adapt language based on target audience</li>
 *   <li>Emphasize evidence-based medicine principles</li>
 *   <li>Request confidence scores and uncertainty acknowledgment</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultPromptTemplateEngine implements PromptTemplateEngine {

    private final ObjectMapper objectMapper;

    @Override
    public String buildClinicalInsightPrompt(
            Map<String, Object> medicalData,
            PatientContext patientContext,
            InsightOptions options
    ) {
        String audienceInstruction = getAudienceInstruction(options.targetAudience());
        String focusInstruction = options.focusArea() != null ?
                "Focus particularly on " + options.focusArea() + " findings." : "";

        return String.format("""
                You are a medical AI assistant analyzing patient medical data.

                %s

                **Medical Data:**
                %s

                **Patient Context:**
                %s

                **Task:** Generate comprehensive clinical insights from the medical data above.
                %s

                **Instructions:**
                1. Analyze all medical data and identify key clinical findings
                2. Determine severity level for each finding (LOW, MODERATE, HIGH, CRITICAL)
                3. Generate evidence-based recommendations with priority levels
                %s
                %s
                4. Provide overall confidence score (0.0-1.0)

                **Response Format (JSON):**
                ```json
                {
                  "summary": "Summary of overall document, key findings, risk level and instructions, advice. Make it 5-10 sentences",
                  "keyFindings": [
                    {
                      "id": "finding_1",
                      "description": "Description of the finding",
                      "category": "LAB_ABNORMALITY | RISK_FACTOR | etc",
                      "severity": "LOW | MODERATE | HIGH | CRITICAL",
                      "explanation": "Clinical significance and interpretation",
                      "relatedTests": ["test1", "test2"],
                      "details": {}
                    }
                  ],
                  "recommendations": [
                    {
                      "id": "rec_1",
                      "category": "IMMEDIATE_ACTION | DIAGNOSTIC_TESTS | MEDICATION | LIFESTYLE | MONITORING",
                      "priority": "URGENT | HIGH | MEDIUM | LOW",
                      "recommendation": "The actual recommendation",
                      "rationale": "Why this is recommended",
                      "evidenceLevel": "A | B | C",
                      "timeframe": "Immediate | Within 24 hours | 1-2 weeks | etc"
                    }
                  ],
                  "riskAssessment": {
                    "riskScores": {
                      "cardiovascular": {
                        "category": "cardiovascular",
                        "level": "LOW | MODERATE | HIGH | CRITICAL",
                        "score": 0.0,
                        "explanation": "Explanation of cardiovascular risk"
                      },
                      "metabolic": {...},
                      "renal": {...}
                    },
                    "overallRiskLevel": "LOW | MODERATE | HIGH",
                    "riskFactors": ["factor1", "factor2"]
                  },
                  "actionPlan": {
                    "immediateActions": [
                      {
                        "action": "Specific action to take",
                        "priority": "URGENT | HIGH",
                        "timeframe": "Immediate",
                        "category": "DIAGNOSTIC | MEDICATION | etc"
                      }
                    ],
                    "shortTermActions": [...],
                    "longTermActions": [...]
                  },
                  "confidence": 0.95
                }
                ```

                Respond ONLY with valid JSON matching the format above.
                """,
                audienceInstruction,
                formatData(medicalData),
                formatPatientContext(patientContext),
                focusInstruction,
                options.includeRiskAssessment() ? "3a. Assess clinical risk across organ systems (cardiovascular, metabolic, renal, hepatic, etc.)" : "",
                options.includeRecommendations() ? "3b. Generate up to " + (options.maxRecommendations() != null ? options.maxRecommendations() : 10) + " evidence-based recommendations" : ""
        );
    }

    @Override
    public String buildSummaryPrompt(
            Map<String, Object> medicalData,
            PatientContext patientContext,
            SummaryOptions options
    ) {
        String audienceInstruction = getAudienceInstruction(options.targetAudience());
        String lengthInstruction = getLengthInstruction(options.length());
        String focusInstruction = options.focusArea() != null ?
                "Focus on: " + options.focusArea() : "";

        return String.format("""
                You are a medical AI assistant creating a summary of patient medical data.

                %s
                %s

                **Medical Data:**
                %s

                **Patient Context:**
                %s

                **Task:** Create a %s summary of the medical findings.
                %s

                **Response Format (JSON):**
                ```json
                {
                  "summary": "The generated summary text",
                  "title": "Brief title for the summary",
                  "keyPoints": [
                    "Key point 1",
                    "Key point 2",
                    "Key point 3"
                  ]
                }
                ```

                Respond ONLY with valid JSON matching the format above.
                """,
                audienceInstruction,
                lengthInstruction,
                formatData(medicalData),
                formatPatientContext(patientContext),
                options.length().toString().toLowerCase(),
                focusInstruction
        );
    }

    @Override
    public String buildRiskAssessmentPrompt(
            Map<String, Object> medicalData,
            PatientContext patientContext,
            RiskAssessmentOptions options
    ) {
        String categoriesInstruction = options.hasSpecificCategories() ?
                "Assess risk for these categories: " + String.join(", ", options.riskCategories()) :
                "Assess risk across all relevant organ systems (cardiovascular, metabolic, renal, hepatic, respiratory, etc.)";

        String timeHorizonInstruction = options.timeHorizon() != null ?
                "Time horizon: " + options.timeHorizon() + " months" : "";

        return String.format("""
                You are a medical AI assistant performing clinical risk assessment.

                **Medical Data:**
                %s

                **Patient Context:**
                %s

                **Task:** %s
                %s

                **Response Format (JSON):**
                ```json
                {
                  "riskAssessment": {
                    "riskScores": {
                      "cardiovascular": {
                        "category": "cardiovascular",
                        "level": "LOW | MODERATE | HIGH | CRITICAL",
                        "score": 0.0,
                        "explanation": "Detailed explanation"
                      }
                    },
                    "overallRiskLevel": "LOW | MODERATE | HIGH",
                    "riskFactors": ["factor1", "factor2"]
                  },
                  "preventionStrategies": [
                    "Prevention strategy 1",
                    "Prevention strategy 2"
                  ],
                  "overallAssessment": "Overall risk assessment summary"
                }
                ```

                Respond ONLY with valid JSON matching the format above.
                """,
                formatData(medicalData),
                formatPatientContext(patientContext),
                categoriesInstruction,
                timeHorizonInstruction
        );
    }

    @Override
    public String buildRecommendationPrompt(
            Map<String, Object> medicalFindings,
            PatientContext patientContext,
            RecommendationOptions options
    ) {
        String categoryFilter = options.categories() != null && !options.categories().isEmpty() ?
                "Focus on these categories: " + String.join(", ", options.categories()) : "";

        String priorityFilter = options.priorityFilter() != null ?
                "Priority filter: " + options.priorityFilter() : "";

        String maxRecommendations = options.maxRecommendations() != null ?
                "Generate up to " + options.maxRecommendations() + " recommendations." : "";

        return String.format("""
                You are a medical AI assistant generating clinical recommendations.

                **Medical Findings:**
                %s

                **Patient Context:**
                %s

                **Task:** Generate evidence-based clinical recommendations.
                %s
                %s
                %s

                **Instructions:**
                1. Prioritize recommendations by urgency (URGENT, HIGH, MEDIUM, LOW)
                2. Categorize by type (IMMEDIATE_ACTION, DIAGNOSTIC_TESTS, MEDICATION, LIFESTYLE, MONITORING)
                %s
                4. Include timeframe for each recommendation

                **Response Format (JSON):**
                ```json
                {
                  "recommendations": [
                    {
                      "id": "rec_1",
                      "category": "IMMEDIATE_ACTION | DIAGNOSTIC_TESTS | MEDICATION | LIFESTYLE | MONITORING",
                      "priority": "URGENT | HIGH | MEDIUM | LOW",
                      "recommendation": "The recommendation text",
                      "rationale": "Why this is recommended",
                      "evidenceLevel": "A | B | C",
                      "timeframe": "Immediate | Within 24 hours | 1-2 weeks | etc"
                    }
                  ],
                  "summary": "Brief summary of recommendations"
                }
                ```

                Respond ONLY with valid JSON matching the format above.
                """,
                formatData(medicalFindings),
                formatPatientContext(patientContext),
                categoryFilter,
                priorityFilter,
                maxRecommendations,
                options.includeEvidenceLevels() ? "3. Include evidence level (A=strong, B=moderate, C=limited)" : ""
        );
    }

    @Override
    public String buildTrendAnalysisPrompt(
            List<TimeSeriesDataPoint> historicalData,
            PatientContext patientContext,
            TrendAnalysisOptions options
    ) {
        String testsFilter = options.testsToAnalyze() != null && !options.testsToAnalyze().isEmpty() ?
                "Focus on these tests: " + String.join(", ", options.testsToAnalyze()) : "";

        return String.format("""
                You are a medical AI assistant analyzing temporal trends in medical data.

                **Historical Data:**
                %s

                **Patient Context:**
                %s

                **Task:** Analyze temporal trends in the medical data.
                %s

                **Instructions:**
                1. Identify trend patterns (INCREASING, DECREASING, STABLE, FLUCTUATING)
                2. Assess clinical significance of each pattern
                %s
                %s
                5. Provide overall trend summary

                **Response Format (JSON):**
                ```json
                {
                  "patterns": [
                    {
                      "testName": "test1",
                      "pattern": "INCREASING | DECREASING | STABLE | FLUCTUATING",
                      "clinicalSignificance": "Clinical interpretation"
                    }
                  ],
                  "anomalies": [
                    {
                      "timestamp": "2024-01-15T10:30:00Z",
                      "testName": "test1",
                      "value": 100.0,
                      "explanation": "Why this is anomalous"
                    }
                  ],
                  "predictions": {
                    "test1": {
                      "testName": "test1",
                      "predictedValue": 105.0,
                      "unit": "µmol/L",
                      "confidenceInterval": 5.0,
                      "timeframe": "Next 30 days"
                    }
                  },
                  "overallTrendSummary": "Overall trend summary"
                }
                ```

                Respond ONLY with valid JSON matching the format above.
                """,
                formatTimeSeriesData(historicalData),
                formatPatientContext(patientContext),
                testsFilter,
                options.detectAnomalies() ? "3. Detect anomalies (unusual values or patterns)" : "",
                options.predictFutureValues() ? "4. Predict future values (" + (options.predictionHorizon() != null ? options.predictionHorizon() : 30) + " days ahead)" : ""
        );
    }

    @Override
    public String buildEducationalContentPrompt(
            String topic,
            PatientContext patientContext,
            EducationalContentOptions options
    ) {
        String readingLevelInstruction = getReadingLevelInstruction(options.readingLevel());
        String formatInstruction = getFormatInstruction(options.format());
        String lengthInstruction = options.maxLength() != null ?
                "Maximum length: " + options.maxLength() + " words." : "";

        String personalizationNote = patientContext != null ?
                "Personalize content based on patient context when relevant." : "";

        return String.format("""
                You are a medical AI assistant creating patient educational content.

                **Topic:** %s

                **Patient Context:**
                %s

                **Task:** Create educational content about the topic above.

                **Requirements:**
                - %s
                - %s
                - %s
                %s
                - Use clear, empathetic language
                - Avoid medical jargon (or explain it when necessary)
                - Be accurate and evidence-based
                - Include practical, actionable information

                **Response Format (JSON):**
                ```json
                {
                  "title": "Title for the educational content",
                  "content": "Main educational content",
                  "keyTakeaways": [
                    "Key takeaway 1",
                    "Key takeaway 2",
                    "Key takeaway 3"
                  ],
                  "faqs": [
                    {
                      "question": "Frequently asked question",
                      "answer": "Clear answer"
                    }
                  ],
                  "additionalResources": [
                    "Resource 1",
                    "Resource 2"
                  ]
                }
                ```

                Respond ONLY with valid JSON matching the format above.
                """,
                topic,
                formatPatientContext(patientContext),
                readingLevelInstruction,
                formatInstruction,
                lengthInstruction,
                personalizationNote
        );
    }

    @Override
    public String getSystemPrompt() {
        return """
                You are a medical AI assistant specialized in analyzing medical data and providing clinical insights.
                Your role is to:
                1. Analyze medical test results and clinical data
                2. Identify clinically significant findings
                3. Provide evidence-based recommendations
                4. Assess clinical risk
                5. Generate clear, accurate summaries

                Important guidelines:
                - Base all analysis on medical evidence and clinical guidelines
                - Clearly indicate when you're uncertain or when findings require professional medical judgment
                - Use appropriate medical terminology but explain complex concepts
                - Prioritize patient safety in all recommendations
                - Always respond in valid JSON format as instructed
                - Do not provide definitive diagnoses - frame as clinical impressions or differential diagnoses
                - Recommend consulting healthcare providers for concerning findings
                """;
    }

    @Override
    public String getSystemPrompt(TargetAudience audience) {
        return switch (audience) {
            case PATIENT -> """
                    You are a medical AI assistant helping patients understand their medical information.
                    Use clear, simple language that is easy to understand for non-medical professionals.
                    Avoid medical jargon, or explain it when necessary.
                    Be empathetic and supportive while remaining accurate and evidence-based.
                    Always recommend consulting with healthcare providers for medical decisions.
                    """;
            case PROVIDER -> """
                    You are a medical AI assistant supporting healthcare providers with clinical decision support.
                    Use appropriate medical terminology and clinical language.
                    Provide detailed clinical reasoning and evidence-based rationales.
                    Include relevant medical codes, evidence levels, and clinical guidelines when applicable.
                    Frame insights as clinical impressions to support (not replace) clinical judgment.
                    """;
            case RESEARCHER -> """
                    You are a medical AI assistant supporting medical research and scientific analysis.
                    Use precise medical and scientific terminology.
                    Include technical details, statistical considerations, and research methodologies.
                    Reference evidence-based medicine principles and research guidelines.
                    Maintain scientific rigor and acknowledge limitations of AI-based analysis.
                    """;
        };
    }

    // Helper methods

    private String getAudienceInstruction(TargetAudience audience) {
        return switch (audience) {
            case PATIENT -> "**Target Audience:** Patient (use clear, simple language)";
            case PROVIDER -> "**Target Audience:** Healthcare Provider (use medical terminology)";
            case RESEARCHER -> "**Target Audience:** Researcher (use scientific terminology)";
        };
    }

    private String getLengthInstruction(SummaryLength length) {
        return switch (length) {
            case BRIEF -> "**Length:** Brief (1-2 sentences)";
            case STANDARD -> "**Length:** Standard (1 paragraph, 3-5 sentences)";
            case DETAILED -> "**Length:** Detailed (multiple paragraphs with comprehensive details)";
        };
    }

    private String getReadingLevelInstruction(ReadingLevel level) {
        return switch (level) {
            case SIMPLE -> "Reading level: Simple (6th-8th grade)";
            case INTERMEDIATE -> "Reading level: Intermediate (high school)";
            case ADVANCED -> "Reading level: Advanced (college/professional)";
        };
    }

    private String getFormatInstruction(ContentFormat format) {
        return switch (format) {
            case TEXT -> "Format: Narrative paragraphs";
            case BULLET_POINTS -> "Format: Bulleted list";
            case FAQ -> "Format: Question-answer (FAQ) style";
        };
    }

    private String formatData(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return "No data provided";
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.error("Error formatting medical data", e);
            return data.toString();
        }
    }

    private String formatPatientContext(PatientContext context) {
        if (context == null) {
            return "No patient context provided";
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(context);
        } catch (JsonProcessingException e) {
            log.error("Error formatting patient context", e);
            return context.toString();
        }
    }

    private String formatTimeSeriesData(List<TimeSeriesDataPoint> data) {
        if (data == null || data.isEmpty()) {
            return "No historical data provided";
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.error("Error formatting time series data", e);
            return data.toString();
        }
    }
}

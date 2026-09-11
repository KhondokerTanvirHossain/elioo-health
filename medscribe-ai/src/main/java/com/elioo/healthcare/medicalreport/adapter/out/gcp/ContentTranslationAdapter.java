package com.elioo.healthcare.medicalreport.adapter.out.gcp;

import com.elioo.healthcare.gcp.translate.api.TranslationService;
import com.elioo.healthcare.gcp.translate.model.TranslationRequest;
import com.elioo.healthcare.gcp.translate.model.TranslationResponse;
import com.elioo.healthcare.medicalreport.application.port.out.ContentTranslationPort;
import com.elioo.healthcare.medicalreport.domain.Language;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * Google Cloud Translation implementation of ContentTranslationPort.
 *
 * <p>This adapter translates AI-generated medical content (clinical insights,
 * risk assessments, recommendations, educational content) between English and Bangla.</p>
 *
 * <p>Architecture: Outbound Adapter (Driven Adapter) in Hexagonal Architecture</p>
 *
 * <p><b>Key Features:</b></p>
 * <ul>
 *   <li>Translates JSON content while preserving structure</li>
 *   <li>Smart field detection - only translates text fields, not codes/scores</li>
 *   <li>Batch translation for efficiency</li>
 *   <li>Bidirectional: English ↔ Bangla</li>
 *   <li>Lenient error handling - returns original on failure</li>
 * </ul>
 *
 * @see ContentTranslationPort
 * @see TranslationService
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "gcp.translate.enabled", havingValue = "true", matchIfMissing = false)
public class ContentTranslationAdapter implements ContentTranslationPort {

    private final TranslationService translationService;
    private final ObjectMapper objectMapper;

    // Fields that should be translated for different content types
    private static final Set<String> TRANSLATABLE_FIELDS = Set.of(
        // Clinical insights fields
        "summary", "description", "explanation", "interpretation",
        "clinicalSignificance", "rationale", "finding", "recommendation",
        "timeframe", "overallAssessment", "actionPlan",
        // Risk assessment fields
        "riskFactors", "preventionStrategies",
        // Educational content fields
        "title", "content", "question", "answer", "keyTakeaways",
        "additionalResources", "text", "message"
    );

    // Fields that should NEVER be translated (codes, scores, identifiers)
    private static final Set<String> NON_TRANSLATABLE_FIELDS = Set.of(
        "id", "code", "codeSystem", "score", "confidence", "priority",
        "category", "severity", "status", "level", "type", "evidenceLevel",
        "riskLevel", "overallRiskLevel", "timestamp", "createdAt", "updatedAt"
    );

    @Override
    public Mono<String> translateClinicalInsights(String clinicalInsightsJson, Language targetLanguage) {
        log.info("[ContentTranslation] Translating clinical insights to {}", targetLanguage.getCode());
        return translateJsonContent(clinicalInsightsJson, targetLanguage, "CLINICAL_INSIGHTS");
    }

    @Override
    public Mono<String> translateRiskAssessment(String riskAssessmentJson, Language targetLanguage) {
        log.info("[ContentTranslation] Translating risk assessment to {}", targetLanguage.getCode());
        return translateJsonContent(riskAssessmentJson, targetLanguage, "RISK_ASSESSMENT");
    }

    @Override
    public Mono<String> translateRecommendations(String recommendationsJson, Language targetLanguage) {
        log.info("[ContentTranslation] Translating recommendations to {}", targetLanguage.getCode());
        return translateJsonContent(recommendationsJson, targetLanguage, "RECOMMENDATIONS");
    }

    @Override
    public Mono<String> translateEducationalContent(String educationalContentJson, Language targetLanguage) {
        log.info("[ContentTranslation] Translating educational content to {}", targetLanguage.getCode());
        return translateJsonContent(educationalContentJson, targetLanguage, "EDUCATIONAL_CONTENT");
    }

    @Override
    public Mono<String> translateChatResponse(String response, Language targetLanguage) {
        if (targetLanguage == Language.EN || response == null || response.isBlank()) {
            return Mono.just(response != null ? response : "");
        }

        log.info("[ContentTranslation] Translating chat response to {}: {} chars",
                targetLanguage.getCode(), response.length());

        return translateText(response, targetLanguage);
    }

    @Override
    public Mono<String> translateUserMessageToEnglish(String message) {
        if (message == null || message.isBlank()) {
            return Mono.just("");
        }

        log.info("[ContentTranslation] Checking if user message needs translation: {} chars", message.length());

        // Check if message contains non-English (likely Bangla) characters
        return containsBanglaCharacters(message)
            ? translationService.translate(
                    TranslationRequest.builder()
                        .text(message)
                        .sourceLanguage("bn")
                        .targetLanguage("en")
                        .preserveEnglish(true)
                        .build()
                )
                .map(TranslationResponse::getTranslatedText)
                .doOnSuccess(translated -> log.info("[ContentTranslation] User message translated to English"))
                .onErrorResume(e -> {
                    log.warn("[ContentTranslation] User message translation failed, using original: {}", e.getMessage());
                    return Mono.just(message);
                })
            : Mono.just(message);
    }

    @Override
    public Mono<String> translateContent(String contentJson, String contentType, Language targetLanguage) {
        return translateJsonContent(contentJson, targetLanguage, contentType);
    }

    @Override
    public Mono<String> translateText(String text, Language targetLanguage) {
        if (targetLanguage == Language.EN || text == null || text.isBlank()) {
            return Mono.just(text != null ? text : "");
        }

        // Translate from English to target language
        TranslationRequest request = TranslationRequest.builder()
            .text(text)
            .sourceLanguage("en")
            .targetLanguage(targetLanguage.getCode())
            .preserveEnglish(false)  // We want to translate English to target
            .build();

        return translationService.translate(request)
            .map(TranslationResponse::getTranslatedText)
            .doOnSuccess(translated -> log.debug("[ContentTranslation] Text translated: {} chars -> {} chars",
                    text.length(), translated.length()))
            .onErrorResume(e -> {
                log.warn("[ContentTranslation] Text translation failed, returning original: {}", e.getMessage());
                return Mono.just(text);
            });
    }

    // ==================== Private Helper Methods ====================

    /**
     * Translate JSON content by extracting text fields, batch translating them,
     * and reconstructing the JSON with translated values.
     */
    private Mono<String> translateJsonContent(String json, Language targetLanguage, String contentType) {
        if (targetLanguage == Language.EN) {
            log.debug("[ContentTranslation] Target is English, returning original");
            return Mono.just(json);
        }

        if (json == null || json.isBlank()) {
            log.warn("[ContentTranslation] Empty JSON provided for translation");
            return Mono.just("");
        }

        try {
            JsonNode rootNode = objectMapper.readTree(json);

            // Collect all translatable text fields with their paths
            List<TextLocation> textsToTranslate = new ArrayList<>();
            collectTranslatableTexts(rootNode, textsToTranslate, "");

            if (textsToTranslate.isEmpty()) {
                log.info("[ContentTranslation] No translatable fields found in {} content", contentType);
                return Mono.just(json);
            }

            log.info("[ContentTranslation] Found {} translatable fields in {} content",
                    textsToTranslate.size(), contentType);

            // Extract just the texts for batch translation
            List<String> texts = textsToTranslate.stream()
                .map(TextLocation::text)
                .toList();

            // Batch translate all texts
            return translateBatch(texts, targetLanguage)
                .collectList()
                .map(translations -> {
                    // Apply translations back to JSON
                    ObjectNode mutableRoot = rootNode.deepCopy();
                    for (int i = 0; i < textsToTranslate.size(); i++) {
                        applyTranslation(mutableRoot, textsToTranslate.get(i).path(), translations.get(i));
                    }
                    try {
                        return objectMapper.writeValueAsString(mutableRoot);
                    } catch (JsonProcessingException e) {
                        log.error("[ContentTranslation] Error serializing translated JSON", e);
                        return json;
                    }
                })
                .doOnSuccess(translated -> log.info("[ContentTranslation] {} content translated successfully", contentType))
                .onErrorResume(e -> {
                    log.error("[ContentTranslation] Translation failed for {}: {}", contentType, e.getMessage());
                    return Mono.just(json);
                });

        } catch (JsonProcessingException e) {
            log.error("[ContentTranslation] Failed to parse JSON for translation", e);
            return Mono.just(json);
        }
    }

    /**
     * Batch translate multiple texts from English to target language.
     */
    private Flux<String> translateBatch(List<String> texts, Language targetLanguage) {
        if (texts.isEmpty()) {
            return Flux.empty();
        }

        List<TranslationRequest> requests = texts.stream()
            .map(text -> TranslationRequest.builder()
                .text(text)
                .sourceLanguage("en")
                .targetLanguage(targetLanguage.getCode())
                .preserveEnglish(false)
                .build())
            .toList();

        return translationService.translateBatch(requests)
            .map(TranslationResponse::getTranslatedText);
    }

    /**
     * Recursively collect all translatable text fields from a JSON node.
     */
    private void collectTranslatableTexts(JsonNode node, List<TextLocation> results, String currentPath) {
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String fieldName = field.getKey();
                JsonNode fieldValue = field.getValue();
                String fieldPath = currentPath.isEmpty() ? fieldName : currentPath + "." + fieldName;

                if (fieldValue.isTextual() && isTranslatableField(fieldName, fieldValue.asText())) {
                    results.add(new TextLocation(fieldPath, fieldValue.asText()));
                } else if (fieldValue.isObject() || fieldValue.isArray()) {
                    collectTranslatableTexts(fieldValue, results, fieldPath);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (int i = 0; i < arrayNode.size(); i++) {
                String arrayPath = currentPath + "[" + i + "]";
                JsonNode element = arrayNode.get(i);
                if (element.isTextual() && !currentPath.isEmpty()) {
                    // Check if parent field is translatable
                    String parentField = getParentFieldName(currentPath);
                    if (isTranslatableFieldName(parentField)) {
                        results.add(new TextLocation(arrayPath, element.asText()));
                    }
                } else if (element.isObject() || element.isArray()) {
                    collectTranslatableTexts(element, results, arrayPath);
                }
            }
        }
    }

    /**
     * Check if a field should be translated based on its name and value.
     */
    private boolean isTranslatableField(String fieldName, String value) {
        // Skip non-translatable fields
        if (NON_TRANSLATABLE_FIELDS.contains(fieldName.toLowerCase())) {
            return false;
        }

        // Skip empty or very short values
        if (value == null || value.isBlank() || value.length() < 3) {
            return false;
        }

        // Skip values that look like codes, IDs, or enums
        if (value.matches("^[A-Z0-9_-]+$") || value.matches("^[a-z0-9-]+$")) {
            return false;
        }

        // Skip numeric values
        if (value.matches("^[\\d.,]+$")) {
            return false;
        }

        // Include if field name is in translatable list OR value looks like a sentence
        return isTranslatableFieldName(fieldName) || value.contains(" ");
    }

    /**
     * Check if field name suggests it contains translatable content.
     */
    private boolean isTranslatableFieldName(String fieldName) {
        String lowerName = fieldName.toLowerCase();
        return TRANSLATABLE_FIELDS.stream()
            .anyMatch(tf -> lowerName.contains(tf.toLowerCase()));
    }

    /**
     * Get parent field name from a JSON path.
     */
    private String getParentFieldName(String path) {
        if (path.contains("[")) {
            path = path.substring(0, path.lastIndexOf("["));
        }
        if (path.contains(".")) {
            path = path.substring(path.lastIndexOf(".") + 1);
        }
        return path;
    }

    /**
     * Apply a translation to a specific path in the JSON tree.
     */
    private void applyTranslation(ObjectNode root, String path, String translatedValue) {
        try {
            String[] parts = path.split("\\.");
            JsonNode current = root;

            for (int i = 0; i < parts.length - 1; i++) {
                String part = parts[i];
                if (part.contains("[")) {
                    String fieldName = part.substring(0, part.indexOf("["));
                    int index = Integer.parseInt(part.substring(part.indexOf("[") + 1, part.indexOf("]")));
                    current = current.get(fieldName).get(index);
                } else {
                    current = current.get(part);
                }
            }

            String lastPart = parts[parts.length - 1];
            if (lastPart.contains("[")) {
                String fieldName = lastPart.substring(0, lastPart.indexOf("["));
                int index = Integer.parseInt(lastPart.substring(lastPart.indexOf("[") + 1, lastPart.indexOf("]")));
                ((ArrayNode) current.get(fieldName)).set(index, new TextNode(translatedValue));
            } else {
                ((ObjectNode) current).put(lastPart, translatedValue);
            }
        } catch (Exception e) {
            log.warn("[ContentTranslation] Failed to apply translation at path {}: {}", path, e.getMessage());
        }
    }

    /**
     * Check if text contains Bangla Unicode characters.
     */
    private boolean containsBanglaCharacters(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (char c : text.toCharArray()) {
            // Bangla Unicode range: U+0980 to U+09FF
            if (c >= '\u0980' && c <= '\u09FF') {
                return true;
            }
        }
        return false;
    }

    /**
     * Record to track text location within JSON structure.
     */
    private record TextLocation(String path, String text) {}
}

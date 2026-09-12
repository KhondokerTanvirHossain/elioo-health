package com.elioo.healthcare.medicalreport.application.service;

import com.elioo.healthcare.medicalreport.application.port.in.MedicalReportChatUseCase;
import com.elioo.healthcare.medicalreport.application.port.out.ClinicalInsightPort;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalReportPersistencePort;
import com.elioo.healthcare.medicalreport.domain.ChatConversation;
import com.elioo.healthcare.medicalreport.domain.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service implementation for medical report chat operations.
 *
 * <p>Architecture: Application Service in Hexagonal Architecture</p>
 * <ul>
 *   <li>Implements {@link MedicalReportChatUseCase} inbound port</li>
 *   <li>Orchestrates chat workflow using outbound ports</li>
 *   <li>Maintains in-memory conversation cache (session-based)</li>
 *   <li>Enforces business rules (message limits, validation)</li>
 * </ul>
 *
 * <p>Key Responsibilities:</p>
 * <ul>
 *   <li>Manage conversation lifecycle (create, get, clear)</li>
 *   <li>Fetch report context from persistence layer</li>
 *   <li>Build comprehensive context for AI prompts</li>
 *   <li>Call clinical insight port for AI responses</li>
 *   <li>Enforce 50-message limit per conversation</li>
 *   <li>Handle errors gracefully</li>
 * </ul>
 *
 * <p>Storage Strategy:</p>
 * <ul>
 *   <li>In-memory cache using ConcurrentHashMap</li>
 *   <li>No database persistence (session-based)</li>
 *   <li>Conversations cleared on server restart</li>
 *   <li>Per-report conversations (reportId is key)</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MedicalReportChatService implements MedicalReportChatUseCase {

    private final ClinicalInsightPort clinicalInsightPort;
    private final MedicalReportPersistencePort persistencePort;

    /**
     * In-memory conversation cache.
     * Key: reportId
     * Value: ChatConversation
     *
     * <p>Thread-safe for concurrent access.</p>
     */
    private final ConcurrentHashMap<String, ChatConversation> conversationCache = new ConcurrentHashMap<>();

    @Override
    public Mono<ChatResponse> sendMessage(ChatRequest request) {
        log.info("Processing chat message for report: {}", request.reportId());

        return Mono.defer(() -> {
            // 1. Get or create conversation
            ChatConversation conversation = conversationCache.computeIfAbsent(
                    request.reportId(),
                    ChatConversation::empty
            );

            // 2. Validate message limit
            if (!conversation.canAddMessage()) {
                return Mono.error(new IllegalStateException(
                        "Message limit reached (" + ChatConversation.MAX_MESSAGES + " messages). " +
                                "Please clear the conversation to continue."
                ));
            }

            // 3. Create user message
            ChatMessage userMessage = ChatMessage.userMessage(request.message());

            // 4. Validate user message
            if (!userMessage.isValid()) {
                return Mono.error(new IllegalArgumentException("Invalid message content"));
            }

            // 5. Fetch report context and patient context in parallel
            Mono<Map<String, Object>> reportContextMono = fetchReportContext(request.reportId());
            Mono<ClinicalInsightPort.PatientContext> patientContextMono = fetchPatientContext(request.reportId());

            return Mono.zip(reportContextMono, patientContextMono)
                    .flatMap(tuple -> {
                        Map<String, Object> reportContext = tuple.getT1();
                        ClinicalInsightPort.PatientContext patientContext = tuple.getT2();

                        // 6. Get conversation history
                        List<ChatMessage> conversationHistory = conversation.messages();

                        // 7. Call AI with comprehensive context
                        return clinicalInsightPort.chatAboutReport(
                                        request.reportId(),
                                        request.message(),
                                        conversationHistory,
                                        reportContext,
                                        patientContext
                                )
                                .map(aiResponse -> {
                                    // 8. Create assistant message
                                    ChatMessage assistantMessage = ChatMessage.assistantMessage(aiResponse);

                                    // 9. Update conversation with both messages
                                    ChatConversation updatedConversation = conversation
                                            .withNewMessage(userMessage)
                                            .withNewMessage(assistantMessage);

                                    // 10. Store updated conversation in cache
                                    conversationCache.put(request.reportId(), updatedConversation);

                                    // 11. Return response
                                    return new ChatResponse(
                                            request.reportId(),
                                            userMessage,
                                            assistantMessage,
                                            updatedConversation.remainingMessages(),
                                            LocalDateTime.now()
                                    );
                                });
                    })
                    .doOnSuccess(response -> log.info(
                            "Chat message processed for report: {}, remaining messages: {}",
                            request.reportId(), response.remainingMessages()
                    ))
                    .doOnError(error -> log.error(
                            "Error processing chat message for report: {}",
                            request.reportId(), error
                    ));
        });
    }

    @Override
    public Mono<ChatConversation> getConversation(String reportId) {
        log.debug("Getting conversation for report: {}", reportId);

        return Mono.fromSupplier(() ->
                conversationCache.getOrDefault(reportId, ChatConversation.empty(reportId))
        );
    }

    @Override
    public Mono<Void> clearConversation(String reportId) {
        log.info("Clearing conversation for report: {}", reportId);

        return Mono.fromRunnable(() -> {
            ChatConversation removed = conversationCache.remove(reportId);
            if (removed != null) {
                log.info("Cleared conversation with {} messages for report: {}",
                        removed.messageCount(), reportId);
            } else {
                log.debug("No conversation found to clear for report: {}", reportId);
            }
        });
    }

    // ========================================
    // Helper Methods
    // ========================================

    /**
     * Fetch complete report context from persistence layer.
     *
     * <p>Includes all available data:</p>
     * <ul>
     *   <li>OCR results (extracted test data)</li>
     *   <li>Classification results (medical entities)</li>
     *   <li>ICD-10 codes</li>
     *   <li>RxNorm codes</li>
     *   <li>Clinical insights</li>
     *   <li>Risk assessment</li>
     *   <li>Recommendations</li>
     *   <li>Educational content</li>
     * </ul>
     *
     * @param reportId Report identifier
     * @return Mono containing report context map
     */
    private Mono<Map<String, Object>> fetchReportContext(String reportId) {
        log.debug("Fetching report context for report: {}", reportId);

        return Mono.zip(
                        // Fetch OCR results
                        persistencePort.findResultByType(reportId, "OCR")
                                .map(r -> r.resultData() != null ? r.resultData() : (Object) Map.of())
                                .onErrorResume(e -> { log.warn("Chat context: skipping unreadable result ({})", e.getMessage()); return Mono.empty(); })
                                .defaultIfEmpty(Map.of()),

                        // Fetch classification results
                        persistencePort.findResultByType(reportId, "CLASSIFICATION")
                                .map(r -> r.resultData() != null ? r.resultData() : (Object) Map.of())
                                .onErrorResume(e -> { log.warn("Chat context: skipping unreadable result ({})", e.getMessage()); return Mono.empty(); })
                                .defaultIfEmpty(Map.of()),

                        // Fetch ICD-10 codes
                        persistencePort.findResultByType(reportId, "ICD10")
                                .map(r -> r.resultData() != null ? r.resultData() : (Object) List.of())
                                .onErrorResume(e -> { log.warn("Chat context: skipping unreadable result ({})", e.getMessage()); return Mono.empty(); })
                                .defaultIfEmpty(List.of()),

                        // Fetch RxNorm codes
                        persistencePort.findResultByType(reportId, "RXNORM")
                                .map(r -> r.resultData() != null ? r.resultData() : (Object) List.of())
                                .onErrorResume(e -> { log.warn("Chat context: skipping unreadable result ({})", e.getMessage()); return Mono.empty(); })
                                .defaultIfEmpty(List.of()),

                        // Fetch clinical insights
                        persistencePort.findResultByType(reportId, "CLINICAL_INSIGHTS")
                                .map(r -> r.resultData() != null ? r.resultData() : (Object) Map.of())
                                .onErrorResume(e -> { log.warn("Chat context: skipping unreadable result ({})", e.getMessage()); return Mono.empty(); })
                                .defaultIfEmpty(Map.of()),

                        // Fetch risk assessment
                        persistencePort.findResultByType(reportId, "RISK_ASSESSMENT")
                                .map(r -> r.resultData() != null ? r.resultData() : (Object) Map.of())
                                .onErrorResume(e -> { log.warn("Chat context: skipping unreadable result ({})", e.getMessage()); return Mono.empty(); })
                                .defaultIfEmpty(Map.of()),

                        // Fetch recommendations
                        persistencePort.findResultByType(reportId, "RECOMMENDATIONS")
                                .map(r -> r.resultData() != null ? r.resultData() : (Object) List.of())
                                .onErrorResume(e -> { log.warn("Chat context: skipping unreadable result ({})", e.getMessage()); return Mono.empty(); })
                                .defaultIfEmpty(List.of()),

                        // Fetch educational content
                        persistencePort.findResultByType(reportId, "EDUCATIONAL_CONTENT")
                                .map(r -> r.resultData() != null ? r.resultData() : (Object) Map.of())
                                .onErrorResume(e -> { log.warn("Chat context: skipping unreadable result ({})", e.getMessage()); return Mono.empty(); })
                                .defaultIfEmpty(Map.of())
                )
                .map(tuple -> {
                    Map<String, Object> context = new HashMap<>();
                    context.put("ocrResults", tuple.getT1());
                    context.put("classificationResults", tuple.getT2());
                    context.put("icd10Codes", tuple.getT3());
                    context.put("rxnormCodes", tuple.getT4());
                    context.put("clinicalInsights", tuple.getT5());
                    context.put("riskAssessment", tuple.getT6());
                    context.put("recommendations", tuple.getT7());
                    context.put("educationalContent", tuple.getT8());
                    return context;
                })
                .doOnSuccess(context -> log.debug(
                        "Fetched report context for report: {}, keys: {}",
                        reportId, context.keySet()
                ))
                .onErrorResume(error -> {
                    log.error("Error fetching report context for report: {}", reportId, error);
                    return Mono.just(Map.of());
                });
    }

    /**
     * Fetch patient context from persistence layer.
     *
     * @param reportId Report identifier
     * @return Mono containing patient context
     */
    private Mono<ClinicalInsightPort.PatientContext> fetchPatientContext(String reportId) {
        log.debug("Fetching patient context for report: {}", reportId);

        return persistencePort.findProcessByReportId(reportId)
                .switchIfEmpty(Mono.error(new java.util.NoSuchElementException("Report not found: " + reportId)))
                .map(processRecord -> {
                    // Parse patient context JSON string
                    String patientContextJson = processRecord.patientContextJson();

                    if (patientContextJson == null || patientContextJson.isBlank()) {
                        log.warn("No patient context JSON found for report: {}", reportId);
                        return createDefaultPatientContext();
                    }

                    try {
                        // Parse JSON to Map
                        @SuppressWarnings("unchecked")
                        Map<String, Object> contextMap = new com.fasterxml.jackson.databind.ObjectMapper()
                                .readValue(patientContextJson, Map.class);

                        // Extract fields with safe casting
                        String patientId = getStringValue(contextMap, "patientId");
                        Integer age = getIntegerValue(contextMap, "age");
                        String gender = getStringValue(contextMap, "gender");

                        @SuppressWarnings("unchecked")
                        List<String> medicalHistory = (List<String>) contextMap.getOrDefault("medicalHistory", List.of());

                        @SuppressWarnings("unchecked")
                        List<String> currentMedications = (List<String>) contextMap.getOrDefault("currentMedications", List.of());

                        @SuppressWarnings("unchecked")
                        List<String> allergies = (List<String>) contextMap.getOrDefault("allergies", List.of());

                        @SuppressWarnings("unchecked")
                        Map<String, Object> vitalSigns = (Map<String, Object>) contextMap.getOrDefault("vitalSigns", Map.of());

                        @SuppressWarnings("unchecked")
                        Map<String, Object> lifestyle = (Map<String, Object>) contextMap.getOrDefault("lifestyle", Map.of());

                        return new ClinicalInsightPort.PatientContext(
                                patientId,
                                age,
                                gender,
                                medicalHistory != null ? medicalHistory : List.of(),
                                currentMedications != null ? currentMedications : List.of(),
                                allergies != null ? allergies : List.of(),
                                vitalSigns != null ? vitalSigns : Map.of(),
                                lifestyle != null ? lifestyle : Map.of()
                        );
                    } catch (Exception e) {
                        log.error("Error parsing patient context JSON for report: {}", reportId, e);
                        return createDefaultPatientContext();
                    }
                })
                .doOnSuccess(context -> log.debug(
                        "Fetched patient context for report: {}, patientId: {}",
                        reportId, context.patientId()
                ))
                .onErrorResume(error -> {
                    if (error instanceof java.util.NoSuchElementException) {
                        return Mono.error(error); // unknown report: surface as 404, do not fake a patient
                    }
                    log.error("Error fetching patient context for report: {}", reportId, error);
                    return Mono.just(createDefaultPatientContext());
                });
    }

    /**
     * Create default patient context with empty values.
     */
    private ClinicalInsightPort.PatientContext createDefaultPatientContext() {
        return new ClinicalInsightPort.PatientContext(
                null, null, null, List.of(), List.of(), List.of(), Map.of(), Map.of()
        );
    }

    /**
     * Safely extract string value from map.
     */
    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * Safely extract integer value from map.
     */
    private Integer getIntegerValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            log.warn("Failed to parse integer value for key: {}", key);
            return null;
        }
    }
}

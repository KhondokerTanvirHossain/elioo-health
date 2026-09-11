package com.elioo.healthcare.medicalreport.adapter.in.handler;

import com.elioo.healthcare.medicalreport.application.port.in.ContentTranslationUseCase;
import com.elioo.healthcare.medicalreport.application.port.in.MedicalReportChatUseCase;
import com.elioo.healthcare.medicalreport.domain.ChatMessage;
import com.elioo.healthcare.medicalreport.domain.Language;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * HTTP handler for medical report chat endpoints.
 *
 * <p>Architecture: Inbound Adapter (Web) in Hexagonal Architecture</p>
 * <ul>
 *   <li>Handles HTTP requests for chat operations</li>
 *   <li>Delegates to {@link MedicalReportChatUseCase} for business logic</li>
 *   <li>Maps HTTP requests/responses to domain objects</li>
 *   <li>Handles validation and error responses</li>
 * </ul>
 *
 * <p>Endpoints Handled:</p>
 * <ul>
 *   <li>POST /api/v1/medical-report/chat/{reportId}/send - Send chat message</li>
 *   <li>GET /api/v1/medical-report/chat/{reportId}/messages - Get conversation history</li>
 *   <li>DELETE /api/v1/medical-report/chat/{reportId}/clear - Clear conversation</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MedicalReportChatHandler {

    private final MedicalReportChatUseCase chatUseCase;
    private final ContentTranslationUseCase translationUseCase;

    /**
     * POST /api/v1/medical-report/chat/{reportId}/send
     * Send a chat message and get AI response.
     *
     * <p>Request Body:</p>
     * <pre>
     * {
     *   "message": "What does my creatinine level mean?"
     * }
     * </pre>
     *
     * <p>Response Body:</p>
     * <pre>
     * {
     *   "reportId": "RPT-123",
     *   "userMessage": {
     *     "id": "msg-456",
     *     "role": "USER",
     *     "content": "What does my creatinine level mean?",
     *     "timestamp": "2024-01-15T10:30:00Z",
     *     "metadata": {}
     *   },
     *   "assistantMessage": {
     *     "id": "msg-789",
     *     "role": "ASSISTANT",
     *     "content": "Your creatinine level is...",
     *     "timestamp": "2024-01-15T10:30:05Z",
     *     "metadata": {}
     *   },
     *   "remainingMessages": 48,
     *   "timestamp": "2024-01-15T10:30:05Z"
     * }
     * </pre>
     */
    public Mono<ServerResponse> sendMessage(ServerRequest request) {
        String reportId = request.pathVariable("reportId");

        log.debug("Received chat message request for report: {}", reportId);

        return request.bodyToMono(SendMessageRequest.class)
                .flatMap(body -> {
                    // Validate request body
                    if (body.message() == null || body.message().isBlank()) {
                        return ServerResponse.badRequest()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(Map.of("error", "Message is required"));
                    }

                    // Get language from request (default to English)
                    Language targetLanguage = Language.fromCode(body.language());
                    log.debug("Chat language: {}", targetLanguage.getCode());

                    // Step 1: Translate user message to English if needed
                    return translationUseCase.translateUserMessageToEnglish(body.message())
                            .flatMap(englishMessage -> {
                                log.debug("User message (English): {}",
                                    englishMessage.length() > 50 ? englishMessage.substring(0, 50) + "..." : englishMessage);

                                // Step 2: Create use case request with English message
                                MedicalReportChatUseCase.ChatRequest chatRequest =
                                        new MedicalReportChatUseCase.ChatRequest(reportId, englishMessage);

                                // Step 3: Get AI response
                                return chatUseCase.sendMessage(chatRequest)
                                        .flatMap(response -> {
                                            // Step 4: Translate AI response to target language if not English
                                            if (targetLanguage != Language.EN && response.assistantMessage() != null) {
                                                return translationUseCase.translateChatResponse(
                                                        response.assistantMessage().content(),
                                                        targetLanguage
                                                ).map(translatedContent -> {
                                                    // Create new ChatMessage with translated content
                                                    ChatMessage translatedAssistantMessage = new ChatMessage(
                                                            response.assistantMessage().id(),
                                                            response.assistantMessage().role(),
                                                            translatedContent,
                                                            response.assistantMessage().timestamp(),
                                                            response.assistantMessage().metadata()
                                                    );
                                                    return new MedicalReportChatUseCase.ChatResponse(
                                                            response.reportId(),
                                                            response.userMessage(),
                                                            translatedAssistantMessage,
                                                            response.remainingMessages(),
                                                            response.timestamp()
                                                    );
                                                });
                                            }
                                            return Mono.just(response);
                                        });
                            });
                })
                .flatMap(response -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(response))
                .onErrorResume(this::handleError);
    }

    /**
     * GET /api/v1/medical-report/chat/{reportId}/messages
     * Get conversation history for a report.
     *
     * <p>Response Body:</p>
     * <pre>
     * {
     *   "reportId": "RPT-123",
     *   "messages": [
     *     {
     *       "id": "msg-1",
     *       "role": "USER",
     *       "content": "What is my creatinine level?",
     *       "timestamp": "2024-01-15T10:25:00Z",
     *       "metadata": {}
     *     },
     *     {
     *       "id": "msg-2",
     *       "role": "ASSISTANT",
     *       "content": "Your creatinine level is 135 µmol/L...",
     *       "timestamp": "2024-01-15T10:25:05Z",
     *       "metadata": {}
     *     }
     *   ],
     *   "messageCount": 2,
     *   "lastUpdatedAt": "2024-01-15T10:25:05Z"
     * }
     * </pre>
     */
    public Mono<ServerResponse> getMessages(ServerRequest request) {
        String reportId = request.pathVariable("reportId");

        log.debug("Received get messages request for report: {}", reportId);

        return chatUseCase.getConversation(reportId)
                .flatMap(conversation -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(conversation))
                .switchIfEmpty(ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of(
                                "reportId", reportId,
                                "messages", java.util.List.of(),
                                "messageCount", 0,
                                "lastUpdatedAt", java.time.LocalDateTime.now()
                        )))
                .onErrorResume(this::handleError);
    }

    /**
     * DELETE /api/v1/medical-report/chat/{reportId}/clear
     * Clear conversation history for a report.
     *
     * <p>Response: 204 No Content</p>
     */
    public Mono<ServerResponse> clearConversation(ServerRequest request) {
        String reportId = request.pathVariable("reportId");

        log.debug("Received clear conversation request for report: {}", reportId);

        return chatUseCase.clearConversation(reportId)
                .then(ServerResponse.noContent().build())
                .onErrorResume(this::handleError);
    }

    /**
     * Handle errors and return appropriate HTTP response.
     */
    private Mono<ServerResponse> handleError(Throwable error) {
        log.error("Error in chat handler", error);

        // Message limit reached
        if (error instanceof IllegalStateException && error.getMessage().contains("Message limit reached")) {
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "error", "MESSAGE_LIMIT_REACHED",
                            "message", error.getMessage()
                    ));
        }

        // Invalid request
        if (error instanceof IllegalArgumentException) {
            return ServerResponse.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "error", "INVALID_REQUEST",
                            "message", error.getMessage()
                    ));
        }

        // Generic server error
        return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "error", "INTERNAL_SERVER_ERROR",
                        "message", "An error occurred processing your request. Please try again."
                ));
    }

    /**
     * Request DTO for sending a chat message.
     *
     * @param message The user's chat message
     * @param language Target language code (e.g., "en" for English, "bn" for Bangla)
     */
    record SendMessageRequest(String message, String language) {}
}

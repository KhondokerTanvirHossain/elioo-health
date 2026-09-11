package com.elioo.healthcare.medicalreport.application.port.in;

import com.elioo.healthcare.medicalreport.domain.ChatConversation;
import com.elioo.healthcare.medicalreport.domain.ChatMessage;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Inbound port (use case interface) for medical report chat operations.
 *
 * <p>Architecture: Inbound Port in Hexagonal Architecture</p>
 * <ul>
 *   <li>Defines what the application can do with chat conversations</li>
 *   <li>Independent of implementation details (web, messaging, etc.)</li>
 *   <li>Used by inbound adapters (handlers) to interact with business logic</li>
 * </ul>
 *
 * <p>Chat System Design:</p>
 * <ul>
 *   <li>Session-based (in-memory, no database persistence)</li>
 *   <li>Per-report conversations (tied to specific reportId)</li>
 *   <li>50 message limit per conversation</li>
 *   <li>Context-aware AI responses (includes patient + report data)</li>
 * </ul>
 */
public interface MedicalReportChatUseCase {

    /**
     * Send a chat message about a medical report and get AI response.
     *
     * <p>This is the main chat interaction method. It:</p>
     * <ol>
     *   <li>Validates the message and report</li>
     *   <li>Checks message limit (50 max)</li>
     *   <li>Fetches report context (OCR, classification, insights)</li>
     *   <li>Builds conversation history (last 10 messages)</li>
     *   <li>Calls AI with comprehensive prompt</li>
     *   <li>Stores response in session cache</li>
     *   <li>Returns both user and assistant messages</li>
     * </ol>
     *
     * @param request Chat request with reportId and user message
     * @return Mono containing chat response with assistant message
     * @throws IllegalStateException if message limit reached
     * @throws IllegalArgumentException if message is invalid
     */
    Mono<ChatResponse> sendMessage(ChatRequest request);

    /**
     * Get conversation history for a report (session-based).
     *
     * <p>Returns all messages in the current conversation. If no conversation
     * exists, returns an empty conversation.</p>
     *
     * @param reportId Report identifier
     * @return Mono containing conversation with all messages
     */
    Mono<ChatConversation> getConversation(String reportId);

    /**
     * Clear conversation history for a report.
     *
     * <p>Removes the conversation from the session cache. This action cannot
     * be undone since conversations are not persisted to database.</p>
     *
     * @param reportId Report identifier
     * @return Mono indicating completion
     */
    Mono<Void> clearConversation(String reportId);

    // ========================================
    // DTOs
    // ========================================

    /**
     * Request to send a chat message.
     *
     * @param reportId The medical report this question is about
     * @param message User's question (max 1000 characters)
     */
    record ChatRequest(
            String reportId,
            String message
    ) {
        public ChatRequest {
            if (reportId == null || reportId.isBlank()) {
                throw new IllegalArgumentException("Report ID is required");
            }
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("Message is required");
            }
            if (message.length() > 1000) {
                throw new IllegalArgumentException("Message too long (max 1000 characters)");
            }
        }
    }

    /**
     * Response containing the chat interaction result.
     *
     * @param reportId The medical report this conversation is about
     * @param userMessage The user's question
     * @param assistantMessage The AI's response
     * @param remainingMessages Number of messages left before hitting limit
     * @param timestamp When the interaction occurred
     */
    record ChatResponse(
            String reportId,
            ChatMessage userMessage,
            ChatMessage assistantMessage,
            int remainingMessages,
            LocalDateTime timestamp
    ) {}
}

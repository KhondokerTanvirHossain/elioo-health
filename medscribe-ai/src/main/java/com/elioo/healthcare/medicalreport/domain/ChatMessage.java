package com.elioo.healthcare.medicalreport.domain;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Chat message value object representing a single message in a conversation.
 *
 * <p>Immutable record representing either a user question or AI assistant response
 * in a medical report conversation context.</p>
 *
 * @param id Unique message identifier
 * @param role Message role - "USER" or "ASSISTANT"
 * @param content Message text content
 * @param timestamp When the message was created
 * @param metadata Additional metadata (e.g., confidence scores, processing time)
 */
public record ChatMessage(
        String id,
        String role,
        String content,
        LocalDateTime timestamp,
        Map<String, Object> metadata
) {
    /**
     * Create a new user message.
     */
    public static ChatMessage userMessage(String content) {
        return new ChatMessage(
                UUID.randomUUID().toString(),
                "USER",
                content,
                LocalDateTime.now(),
                Map.of()
        );
    }

    /**
     * Create a new assistant message.
     */
    public static ChatMessage assistantMessage(String content) {
        return new ChatMessage(
                UUID.randomUUID().toString(),
                "ASSISTANT",
                content,
                LocalDateTime.now(),
                Map.of()
        );
    }

    /**
     * Create assistant message with metadata.
     */
    public static ChatMessage assistantMessage(String content, Map<String, Object> metadata) {
        return new ChatMessage(
                UUID.randomUUID().toString(),
                "ASSISTANT",
                content,
                LocalDateTime.now(),
                metadata
        );
    }

    /**
     * Check if this is a user message.
     */
    public boolean isUserMessage() {
        return "USER".equalsIgnoreCase(role);
    }

    /**
     * Check if this is an assistant message.
     */
    public boolean isAssistantMessage() {
        return "ASSISTANT".equalsIgnoreCase(role);
    }

    /**
     * Validate message content.
     */
    public boolean isValid() {
        return content != null && !content.isBlank() && content.length() <= 1000;
    }
}

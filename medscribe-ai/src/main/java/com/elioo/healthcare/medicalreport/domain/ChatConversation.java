package com.elioo.healthcare.medicalreport.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Chat conversation aggregate representing the complete conversation for a medical report.
 *
 * <p>Immutable record that manages the collection of messages and enforces business rules
 * like message limits. Each conversation is tied to a specific report ID.</p>
 *
 * @param reportId The medical report this conversation is about
 * @param messages List of all messages in the conversation (chronological order)
 * @param messageCount Current number of messages in the conversation
 * @param lastUpdatedAt Timestamp of the last message added
 */
public record ChatConversation(
        String reportId,
        List<ChatMessage> messages,
        int messageCount,
        LocalDateTime lastUpdatedAt
) {
    /**
     * Maximum number of messages allowed per conversation.
     * This limit prevents excessive context length and controls token costs.
     */
    public static final int MAX_MESSAGES = 50;

    /**
     * Create a new empty conversation for a report.
     */
    public static ChatConversation empty(String reportId) {
        return new ChatConversation(
                reportId,
                new ArrayList<>(),
                0,
                LocalDateTime.now()
        );
    }

    /**
     * Check if more messages can be added to this conversation.
     * @return true if message count is below the maximum limit
     */
    public boolean canAddMessage() {
        return messageCount < MAX_MESSAGES;
    }

    /**
     * Get the number of remaining messages before hitting the limit.
     * @return number of messages that can still be added
     */
    public int remainingMessages() {
        return MAX_MESSAGES - messageCount;
    }

    /**
     * Create a new conversation with an additional message.
     * This follows the immutable pattern - returns a new instance.
     *
     * @param message The message to add
     * @return New ChatConversation instance with the message added
     * @throws IllegalStateException if message limit has been reached
     */
    public ChatConversation withNewMessage(ChatMessage message) {
        if (!canAddMessage()) {
            throw new IllegalStateException(
                    "Cannot add message: conversation has reached maximum limit of " + MAX_MESSAGES + " messages"
            );
        }

        List<ChatMessage> updatedMessages = new ArrayList<>(messages);
        updatedMessages.add(message);

        return new ChatConversation(
                reportId,
                updatedMessages,
                messageCount + 1,
                LocalDateTime.now()
        );
    }

    /**
     * Get the last N messages from the conversation.
     * Useful for building context prompts with limited history.
     *
     * @param count Number of recent messages to retrieve
     * @return List of recent messages (may be fewer than count if conversation is short)
     */
    public List<ChatMessage> getLastMessages(int count) {
        if (messages.isEmpty()) {
            return List.of();
        }

        int startIndex = Math.max(0, messages.size() - count);
        return messages.subList(startIndex, messages.size());
    }

    /**
     * Check if this conversation is empty (no messages).
     */
    public boolean isEmpty() {
        return messageCount == 0;
    }

    /**
     * Check if this conversation is approaching the message limit.
     * @return true if less than 10 messages remain
     */
    public boolean isNearLimit() {
        return remainingMessages() < 10;
    }

    /**
     * Validate conversation state.
     * @return true if conversation is in a valid state
     */
    public boolean isValid() {
        return reportId != null
                && !reportId.isBlank()
                && messageCount >= 0
                && messageCount <= MAX_MESSAGES
                && messages.size() == messageCount;
    }
}

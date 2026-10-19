package com.elioo.baymax.outbound.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;

/**
 * The one encoding of urgency reasons, shared by {@code outbound_message.urgency_reasons} (text) and
 * {@code document.urgency_reasons} (jsonb). Moved unchanged from PostgresOutboundMessageAdapter (PR 5).
 */
public final class UrgencyReasonsJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private UrgencyReasonsJson() {
    }

    /**
     * F4: reasons are stored as a JSON array. They embed marker names, and marker names carry commas
     * ({@code value_critical:cholesterol, total|stopgap}); the comma-joined form split one reason into two on read,
     * so a cache keyed on reasons could never hit against the real database.
     */
    public static String encode(List<String> reasons) {
        try {
            return JSON.writeValueAsString(reasons == null ? List.of() : reasons);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("urgency reasons are not encodable", e);
        }
    }

    /** A JSON array (written from F4 on); anything else is the legacy comma-joined form and reads as it always did. */
    public static List<String> decode(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        if (stored.startsWith("[")) {
            try {
                return List.copyOf(JSON.readValue(stored, new TypeReference<List<String>>() { }));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("urgency reasons are not a JSON array of strings", e);
            }
        }
        return Arrays.asList(stored.split(","));
    }
}

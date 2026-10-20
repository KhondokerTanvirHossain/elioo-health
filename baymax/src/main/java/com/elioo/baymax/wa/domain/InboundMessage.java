package com.elioo.baymax.wa.domain;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A row of {@code baymax.inbound_message} (F9a). No raw provider id and no phone number ever reach it: the key is an HMAC
 * of the wamid, and text is kept only for a sender who resolved to a family (the only text that can be deleted on
 * request), never when it is a six-digit code.
 *
 * @param providerKey HMAC-SHA256 hex of "wamid:" || the provider id (PhoneHasher.providerId)
 * @param outcome     the WaIntakeService.Outcome name once handled; null while received and unanswered
 */
public record InboundMessage(UUID id, String providerKey, UUID familyId, UUID patientId, UUID documentId,
                             String type, String body, String mediaId, Instant providerTimestamp,
                             Instant receivedAt, Instant handledAt, String outcome) {

    /** WhatsApp's text limit is 4096 characters; anything longer is not a WhatsApp text. */
    public static final int MAX_BODY = 4096;
    /** A login code as a family might paste it back: six ASCII or Bangla digits, alone, whitespace around it. */
    private static final Pattern LOGIN_CODE = Pattern.compile("^\\s*[0-9০-৯]{6}\\s*$");

    /** The privacy rules in code as well as in the CHECK: no family, no text; never a six-digit code. */
    public static InboundMessage received(String providerKey, UUID familyId, String type, String body,
                                          String mediaId, Instant providerTimestamp) {
        String kept = familyId == null || body == null || body.isEmpty() || LOGIN_CODE.matcher(body).matches() ? null
                : body.length() > MAX_BODY ? body.substring(0, MAX_BODY) : body;
        String media = mediaId == null || mediaId.isBlank() ? null : mediaId;
        return new InboundMessage(null, providerKey, familyId, null, null, type, kept, media,
                providerTimestamp, null, null, null);
    }
}

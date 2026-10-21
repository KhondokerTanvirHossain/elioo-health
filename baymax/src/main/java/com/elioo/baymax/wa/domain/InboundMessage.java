package com.elioo.baymax.wa.domain;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A row of {@code baymax.inbound_message} (F9a). No raw provider id and no phone number ever reach it: the key is an HMAC
 * of the wamid. Text and the media id are kept only for a sender who resolved to a family (the only rows a delete on
 * request can reach), and text is never kept when it carries a six-digit run (a login code, bare, with a word in front
 * of it, or the whole code message forwarded back).
 *
 * @param providerKey HMAC-SHA256 hex of "wamid:" || the provider id (PhoneHasher.providerId)
 * @param outcome     the WaIntakeService.Outcome name once handled, {@link #CUT_OFF} when a restart closed it unanswered;
 *                    null while received and in flight
 */
public record InboundMessage(UUID id, String providerKey, UUID familyId, UUID patientId, UUID documentId,
                             String type, String body, String mediaId, Instant providerTimestamp,
                             Instant receivedAt, Instant handledAt, String outcome) {

    /** WhatsApp's text limit is 4096 characters; anything longer is not a WhatsApp text. */
    public static final int MAX_BODY = 4096;
    /**
     * The outcome a startup writes on a message an earlier instance claimed and never answered: counted once, then
     * closed, and still reclaimable if Meta re-delivers it.
     */
    public static final String CUT_OFF = "CUT_OFF";
    /**
     * Six ASCII or Bangla digits standing alone (no digit on either side): a login code however it is wrapped. Found,
     * not matched, so "কোড 483920" and the forwarded wa.login_code message are caught; "4839201" is not a code.
     */
    private static final Pattern SIX_DIGIT_RUN = Pattern.compile("(?<![0-9০-৯])[0-9০-৯]{6}(?![0-9০-৯])");

    /** The privacy rules in code as well as in the CHECK: no family, no text and no media id; never a six-digit run. */
    public static InboundMessage received(String providerKey, UUID familyId, String type, String body,
                                          String mediaId, Instant providerTimestamp) {
        String kept = familyId == null || body == null || body.isEmpty() || SIX_DIGIT_RUN.matcher(body).find() ? null
                : body.length() > MAX_BODY ? body.substring(0, MAX_BODY) : body;
        String media = familyId == null || mediaId == null || mediaId.isBlank() ? null : mediaId;
        return new InboundMessage(null, providerKey, familyId, null, null, type, kept, media,
                providerTimestamp, null, null, null);
    }
}

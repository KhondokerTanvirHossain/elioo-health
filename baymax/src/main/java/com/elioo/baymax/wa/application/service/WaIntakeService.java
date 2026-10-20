package com.elioo.baymax.wa.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.application.service.BanglaDate;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.outbound.application.service.UrgencyLines;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import com.elioo.baymax.wa.domain.WaInbound;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Answers every inbound WhatsApp message exactly once (Phase 0) and turns an inbound image or PDF into a document on
 * the family's timeline (BMX-10 phase 1). Text is routed by {@link WaTextRouter}, whole message only, with zero model
 * calls: greeting, the detail words, thanks, and an honest fallback that carries the red-flag line.
 *
 * <p>Resolution is sender → family → patient. Every branch that cannot reach {@code accept} answers the family
 * on WhatsApp rather than only the log: <b>a photo that vanishes in silence is the worst outcome of the
 * three</b>, worse than a refusal that says why.
 *
 * <p><b>Ambiguity is refused, never guessed.</b> §1.4 says one number carries many patients and every message
 * must be able to ask "Ma or Baba?"; phase 1 has no such conversation, so a family with more than one patient
 * gets a reply saying Medioo cannot tell whose report this is. Picking the first would misfile one parent's
 * report under the other, and the timeline, the trend series and every nudge built on it would then be wrong
 * and internally consistent — the failure mode this repository keeps finding.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "baymax.wa", name = "enabled", havingValue = "true")
public class WaIntakeService {

    private final HealthRecordPort records;
    private final com.elioo.baymax.extraction.application.port.out.DocumentRecordPort documents;
    private final com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase explainer;
    private final DocumentIntakeUseCase intake;
    private final WaMessagingPort graph;
    private final Copy copy;
    private final BaymaxProperties properties;
    private final com.elioo.baymax.web.application.port.in.WebAuthUseCase auth;
    /** The standing urgency of the latest paper, read in any gate state (plan PR 7, D5). */
    private final com.elioo.baymax.outbound.application.port.out.OutboundMessagePort messages;
    private final java.time.Clock clock;
    /** Checked before the "got it" ack, so a family at the cap hears one true message (W15). */
    private final com.elioo.baymax.family.application.port.in.FreeTierUseCase freeTier;

    /** Why an inbound image did not become a document; each carries the reply the family receives. */
    public enum Outcome {
        ACCEPTED, UNKNOWN_SENDER, AMBIGUOUS_PATIENT, FREE_TIER, FAILED,
        LOGIN_CODE,
        /** A fixed-copy reply was sent (greeting, thanks, fallback, voice, video, no document yet). */
        REPLIED,
        /** Nothing the family sent, so nothing to answer: a Meta system notice, or a reaction being removed. */
        IGNORED
    }

    /**
     * The outer bound on a read receipt, for any port: it runs in front of the 24 h window record and the reply, and a
     * call that stalls is not an error onErrorResume can see. Longer than WaGraphClient's own bound, so that one fires
     * (and logs its status) first.
     */
    static final java.time.Duration READ_RECEIPT_BOUND = java.time.Duration.ofSeconds(3);

    /** What the intake can read: a PDF or a JPEG/PNG photo sent as a file (PageRenderer decides by magic bytes). */
    private static final Set<String> READABLE_FILES = Set.of("application/pdf", "image/jpeg", "image/png");

    /**
     * The one entry point from the webhook. Every message a family sends gets exactly one reply (Phase 0); the only
     * silences are the two events that carry nothing from the family ({@link WaInbound#expectsReply}). The switch is
     * exhaustive over Type, so a new Type does not compile without a reply.
     */
    public Mono<Outcome> onInbound(WaInbound in) {
        if (!in.expectsReply()) {
            log.info("[baymax] wa inbound needs no reply type={}", in.type());
            return Mono.just(Outcome.IGNORED);
        }
        String number = e164(in.from());
        // DR-39 (2026-10-18): every message the family sends opens WhatsApp's free 24 h window, in which a web sign-in
        // code may be sent automatically. Recorded first, at Meta's send time (a retried callback arrives late); a
        // failure here is logged and never costs the family a reply.
        // The read receipt goes first so the ticks (and, for a slow answer, the dots) appear before the work starts. It is
        // bounded (READ_RECEIPT_BOUND): a stalled courtesy call must not hold back the window record or the reply.
        return readReceipt(in, number)
                .then(Mono.defer(() -> auth.noteInbound(number, in.timestamp())
                        .onErrorResume(e -> {
                            log.warn("[baymax] wa inbound window not recorded error={}", e.getClass().getSimpleName());
                            return Mono.empty();
                        })))
                .then(Mono.defer(() -> dispatch(in, number)));
    }

    /**
     * Blue ticks, and the typing dots while a slow answer is prepared (BAYMAX_WA_READ_RECEIPTS: off | read | typing).
     * Only for a message that gets a reply (onInbound returned already for the rest) and only to a number the allowlist
     * permits: a read receipt is a message to that number like any other. A courtesy, never part of the answer, so a
     * failure here is swallowed, and one that does not answer within {@link #READ_RECEIPT_BOUND} is abandoned.
     */
    private Mono<Void> readReceipt(WaInbound in, String number) {
        String mode = properties.getWa().getReadReceipts();
        if ("off".equals(mode) || in.providerMessageId() == null || in.providerMessageId().isBlank()
                || !properties.getWa().permits(number)) {
            return Mono.empty();
        }
        boolean typing = "typing".equals(mode) && takesTime(in);
        return Mono.defer(() -> graph.markRead(in.providerMessageId(), typing))
                .timeout(READ_RECEIPT_BOUND)
                .onErrorResume(e -> {
                    log.warn("[baymax] wa read receipt skipped error={}", e.getClass().getSimpleName());
                    return Mono.empty();
                });
    }

    /**
     * Meta: show typing only when the response takes a few seconds. A photo or a readable file (a download, then the
     * acknowledgement) and the detail word (a stored body or a model call) do; a greeting, a thanks or a fallback is a
     * fixed template answered at once and gets the tick alone.
     */
    static boolean takesTime(WaInbound in) {
        return switch (in.type()) {
            case IMAGE -> true;
            case DOCUMENT -> readable(in.mimeType());
            case TEXT, INTERACTIVE, BUTTON -> WaTextRouter.route(in.text()) == WaTextRouter.Route.DETAIL;
            default -> false;
        };
    }

    private Mono<Outcome> dispatch(WaInbound in, String number) {
        return switch (in.type()) {
            case IMAGE -> onImage(in.from(), in.mediaId(), "wa.received");
            case DOCUMENT -> readable(in.mimeType())
                    ? onImage(in.from(), in.mediaId(), "wa.received.file")
                    // refused before any download or ack: a DOCX acknowledged and then dropped was silence by another name
                    : withFamily(number, f -> reply(number, "wa.unsupported_file", Outcome.REPLIED,
                            "document type not readable"), "document");
            // a reaction's emoji goes through the router: 👍 is a thanks, 😢 is not and gets the fallback
            case TEXT, INTERACTIVE, BUTTON, REACTION -> onText(in.from(), in.text());
            case STICKER -> withFamily(number, f -> thanks(number, f), "sticker");
            // D13: a fixed reply plus the red-flag line; nothing can listen yet, so nothing is downloaded
            case AUDIO -> withFamily(number, f -> replyWithRedFlag(number, "wa.voice", "voice note"), "voice note");
            case VIDEO -> withFamily(number, f -> replyWithRedFlag(number, "wa.video", "video"), "video");
            case LOCATION, CONTACTS, OTHER -> withFamily(number, f -> replyWithRedFlag(number, "wa.fallback",
                    "type " + in.type()), "message");
            case SYSTEM -> Mono.just(Outcome.IGNORED);   // unreachable: expectsReply() is false for SYSTEM
        };
    }

    /** Blank or missing mime: let PageRenderer's magic bytes decide (a failure there still replies, plan PR 7). */
    static boolean readable(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return true;
        }
        return READABLE_FILES.contains(mimeType.split(";")[0].trim().toLowerCase(Locale.ROOT));
    }

    /** A photo, acknowledged with "ছবিটা পেয়েছি". */
    public Mono<Outcome> onImage(String from, String mediaId) {
        return onImage(from, mediaId, "wa.received");
    }

    /**
     * @param from   the sender's WhatsApp number as Meta reports it, digits only and no plus
     * @param mediaId the image or file to fetch
     * @param ackKey  the acknowledgement: wa.received for a photo, wa.received.file for a PDF
     */
    Mono<Outcome> onImage(String from, String mediaId, String ackKey) {
        String number = e164(from);
        return records.findFamilyByWhatsapp(number)
                .flatMap(family -> records.patientsOf(family.id()).collectList()
                        .flatMap(patients -> withPatients(number, family, mediaId, patients, ackKey)))
                // defer: switchIfEmpty evaluates its argument at assembly time, so an eager reply(...) here
                // sent the unknown-sender message on EVERY inbound image, known families included
                .switchIfEmpty(Mono.defer(() -> reply(number, "wa.unknown", Outcome.UNKNOWN_SENDER,
                        "sender has no family account")))
                .onErrorResume(FreeTierExceededException.class, e ->
                        reply(number, "wa.free_tier", Outcome.FREE_TIER, "free tier exhausted"))
                .onErrorResume(e -> {
                    // e.toString() of a WebClientResponseException is "404 … from GET <signed media URL>"
                    log.error("[baymax] wa intake failed mediaId={} error={}", mediaId,
                            e instanceof BaymaxException b ? "BaymaxException:" + b.reason() : e.getClass().getSimpleName());
                    return Mono.just(Outcome.FAILED);
                });
    }

    private Mono<Outcome> withPatients(String number, FamilyAccount family, String mediaId, List<PatientProfile> patients,
                                       String ackKey) {
        if (patients.isEmpty()) {
            return reply(number, "wa.unknown", Outcome.UNKNOWN_SENDER, "family has no patient yet");
        }
        if (patients.size() > 1) {
            // §1.4 disambiguation is a later phase; refusing is the only honest answer phase 1 has
            log.warn("[baymax] wa intake refused: family has {} patients and phase 1 cannot ask which one", patients.size());
            return reply(number, "wa.ambiguous", Outcome.AMBIGUOUS_PATIENT, "more than one patient",
                    Map.of("link", properties.getOutbound().getPublicBaseUrl() + "/app"));
        }
        PatientProfile patient = patients.get(0);
        // the cap is checked BEFORE the ack: a family at the limit hears one true message, not "got it" and then "no" (W15).
        // Mono.defer around the send: .then(send(...)) would call graph.sendText at assembly time, before the check ran.
        // intake.accept keeps its own check, so a race past this one still gets wa.free_tier after the ack, truthfully.
        return freeTier.checkCanUploadDocument(family)
                .then(Mono.defer(() -> send(number, copy.bn(ackKey, Map.of()))))
                .then(Mono.defer(() -> graph.downloadMedia(mediaId)))
                .flatMap(bytes -> intake.accept(patient.id(), List.of(new Upload(mediaId + ".jpg", bytes))))
                .doOnNext(document -> log.info("[baymax] wa intake accepted documentId={} patientId={} bytes>0",
                        document.id(), patient.id()))
                .thenReturn(Outcome.ACCEPTED);
    }

    /**
     * Text, a button tap's title, or the words of a quick reply. Every message gets exactly one reply, and none is
     * silent (Phase 0): before, anything but the literal বিস্তারিত/detail/bistarito was acknowledged in the log and
     * the family heard nothing. {@link WaTextRouter} matches the whole message; the route is logged, the text is not.
     *
     * <p>The body is delivered by the delivery port, never sent from here (F4): before F4 this method sent
     * {@code message.body()} itself after {@code detail()} had already delivered it, and under the urgency gate it
     * sent a PENDING body nobody had reviewed.
     */
    public Mono<Outcome> onText(String from, String text) {
        String number = e164(from);
        WaTextRouter.Route route = WaTextRouter.route(text);
        log.info("[baymax] wa text route={} chars={}", route, text == null ? 0 : text.length());
        if (route == WaTextRouter.Route.LOGIN_CODE) {
            return loginCode(number);   // before the family lookup: issueCodeFor answers NO_ACCOUNT itself
        }
        return withFamily(number, family -> switch (route) {
            case DETAIL -> answerLatestDetail(number, family);
            case GREETING_HELLO, GREETING_SALAM, GREETING_NAMASKAR, GREETING_ADAB -> greet(number, family, route);
            case THANKS -> thanks(number, family);
            case FALLBACK -> replyWithRedFlag(number, "wa.fallback", "text not understood");
            case LOGIN_CODE -> loginCode(number);   // unreachable: routed above
        }, "text");
    }

    /** The detail answer (F4): the latest DONE document's DETAIL row, answered by answerDetail. */
    private Mono<Outcome> answerLatestDetail(String number, FamilyAccount family) {
        return documents.latestDoneDocument(family.id())
                .flatMap(document -> explainer.detail(document.id()))
                .flatMap(message -> answerDetail(number, message))
                .switchIfEmpty(Mono.defer(() -> reply(number, "wa.no_document", Outcome.REPLIED,
                        "detail asked for but no readable document yet")));
    }

    /**
     * D2: a sign-in code for the number WhatsApp proved. SENT needs no reply here: the OTP delivery has already sent the
     * code, as a free-form message inside the window this very message opened (DR-39). Every other outcome is answered.
     */
    private Mono<Outcome> loginCode(String number) {
        String minutes = com.elioo.baymax.outbound.application.service.BanglaDate.digits(
                String.valueOf(properties.getAuth().getOtpTtl().toMinutes()));
        return auth.issueCodeFor(number)
                .flatMap(issue -> switch (issue) {
                    case SENT -> {
                        log.info("[baymax] wa login code sent");
                        yield Mono.just(Outcome.LOGIN_CODE);
                    }
                    case RATE_LIMITED -> reply(number, "wa.login_rate_limited", Outcome.LOGIN_CODE, "login code rate-limited",
                            Map.of("minutes", minutes));
                    case NO_ACCOUNT -> reply(number, "wa.login_no_account", Outcome.UNKNOWN_SENDER, "login code, no account");
                    // NOT_PERMITTED: send() refuses it again, with its own WARN; OUTSIDE_WINDOW cannot happen for a sender
                    case NOT_DELIVERED, NOT_PERMITTED, OUTSIDE_WINDOW -> reply(number, "wa.login_off", Outcome.FAILED,
                            "login code not delivered: " + issue);
                })
                .onErrorResume(e -> {
                    log.error("[baymax] wa login code failed error={}", e.getClass().getSimpleName());
                    return reply(number, "wa.login_off", Outcome.FAILED, "login code could not be issued");
                });
    }

    /** How long a NOW / THIS_WEEK keeps leading a greeting or a thanks (W1). */
    private static final Duration STANDING = Duration.ofDays(30);
    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");

    /**
     * The latest DONE document and its standing urgency: NOW or THIS_WEEK, or null when nothing stands.
     *
     * <p>INTERIM until F1 (document.urgency, Phase 1b). The standing level is the highest urgency on the latest DONE
     * document's newest EXPLANATION and DETAIL rows, in ANY gate state: the urgency column is always set, and a PENDING
     * or FAILED_SAFETY row is exactly where a NOW would hide from latestDeliverable (D5). Known gap until F1 + D14: an
     * older urgent document behind a newer routine one is not surfaced.
     */
    private record Latest(Document document, Urgency standing) {
    }

    private Mono<Latest> latest(FamilyAccount family) {
        Instant cutoff = clock.instant().minus(STANDING);
        return documents.latestDoneDocument(family.id())
                .flatMap(doc -> Flux.concat(messages.latestOfKind(doc.id(), OutboundMessage.Kind.EXPLANATION),
                                messages.latestOfKind(doc.id(), OutboundMessage.Kind.DETAIL))
                        .filter(m -> m.createdAt() != null && m.createdAt().isAfter(cutoff))
                        .map(OutboundMessage::urgency)
                        .reduce(Urgency.ROUTINE, Urgency::max)
                        .map(level -> new Latest(doc, level.isAtLeast(Urgency.THIS_WEEK) ? level : null)));
    }

    /** The paper's name for the family: its type, its date in Bangla, and the link to what it says. */
    private Map<String, String> paperVars(Document d) {
        LocalDate today = LocalDate.now(clock.withZone(DHAKA));
        LocalDate date = d.docDate() != null ? d.docDate()
                : d.createdAt() != null ? LocalDate.ofInstant(d.createdAt(), DHAKA) : today;
        return Map.of("document_type", copy.documentType(d.documentType()), "date", BanglaDate.format(date, today),
                "link", properties.getOutbound().getPublicBaseUrl() + "/app/documents/" + d.id());
    }

    /**
     * Mirrors the family's greeting and never assumes religion (spec §7.4); zero model calls (W1). A standing NOW or
     * THIS_WEEK leads it, in its fixed words (UrgencyLines); otherwise it names the latest paper and its link.
     */
    private Mono<Outcome> greet(String number, FamilyAccount family, WaTextRouter.Route route) {
        String opener = copy.bn(switch (route) {
            case GREETING_SALAM -> "wa.greeting.salam";
            case GREETING_NAMASKAR -> "wa.greeting.namaskar";
            case GREETING_ADAB -> "wa.greeting.adab";
            default -> "wa.greeting.hello";
        }, Map.of());
        String intro = copy.bn("wa.greeting.intro", Map.of());
        return latest(family)
                .map(l -> {
                    log.info("[baymax] wa reply key=wa.greeting route={} documentId={} standing={}", route,
                            l.document().id(), l.standing() == null ? "none" : l.standing().dbValue());
                    return l.standing() == null
                            ? String.join("\n", opener, intro, copy.bn("wa.greeting.latest", paperVars(l.document())))
                            // the opener and the paper's name may precede the block; nothing it qualifies does (UrgencyLines)
                            : String.join("\n", opener, copy.bn("wa.greeting.urgent", paperVars(l.document())),
                                    UrgencyLines.lead(copy, l.standing(), intro));
                })
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    log.info("[baymax] wa reply key=wa.greeting route={} documentId=none", route);
                    return String.join("\n", opener, intro, copy.bn("wa.greeting.none", Map.of()));
                }))
                .flatMap(text -> send(number, text))
                .thenReturn(Outcome.REPLIED);
    }

    /** W12: a one-line close plus the red-flag line; a standing NOW / THIS_WEEK is repeated, never closed over. */
    private Mono<Outcome> thanks(String number, FamilyAccount family) {
        return latest(family)
                .filter(l -> l.standing() != null)
                // the address "ঠিক আছে। মনে করিয়ে দিই:" may precede the block (UrgencyLines)
                .map(l -> String.join("\n", copy.bn("wa.thanks.urgent", Map.of()),
                        String.join("\n", UrgencyLines.block(copy, l.standing()))))
                .doOnNext(body -> log.info("[baymax] wa reply key=wa.thanks.urgent reason=standing urgency"))
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    log.info("[baymax] wa reply key=wa.thanks reason=thanks");
                    return copy.bn("wa.thanks", Map.of());
                }))
                // idempotent: a NOW / THIS_WEEK block already carries the red-flag line, once
                .flatMap(body -> send(number, withRedFlag(body)))
                .thenReturn(Outcome.REPLIED);
    }

    private Mono<Outcome> withFamily(String number, Function<FamilyAccount, Mono<Outcome>> known, String what) {
        return records.findFamilyByWhatsapp(number)
                .flatMap(known)
                // defer: an eager reply(...) here would send the unknown-sender text to every known family too
                .switchIfEmpty(Mono.defer(() -> reply(number, "wa.unknown", Outcome.UNKNOWN_SENDER,
                        what + " from unknown sender")));
    }

    private Mono<Outcome> replyWithRedFlag(String number, String key, String why) {
        log.info("[baymax] wa reply key={} reason={}", key, why);
        return send(number, withRedFlag(copy.bn(key, Map.of()))).thenReturn(Outcome.REPLIED);
    }

    /** Phase 0: every fallback, thanks, voice, video and sticker reply carries the approved red-flag line, once. */
    private String withRedFlag(String body) {
        String line = copy.bn("safety.red_flag_line", Map.of());
        return body.contains(line) ? body : body + "\n" + line;
    }

    /**
     * F4: this method never sends a composed body. A released or approved row not yet delivered goes through
     * explainer.deliver, which claims the row (at most once), records the provider's outcome (V13/V14) and is refused by
     * the delivery adapter for anything unreviewed. Every other state gets fixed copy: PENDING the "being checked" line,
     * REJECTED and FAILED_SAFETY the paper link, an already-delivered detail a pointer to it. Urgent documents lead with
     * the UrgencyLines block (D5): the urgency is never held back for review.
     */
    private Mono<Outcome> answerDetail(String number, OutboundMessage m) {
        return switch (m.gateStatus()) {
            case RELEASED, APPROVED -> m.sentAt() == null
                    ? explainer.deliver(m)
                            .map(d -> d.sentAt() != null ? Outcome.ACCEPTED : Outcome.FAILED)
                            // the claim was lost: a concurrent ask is delivering this very row right now
                            .switchIfEmpty(Mono.defer(() -> fixed(number, m, "wa.detail.already_sent", "detail being delivered")))
                    : fixed(number, m, "wa.detail.already_sent", "detail already delivered");
            case PENDING -> fixed(number, m, "wa.detail.pending", "detail pending review");
            case REJECTED, FAILED_SAFETY -> fixed(number, m, "wa.detail.unavailable", "detail withheld");
        };
    }

    private Mono<Outcome> fixed(String number, OutboundMessage m, String key, String why) {
        String text = UrgencyLines.lead(copy, m.urgency(), copy.bn(key,
                Map.of("link", properties.getOutbound().getPublicBaseUrl() + "/app/documents/" + m.documentId())));
        log.info("[baymax] wa detail answered documentId={} gate={} urgency={} reason={} chars={}",
                m.documentId(), m.gateStatus().dbValue(), m.urgency().dbValue(), why, text.length());
        return send(number, text).thenReturn(Outcome.ACCEPTED);
    }

    private Mono<Outcome> reply(String number, String key, Outcome outcome, String why) {
        return reply(number, key, outcome, why, Map.of());
    }

    private Mono<Outcome> reply(String number, String key, Outcome outcome, String why, Map<String, String> vars) {
        log.info("[baymax] wa intake outcome={} reason={}", outcome, why);
        return send(number, copy.bn(key, vars)).thenReturn(outcome);
    }

    /**
     * Phase-1 allowlist, enforced in code rather than relying on the Meta test number's own restriction, which
     * disappears the moment we move to a production number. A refusal is logged, never silently dropped.
     */
    private Mono<Void> send(String number, String body) {
        if (!properties.getWa().permits(number)) {
            log.warn("[baymax] wa outbound refused: recipient is not on BAYMAX_WA_ALLOWLIST (phase 1)");
            return Mono.empty();
        }
        return graph.sendText(number, body)
                .onErrorResume(e -> {
                    // the reply failing must not lose the document that was already accepted
                    log.error("[baymax] wa outbound failed error={}", e.getClass().getSimpleName());
                    return Mono.empty();
                })
                .then();
    }

    /** Meta reports numbers without a plus; every stored number is E.164. */
    static String e164(String from) {
        if (from == null || from.isBlank()) {
            return "";
        }
        return from.startsWith("+") ? from : "+" + from;
    }
}

package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.time.PatientCalendar;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.domain.AttentionItem;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The চ্যাট tab (s06, spec §4.6, AttentionCard and Composer READMEs). Medioo's greeting in a bubble, then the attention
 * stack in the order {@link com.elioo.baymax.web.application.port.in.AttentionUseCase} gives it, each card with exactly
 * one action: NOW under a solid red header carrying the full {@code urgency.now} line (never dismissible: D14's
 * "দেখেছি" is not built), THIS_WEEK in amber, a retake, the doctor's follow-up on a paper slip beside its crop
 * (principle 6, {@link ValueRows#paperQuote}), a paper being read. An empty stack is silence: no heading and no
 * reassurance. The composer says chat is coming (D11 not signed) and can send nothing; its camera, for the owner only,
 * is the one upload entry. A patient with no papers gets the intro and the empty panel's one camera instead.
 */
final class ChatView {

    private final UiCopy copy;

    ChatView(UiCopy copy) {
        this.copy = copy;
    }

    /** The whole tab body. {@code documents} is how many papers the patient has; {@code owner} whether this viewer may add one. */
    String page(Lang lang, String ownerName, String patientName, UUID patientId, List<AttentionItem> stack, long documents, boolean owner) {
        StringBuilder b = new StringBuilder(greeting(lang, ownerName));
        if (documents == 0 && stack.isEmpty()) {
            b.append("<p class=\"bubble\">").append(copy.t(lang, "chat.intro")).append("</p>").append(empty(lang, patientName, patientId, owner));
            return b.append(composer(lang, false, patientId)).toString();
        }
        b.append(stack(lang, stack, patientName))
                .append("<p><a class=\"btn quiet\" href=\"").append(Tab.PAPERS.path).append("?patient=").append(patientId).append("\">")
                .append(copy.t(lang, "chat.papers", patientName)).append("</a></p>");
        return b.append(composer(lang, owner, patientId)).toString();
    }

    /** Medioo's own words: a template with zero model calls, no time-of-day greeting (F7). */
    String greeting(Lang lang, String ownerName) {
        String hello = ownerName == null || ownerName.isBlank() ? copy.t(lang, "chat.hello.plain") : copy.t(lang, "chat.hello", ownerName.strip());
        return "<p class=\"bubble\">" + hello + "</p>";
    }

    /** The heading and the cards, in the order given; nothing at all for an empty stack. */
    String stack(Lang lang, List<AttentionItem> items, String patientName) {
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder("<h2>").append(copy.t(lang, "attn.heading", patientName)).append("</h2><div class=\"attn-stack\">");
        items.forEach(item -> b.append(card(lang, item, patientName)));
        return b.append("</div>").toString();
    }

    /** One card, exactly one action. */
    String card(Lang lang, AttentionItem item, String patientName) {
        return switch (item) {
            case AttentionItem.Urgent u -> {
                boolean now = u.level() == Urgency.NOW;
                yield "<section class=\"attn " + (now ? "now" : "soon") + "\"><div class=\"head\">" + Icons.svg(now ? "octagon" : "calendar", 24)
                        + "<strong>" + copy.urgency(lang, u.level(), false) + "</strong></div><div class=\"body\"><span>"
                        + TimelineView.typeLabel(copy, lang, u.documentType()) + " · " + copy.date(lang, u.date())
                        + (u.dateIsFallback() ? " " + copy.t(lang, "timeline.uploaddate") : "") + "</span>"
                        + action("btn primary", u.documentId(), copy.t(lang, "attn.open")) + "</div></section>";
            }
            case AttentionItem.Retake r -> "<section class=\"attn retake\"><div class=\"head\"><span class=\"disc\" aria-hidden=\"true\">"
                    + Icons.svg("camera", 24) + "</span><strong>" + copy.t(lang, "attn.retake.title") + "</strong></div><div class=\"body\"><span>"
                    + sent(lang, r.receivedAt()) + "</span>" + action("btn outlined", r.documentId(), copy.t(lang, "attn.open")) + "</div></section>";
            case AttentionItem.FollowUp f -> "<section class=\"attn follow\"><div class=\"head\">" + Icons.svg("calendar", 24) + "<strong>"
                    + copy.t(lang, "attn.follow.title", copy.dateText(lang, f.dueDate())) + "</strong></div><div class=\"body\">"
                    + ValueRows.paperQuote(copy, lang, f.documentId(), f.instruction(), f.cropKey(), "")
                    + action("btn quiet", f.documentId(), copy.t(lang, "attn.open")) + "</div></section>";
            case AttentionItem.Reading r -> "<section class=\"attn reading\"><div class=\"head\">" + Icons.svg("page", 24) + "<strong>"
                    + copy.t(lang, "attn.reading.title") + "</strong></div><div class=\"body\"><span>" + sent(lang, r.receivedAt()) + "</span>"
                    + action("btn quiet", r.documentId(), copy.t(lang, "attn.reading.action")) + "</div></section>";
        };
    }

    /** The docked bar: chat is coming and nothing can be typed or sent; the owner's camera opens the add-report sheet. */
    String composer(Lang lang, boolean owner, UUID patientId) {
        StringBuilder b = new StringBuilder("<div class=\"composer\">");
        if (owner) {
            b.append("<a class=\"icon-btn tonal\" href=\"").append(addHref(patientId)).append("\" aria-label=\"")
                    .append(copy.t(lang, "chat.camera")).append("\">").append(Icons.svg("camera", 24)).append("</a>");
        }
        return b.append("<p class=\"soon\">").append(copy.t(lang, "chat.soon")).append("</p></div>").toString();
    }

    /** s06 empty: whose papers, why send one, and — for the owner only — the one camera to the sheet for this patient. */
    private String empty(Lang lang, String patientName, UUID patientId, boolean owner) {
        StringBuilder b = new StringBuilder("<section class=\"empty\"><span class=\"disc\" aria-hidden=\"true\">").append(Icons.svg("papers", 32))
                .append("</span><h2>").append(copy.t(lang, "timeline.empty.title", patientName)).append("</h2><p>")
                .append(copy.t(lang, "timeline.empty.body")).append("</p>");
        if (owner) {
            b.append("<a class=\"btn primary\" href=\"").append(addHref(patientId)).append("\">").append(Icons.svg("camera", 24))
                    .append("<span>").append(copy.t(lang, "chat.empty.action")).append("</span></a>");
        }
        return b.append("</section>").toString();
    }

    private String sent(Lang lang, Instant at) {
        return copy.t(lang, "timeline.sent", copy.dayMonthText(lang, LocalDate.ofInstant(at, PatientCalendar.ZONE)));
    }

    private static String action(String cls, UUID documentId, String label) {
        return "<a class=\"" + cls + "\" href=\"" + PageChrome.BASE + "/documents/" + documentId + "\">" + label + "</a>";
    }

    /** The add-report sheet for this patient, coming back to this tab with the same patient (A7's one entry), escaped for an href. */
    static String addHref(UUID patientId) {
        String back = URLEncoder.encode(Tab.CHAT.path + "?patient=" + patientId, StandardCharsets.UTF_8);
        return UploadUiHandler.SHEET + "?patient=" + patientId + "&amp;back=" + back;
    }
}

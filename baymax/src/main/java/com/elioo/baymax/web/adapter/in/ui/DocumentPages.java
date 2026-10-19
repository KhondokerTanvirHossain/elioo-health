package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyReason;
import com.elioo.baymax.outbound.domain.ValueVerdict;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.domain.ReadingView;
import com.elioo.baymax.web.domain.ResultView;
import com.elioo.baymax.web.domain.WebSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.elioo.baymax.web.adapter.in.ui.Html.esc;
import static com.elioo.baymax.web.adapter.in.ui.PageChrome.BASE;

/**
 * One document: while it is read, the reading page (s09); once DONE, the result (s10) — the stored urgency first, then
 * what the review state allows, then its extracted items beside their crops, its pages, and its deletion. Images are signed afresh on every
 * load. The literal-segment routes come before the {@code {id}}-only route, per the router convention, here and
 * nowhere else.
 */
@Component
@RequiredArgsConstructor
public class DocumentPages implements WebPages {

    private final TimelineUseCase timeline;
    private final UiCopy copy;
    private final PageChrome chrome;
    private final ResultUseCase results;

    /** How often the reading page asks again: the no-script refresh and progress.js's poll (5 s, spec §4.9). */
    static final int POLL_SECONDS = 5;
    /** From five minutes the reading page says it is taking longer than usual. */
    static final Duration SLOW = Duration.ofMinutes(5);

    /**
     * Reason → the block that renders it on a NOW page (spec §4.11, s11). Keyed by the rule's own enum, so a reason added
     * to {@link UrgencyReason} without a block fails ResultNowRenderTest. Every block renders even when no stored row
     * matches its reason (the page images stand in); a stored prefix the enum does not know gets {@link #OTHER_BLOCK}.
     */
    static final Map<UrgencyReason, String> REASON_BLOCKS = new EnumMap<>(Map.of(
            UrgencyReason.VALUE_CRITICAL, "why-value-critical",
            UrgencyReason.VALUE_OUTSIDE_RANGE, "why-value-outside",
            UrgencyReason.UNVERIFIED_VALUE_CRITICAL, "why-unverified-critical",
            UrgencyReason.UNVERIFIED_VALUE_OUTSIDE_RANGE, "why-unverified-outside",
            UrgencyReason.TEXT_EMERGENCY_PHRASE, "why-emergency-phrase",
            UrgencyReason.FOLLOW_UP_PAST_DUE, "why-follow-up",
            UrgencyReason.DIAGNOSIS_PRESENT, "why-diagnosis"));
    /** The generic block: a legacy reason prefix, or a NOW stored with no reason — never a NOW page with nothing. */
    static final String OTHER_BLOCK = "why-other";

    @Override
    public int order() {
        return 50;   // signed in
    }

    @Override
    public RouterFunction<ServerResponse> routes(HandlerFilterFunction<ServerResponse, ServerResponse> requireSession) {
        return RouterFunctions.route()
                // literal segments before the {id}-only routes, per the router convention
                .GET(BASE + "/documents/{id}/delete", this::confirmDelete)
                .POST(BASE + "/documents/{id}/delete", this::delete)
                .GET(BASE + "/documents/{id}/image", this::image)
                .GET(BASE + "/documents/{id}/page/{n}", this::pageImage)
                .GET(BASE + "/documents/{id}/status", this::documentStatus)
                .GET(BASE + "/documents/{id}", this::documentPage)
                .filter(requireSession)
                .build();
    }

    /** GET /app/documents/{id}/status: what the poll needs and nothing a log or a cache could leak. */
    public Mono<ServerResponse> documentStatus(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        return results.status(session.familyId(), PageChrome.uuid(request.pathVariable("id")))
                .flatMap(s -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.CACHE_CONTROL, "private, no-store").bodyValue(Map.of("status", s.name())));
    }

    /**
     * GET /app/documents/{id}: while the paper is RECEIVED or PROCESSING, the reading page (s09); once DONE, the result
     * summary (s10); otherwise the document page as before (B5 replaces NEEDS_RETAKE and FAILED). The branch reads the
     * status alone, which records nothing, so a finished paper's page never reads the reading view and records the one
     * DOCUMENT_VIEW of its own read. A paper finished between the two reads is drawn finished, never as a reading page
     * that would wait for a change that already happened; that page records two DOCUMENT_VIEW rows (the reading view's
     * and the finished page's), accepted for a window of milliseconds rather than a second audit path.
     */
    public Mono<ServerResponse> documentPage(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        Lang lang = Lang.of(request);
        UUID documentId = PageChrome.uuid(request.pathVariable("id"));
        return results.status(session.familyId(), documentId).flatMap(status -> !inFlight(status)
                ? finishedPage(session, lang, documentId, status)
                : results.reading(session.familyId(), documentId).flatMap(r -> {
                    Document.Status now = Document.Status.valueOf(r.document().status());
                    return inFlight(now) ? Html.html(HttpStatus.OK, progressPage(lang, r)) : finishedPage(session, lang, documentId, now);
                }));
    }

    static boolean inFlight(Document.Status status) {
        return status == Document.Status.RECEIVED || status == Document.Status.PROCESSING;
    }

    /** The reading page (s09) in the shell, under কাগজপত্র: reloads itself without script, polls the status with it. */
    String progressPage(Lang lang, ReadingView r) {
        Html.Head head = Html.Head.of(copy.t(lang, "progress.title") + " — " + copy.t(lang, "app.title"))
                .refreshWithoutScript(POLL_SECONDS).script("progress.js");
        return new AppShell(copy).inside(lang, Tab.PAPERS, head, progressBody(lang, r));
    }

    /**
     * Three steps, each a real {@code document.status} (পেয়েছি · পড়ছি · তৈরি): no percentage and no step the code does
     * not have. Nothing about the result — no urgency, no values, no reassurance — until it is DONE.
     */
    String progressBody(Lang lang, ReadingView r) {
        boolean received = Document.Status.RECEIVED.name().equals(r.document().status());
        String id = r.document().documentId();
        Duration untilSlow = SLOW.minus(Duration.between(r.receivedAt(), r.now()));
        boolean slow = !untilSlow.isPositive();
        StringBuilder b = new StringBuilder("<section id=\"progress\" data-status-url=\"").append(BASE).append("/documents/")
                .append(esc(id)).append("/status\" data-status=\"").append(esc(r.document().status())).append('"');
        if (!slow) {
            // a page left open shows the line when its time comes: progress.js reads the seconds, the copy stays here
            b.append(" data-slow-in=\"").append((untilSlow.toMillis() + 999) / 1000).append('"');
        }
        b.append('>');
        b.append("<span class=\"pchip\">").append(copy.t(lang, "result.patient", r.patient().name(), r.patient().age())).append("</span>");
        b.append("<h1>").append(copy.t(lang, "progress.title")).append("</h1>");
        int pages = r.document().pageCount() == null ? 1 : Math.max(1, r.document().pageCount());
        b.append("<p class=\"meta\">").append(copy.t(lang, "progress.sent", pages)).append("</p>");
        String when = copy.time(lang, r.receivedAt()) + (r.viewer().bangladesh() ? "" : " " + copy.t(lang, "time.dhaka"));
        b.append("<ol id=\"steps\" class=\"steps\" aria-live=\"polite\">")
                .append(received ? "<li aria-current=\"step\">" : "<li class=\"done\">").append(copy.t(lang, "progress.step.received"))
                .append(received ? "" : " " + Icons.svg("check", 18)).append(" <span class=\"meta\">").append(when).append("</span></li>")
                .append(received ? "<li class=\"next\">" : "<li aria-current=\"step\">").append(copy.t(lang, "progress.step.processing"))
                .append(received ? "" : " <span class=\"meta\">" + copy.t(lang, "progress.step.processing.note") + "</span>").append("</li>")
                .append("<li class=\"next\">").append(copy.t(lang, "progress.step.done"))
                .append(" <span class=\"meta\">").append(copy.t(lang, "progress.step.done.note")).append("</span></li></ol>");
        b.append("<p id=\"slow\" class=\"warn\"").append(slow ? "" : " hidden").append('>').append(copy.t(lang, "progress.slow")).append("</p>");
        b.append("<p>").append(copy.t(lang, "progress.leave")).append("</p>");
        b.append("<a class=\"btn quiet\" href=\"").append(Tab.PAPERS.path).append("?patient=").append(r.patient().id()).append("\">")
                .append(copy.t(lang, "progress.timeline")).append("</a></section>");
        return b.toString();
    }

    private Mono<ServerResponse> finishedPage(WebSession session, Lang lang, UUID documentId, Document.Status status) {
        return status == Document.Status.DONE ? summaryPage(session, lang, documentId) : legacyPage(session, lang, documentId);
    }

    /** The result (s10) in the shell, under কাগজপত্র. */
    private Mono<ServerResponse> summaryPage(WebSession session, Lang lang, UUID documentId) {
        return results.result(session.familyId(), documentId)
                .flatMap(r -> Html.html(HttpStatus.OK, new AppShell(copy).inside(lang, Tab.PAPERS, summaryBody(lang, r))));
    }

    /**
     * The result summary (s10): the patient named, the paper's type and date, then the stored urgency line as the first
     * section of the page in every review state (F1, D5), then what the review state allows (F3), then the paper's items.
     * A routine paper has no banner and says only that it was filed (D27); a paper whose level is unknown says neither.
     */
    String summaryBody(Lang lang, ResultView r) {
        DocumentView v = r.document();
        StringBuilder b = new StringBuilder("<p>")
                .append(new AppShell(copy).back(lang, Tab.PAPERS.path + "?patient=" + r.patient().id(), "app.back.papers")).append("</p>")
                .append("<span class=\"pchip\">").append(copy.t(lang, "result.patient", r.patient().name(), r.patient().age())).append("</span>")
                .append("<h1>").append(chrome.documentType(lang, v.documentType())).append("</h1>")
                .append("<p class=\"meta\">").append(copy.date(lang, v.documentDate()))
                .append(v.facility() == null ? "" : " · " + esc(v.facility())).append("</p>");
        if (r.done()) {
            b.append(countsLine(lang, v));
        }
        if (r.urgency() == Urgency.ROUTINE) {
            b.append("<p class=\"meta\">").append(copy.t(lang, "result.filed")).append("</p>");
        }
        b.append(banner(lang, r));       // first section in main, on first paint: no script, no animation, no close
        b.append(nowBody(lang, r));      // s11: why, then what to do — before the gate line and the rest of the result
        b.append(gateLine(lang, r));
        if (r.done()) {
            b.append(items(lang, v, PageChrome.uuid(v.documentId()), r.urgency(), shownAbove(r)));
        }
        return b.toString();
    }

    /**
     * The header's counts line (s10): how many values, and how many are outside the printed range — named only when
     * something is (principle 10: "০টি সীমার বাইরে" would read as a reassurance). Counted by the rendered kind, so a
     * row and the count can never disagree. Nothing on a paper with no values.
     */
    String countsLine(Lang lang, DocumentView v) {
        List<Map<String, Object>> values = v.values() == null ? List.of() : v.values();
        if (values.isEmpty()) {
            return "";
        }
        long outside = values.stream().map(ValueRows::kindOf)
                .filter(k -> k == ValueVerdict.Kind.OUTSIDE || k == ValueVerdict.Kind.CRITICAL).count();
        return "<p class=\"counts\">" + (outside == 0 ? copy.t(lang, "result.counts.none", values.size())
                : copy.t(lang, "result.counts", values.size(), outside)) + "</p>";
    }

    /** The stored level's fixed line, from the message bundle (UiCopy.urgency); nothing for ROUTINE or an unknown level. */
    String banner(Lang lang, ResultView r) {
        if (r.urgency() == null || r.urgency() == Urgency.ROUTINE) {
            return "";
        }
        boolean now = r.urgency() == Urgency.NOW;
        // kit §5: the octagon on NOW's solid header, the calendar on THIS_WEEK's tint; aria-hidden, the line says it all
        String icon = now ? Icons.svg("octagon", 28) : Icons.svg("calendar", 26);
        return "<section class=\"banner " + (now ? "now" : "soon") + "\" id=\"urgency\" aria-label=\"" + copy.t(lang, "result.banner.label")
                + "\"><p class=\"line\"><span class=\"icon\">" + icon + "</span><span>" + copy.urgency(lang, r.urgency(), false)
                + "</span></p></section>";
    }

    /**
     * s11 / spec §4.11, only on a stored NOW: one block per distinct stored reason in stored order, then the action for
     * this reader's own phone (F7), what to take, and "I am not a doctor". A Bangladeshi number gets tel:999 once, under
     * the red-flag questions and nowhere else; a number abroad is never handed tel:999 (it does not connect from abroad)
     * and gets "call the person with them" and a WhatsApp share whose text names the patient and carries no value.
     * Every string is a copy key. No call button for the person with them until Phase 5 stores that person.
     */
    String nowBody(Lang lang, ResultView r) {
        if (r.urgency() != Urgency.NOW) {
            return "";
        }
        DocumentView v = r.document();
        UUID documentId = PageChrome.uuid(v.documentId());
        Set<Optional<UrgencyReason>> reasons = new LinkedHashSet<>();
        r.urgencyReasons().forEach(reason -> reasons.add(UrgencyReason.ofReason(reason)));
        if (reasons.isEmpty()) {
            reasons.add(Optional.empty());
        }
        StringBuilder b = new StringBuilder();
        reasons.forEach(reason -> b.append(reasonBlock(lang, v, documentId, reason.orElse(null))));
        boolean bangladesh = r.viewer().bangladesh();
        b.append(bangladesh ? callAction(lang) : remoteAction(lang, r.patient().name()));
        return b.append("<section id=\"now-bring\" class=\"now-after\"><p>").append(copy.t(lang, bangladesh ? "now.bring" : "now.bring.remote"))
                .append("</p><p class=\"small\">").append(copy.t(lang, "now.notdoctor")).append("</p></section>").toString();
    }

    /**
     * The value kinds this page's NOW reason blocks draw: CRITICAL under a stored value_critical, OUTSIDE under a stored
     * value_outside_range, nothing below NOW. {@link #rowsOfKind} and this set are the one rule both places read.
     */
    static Set<ValueVerdict.Kind> shownAbove(ResultView r) {
        if (r.urgency() != Urgency.NOW) {
            return Set.of();
        }
        Set<ValueVerdict.Kind> kinds = EnumSet.noneOf(ValueVerdict.Kind.class);
        for (String reason : r.urgencyReasons()) {
            UrgencyReason.ofReason(reason).flatMap(DocumentPages::kindShownBy).ifPresent(kinds::add);
        }
        return kinds;
    }

    /** The value kind a reason's block draws its rows of, if it draws rows at all. */
    static Optional<ValueVerdict.Kind> kindShownBy(UrgencyReason reason) {
        return switch (reason) {
            case VALUE_CRITICAL -> Optional.of(ValueVerdict.Kind.CRITICAL);
            case VALUE_OUTSIDE_RANGE -> Optional.of(ValueVerdict.Kind.OUTSIDE);
            default -> Optional.empty();
        };
    }

    /** One reason's block; {@code reason} null is the generic block. The page images stand in where no stored row matches. */
    private String reasonBlock(Lang lang, DocumentView v, UUID documentId, UrgencyReason reason) {
        String id = reason == null ? OTHER_BLOCK : REASON_BLOCKS.get(reason);
        StringBuilder b = new StringBuilder("<section id=\"").append(id).append("\" class=\"reason now\">");
        if (reason == null) {
            return b.append(lead(lang, "now.why.other")).append(fullPages(lang, v, documentId)).append("</section>").toString();
        }
        String shown = switch (reason) {
            case VALUE_CRITICAL -> lead(lang, "now.why.critical") + rowsOfKind(lang, v, documentId, kindShownBy(reason).orElseThrow());
            case VALUE_OUTSIDE_RANGE -> lead(lang, "value.group.outside") + rowsOfKind(lang, v, documentId, kindShownBy(reason).orElseThrow());
            case UNVERIFIED_VALUE_CRITICAL -> lead(lang, "now.why.unverified");     // never the number: it is not an observation
            case UNVERIFIED_VALUE_OUTSIDE_RANGE -> lead(lang, "now.why.unverified_outside");
            case TEXT_EMERGENCY_PHRASE -> lead(lang, "now.why.phrase");
            case FOLLOW_UP_PAST_DUE -> lead(lang, "now.why.followup") + followUpQuotes(lang, v, documentId);
            case DIAGNOSIS_PRESENT -> diagnosisQuotes(lang, v, documentId);
        };
        boolean proof = shown.contains("<a class=\"vrow ") || shown.contains("<figure class=\"paper\"");
        return b.append(shown).append(proof ? "" : fullPages(lang, v, documentId)).append("</section>").toString();
    }

    private String lead(Lang lang, String key) {
        return "<p class=\"lead\">" + copy.t(lang, key) + "</p>";
    }

    /** The rows drawn as this kind (the stored verdict, as the groups below draw them); the group below skips them. */
    private String rowsOfKind(Lang lang, DocumentView v, UUID documentId, ValueVerdict.Kind kind) {
        StringBuilder b = new StringBuilder();
        (v.values() == null ? List.<Map<String, Object>>of() : v.values()).stream().filter(row -> ValueRows.kindOf(row) == kind)
                .forEach(row -> b.append(ValueRows.row(copy, lang, documentId, row)));
        return b.toString();
    }

    /** The stored follow-ups as written, beside their crops; a line stored without a crop is not quoted. */
    private String followUpQuotes(Lang lang, DocumentView v, UUID documentId) {
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> f : v.followUp() == null ? List.<Map<String, Object>>of() : v.followUp()) {
            if (f.get("instruction") == null || f.get("crop_key") == null) {
                continue;
            }
            Object due = f.get("due_date");
            String when = due == null || String.valueOf(due).isBlank() ? ""
                    : "<p class=\"due\">" + copy.t(lang, "next.followup", dueText(lang, String.valueOf(due))) + "</p>";
            b.append(ValueRows.paperQuote(copy, lang, documentId, f.get("instruction"), f.get("crop_key"), when));
        }
        return b.toString();
    }

    /** The diagnosis lines as written, under their section's label, beside their crops. */
    private String diagnosisQuotes(Lang lang, DocumentView v, UUID documentId) {
        Object lines = v.clinicalContext() == null ? null : v.clinicalContext().get("diagnosis");
        StringBuilder quotes = new StringBuilder();
        if (lines instanceof List<?> list) {
            for (Object line : list) {
                if (line instanceof Map<?, ?> m && m.get("text") != null && m.get("crop_key") != null) {
                    quotes.append(ValueRows.paperQuote(copy, lang, documentId, m.get("text"), m.get("crop_key"), ""));
                }
            }
        }
        return "<p class=\"lead\">" + chrome.contextSection(lang, "diagnosis") + "</p>" + quotes;
    }

    /** Every page of the paper, whole, each linking to itself so it opens large: what to show a doctor. */
    private String fullPages(Lang lang, DocumentView v, UUID documentId) {
        StringBuilder b = new StringBuilder();
        int pages = v.pageCount() == null ? 1 : Math.max(1, v.pageCount());
        for (int n = 1; n <= pages; n++) {
            String href = BASE + "/documents/" + documentId + "/page/" + n;
            b.append("<a class=\"fullpage\" href=\"").append(href).append("\"><img class=\"page-img\" loading=\"lazy\" alt=\"\" src=\"")
                    .append(href).append("\"><span class=\"cap\">").append(copy.t(lang, "now.page", n)).append("</span><span class=\"zoom\">")
                    .append(copy.t(lang, "crop.zoom")).append("</span></a>");
        }
        return b.toString();
    }

    /** A Bangladeshi phone (F7): the red-flag questions, and the one tel:999 on the page, under them. */
    private String callAction(Lang lang) {
        return "<section id=\"red-flags\" class=\"flags now\"><p class=\"lead\">" + copy.t(lang, "now.flags.q") + "</p><p>"
                + copy.t(lang, "now.flags.go") + "</p><a class=\"btn now-outline\" href=\"tel:999\">" + copy.t(lang, "now.call999")
                + "</a></section>";
    }

    /**
     * A phone abroad (F7): never tel:999. Call the person with the patient; send someone at home a WhatsApp text that names
     * the patient and what to take, and no value (the bundle's own text, URL-encoded with %20 for spaces).
     */
    private String remoteAction(Lang lang, String name) {
        String share = URLEncoder.encode(copy.raw(lang, "now.remote.share.text", name), StandardCharsets.UTF_8).replace("+", "%20");
        return "<section id=\"remote-action\" class=\"emergency\"><p class=\"lead\">" + copy.t(lang, "now.remote.lead", name) + "</p>"
                + "<a class=\"btn\" href=\"https://wa.me/?text=" + esc(share) + "\" rel=\"noopener\">" + copy.t(lang, "now.remote.share") + "</a>"
                + "<p class=\"small\">" + copy.t(lang, "now.remote.share.note") + "</p>"
                + "<p class=\"small\">" + copy.t(lang, "now.remote.999") + "</p>"
                + "<p class=\"lead\">" + copy.t(lang, "now.flags.q") + "</p><p>" + copy.t(lang, "now.remote.flags.go") + "</p></section>";
    }

    /** F3: Medioo's released words, labelled as such; "a person is checking" only for PENDING; withheld says so. */
    String gateLine(Lang lang, ResultView r) {
        return switch (r.gate()) {
            case RELEASED -> "<section class=\"says\" id=\"says\" aria-label=\"" + copy.t(lang, "result.says.label") + "\"><p class=\"label\">"
                    + copy.t(lang, "result.says.label") + "</p><div class=\"explain\">" + esc(r.wording()) + "</div><p class=\"small\">"
                    + copy.t(lang, "result.says.note") + "</p></section>";
            case PENDING -> "<section class=\"gate\" id=\"gate-pending\"><span class=\"chip review\">" + copy.t(lang, "result.review.chip")
                    + "</span><p>" + copy.t(lang, "result.gate.pending", r.reviewHours()) + "</p></section>";
            case REJECTED, FAILED_SAFETY -> "<section class=\"gate\" id=\"gate-withheld\"><p>" + copy.t(lang, "result.gate.withheld") + "</p></section>";
            case NONE -> "";
        };
    }

    /** NEEDS_RETAKE and FAILED, as before A12 (B5 replaces them). */
    private Mono<ServerResponse> legacyPage(WebSession session, Lang lang, UUID documentId) {
        return timeline.document(session.familyId(), documentId)
                .zipWith(timeline.explanationOf(session.familyId(), documentId).map(Optional::of).defaultIfEmpty(Optional.empty()))
                .flatMap(t -> Html.html(HttpStatus.OK, new AppShell(copy).inside(lang, Tab.PAPERS, render(lang, t.getT1(), documentId, t.getT2()))));
    }

    String render(DocumentView view, UUID documentId) {
        return render(Lang.BN, view, documentId, Optional.empty());
    }

    /**
     * The page as the ticket specifies it: extracted items beside their crops, and for a retake nothing but why
     * and the page. Production draws only NEEDS_RETAKE and FAILED with it since A12; a DONE view gets the same
     * {@link #items} the summary shows, so the item tests below the summary cover the page that is served.
     *
     * @param explanation the released BMX-6 message for this document, shown above the extraction exactly as
     *                    composed — it is the only interpretive text on the page and it passed the checklist
     */
    String render(Lang lang, DocumentView view, UUID documentId, Optional<String> explanation) {
        // the view does not name its patient yet (A12's ResultView does): back to the tab, which asks when there are two
        StringBuilder b = new StringBuilder(new AppShell(copy).back(lang, Tab.PAPERS.path, "app.back.papers"));
        b.append("<h1>").append(chrome.documentType(lang, view.documentType())).append(" ").append(chrome.status(lang, view.status())).append("</h1>");
        b.append("<div class=\"meta\">").append(copy.date(lang, view.documentDate()))
                .append(view.facility() == null ? "" : " · " + esc(view.facility())).append("</div>");

        explanation.ifPresent(text -> b.append("<div class=\"card\"><div class=\"meta\">").append(copy.t(lang, "doc.summary"))
                .append("</div><div class=\"explain\">").append(esc(text)).append("</div></div>"));

        if (!"DONE".equals(view.status())) {
            // NEEDS_RETAKE / FAILED / in flight: the reason and the page image, and nothing extracted anywhere
            if (view.reason() != null) {
                b.append("<div class=\"warn\">").append(esc(view.reason())).append("</div>");
            }
            if ("NEEDS_RETAKE".equals(view.status())) {
                b.append("<p>").append(copy.t(lang, "doc.retake")).append("</p>");
            }
            b.append(pageImages(view, documentId));
            b.append(deleteLink(lang, documentId));
            return b.toString();
        }
        return b.append(items(lang, view, documentId, null, Set.of())).toString();
    }

    /**
     * A DONE paper's items (s10, B2): the values grouped by their rendered verdict, the doctor's lines as written, the
     * medicines crop first, the next step, then the pages, "প্রশ্ন? চ্যাট" and the delete link. {@code urgency} is the
     * stored level; null (the legacy page) brings no "what to take" line. {@code shownAbove} are the kinds a NOW reason
     * block already drew (s11), which the outside group does not draw a second time.
     */
    String items(Lang lang, DocumentView view, UUID documentId, Urgency urgency, Set<ValueVerdict.Kind> shownAbove) {
        return valuesSummary(lang, view, documentId, shownAbove) + doctorSection(lang, view, documentId)
                + medicinesSection(lang, view, documentId) + nextSection(lang, view, documentId, urgency)
                + "<h2>" + copy.t(lang, "doc.pages") + "</h2>" + pageImages(view, documentId)
                + "<p><a class=\"btn quiet\" href=\"" + Tab.CHAT.path + "\">" + copy.t(lang, "result.ask") + "</a></p>"
                + deleteLink(lang, documentId);
    }

    /**
     * Spec §4.10: outside first (critical before outside), then the values Medioo could not place with why, then inside
     * (every row; B6 collapses them behind a count). Grouped by {@link ValueRows#kindOf}, the kind each row is drawn as.
     * A row of a kind in {@code shownAbove} is drawn once, in its NOW reason block, and not again here.
     */
    String valuesSummary(Lang lang, DocumentView view, UUID documentId, Set<ValueVerdict.Kind> shownAbove) {
        List<Map<String, Object>> values = view.values() == null ? List.of() : view.values();
        int unverified = unverified(view, "values");
        if (values.isEmpty() && unverified == 0) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        if (unverified > 0) {
            b.append("<p class=\"warn\">").append(copy.t(lang, "value.unverified")).append("</p>");
        }
        List<Map<String, Object>> outside = new ArrayList<>();
        List<Map<String, Object>> unknown = new ArrayList<>();
        List<Map<String, Object>> inside = new ArrayList<>();
        for (Map<String, Object> v : values) {
            ValueVerdict.Kind kind = ValueRows.kindOf(v);
            if (shownAbove.contains(kind)) {
                continue;
            }
            switch (kind) {
                case CRITICAL, OUTSIDE -> outside.add(v);
                case UNKNOWN -> unknown.add(v);
                case INSIDE -> inside.add(v);
            }
        }
        outside.sort(Comparator.comparing(v -> ValueRows.kindOf(v) == ValueVerdict.Kind.CRITICAL ? 0 : 1));   // stable
        group(b, lang, documentId, "outside", "value.group.outside", null, outside);
        group(b, lang, documentId, "unknown", "value.group.unknown", "value.group.unknown.why", unknown);
        group(b, lang, documentId, "inside", "value.group.inside", null, inside);
        return b.toString();
    }

    private void group(StringBuilder b, Lang lang, UUID documentId, String id, String title, String why, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        b.append("<section id=\"").append(id).append("\" class=\"vgroup\"><h2>").append(copy.t(lang, title)).append("</h2>");
        if (why != null) {
            b.append("<p class=\"why\">").append(copy.t(lang, why)).append("</p>");
        }
        rows.forEach(v -> b.append(ValueRows.row(copy, lang, documentId, v)));
        b.append("</section>");
    }

    /**
     * ডাক্তার লিখেছেন (DR-26, principle 6): every clinical-context section but the referral, each line quoted verbatim
     * under "কাগজে লেখা" beside its crop. A line stored without a crop is not quoted: no fact without its proof.
     */
    String doctorSection(Lang lang, DocumentView view, UUID documentId) {
        if (view.clinicalContext() == null || view.clinicalContext().isEmpty()) {
            return "";
        }
        StringBuilder inner = new StringBuilder();
        for (Map.Entry<String, Object> sec : view.clinicalContext().entrySet()) {
            String label = chrome.contextSection(lang, sec.getKey());
            if (label == null || !(sec.getValue() instanceof List<?> lines)) {
                continue;
            }
            StringBuilder quotes = new StringBuilder();
            for (Object line : lines) {
                if (line instanceof Map<?, ?> m && m.get("text") != null && m.get("crop_key") != null) {
                    quotes.append(ValueRows.paperQuote(copy, lang, documentId, m.get("text"), m.get("crop_key"), ""));
                }
            }
            if (!quotes.isEmpty()) {
                inner.append("<h3>").append(label).append("</h3>").append(quotes);
            }
        }
        return inner.isEmpty() ? "" : "<section id=\"doctor\" class=\"doctor\"><h2>" + copy.t(lang, "doctor.title") + "</h2>" + inner + "</section>";
    }

    /** MedicineRow (s17): the pharmacy notice once, then each line crop first and verbatim; no colour, no status. */
    String medicinesSection(Lang lang, DocumentView view, UUID documentId) {
        List<Map<String, Object>> medicines = view.medicines() == null ? List.of() : view.medicines();
        int unverified = unverified(view, "medicines");
        if (medicines.isEmpty() && unverified == 0) {
            return "";
        }
        StringBuilder b = new StringBuilder("<section id=\"medicines\"><h2>").append(copy.t(lang, "med.title")).append("</h2>");
        if (!medicines.isEmpty()) {
            b.append("<p class=\"warn\">").append(copy.t(lang, "med.pharmacy")).append("</p>");   // s17's warn notice
        }
        if (unverified > 0) {
            b.append("<p class=\"warn\">").append(copy.t(lang, "med.unverified", unverified)).append("</p>");
        }
        medicines.forEach(m -> b.append(ValueRows.medicine(copy, lang, documentId, m)));
        return b.append("</section>").toString();
    }

    /**
     * পরের ধাপ: what to take to the doctor, only when the stored level gives a reason to see one (THIS_WEEK, NOW); then
     * each stored follow-up quoted from the paper beside its crop, its date on the patient's calendar.
     */
    String nextSection(Lang lang, DocumentView view, UUID documentId, Urgency urgency) {
        boolean bring = urgency == Urgency.NOW || urgency == Urgency.THIS_WEEK;
        List<Map<String, Object>> followUps = view.followUp() == null ? List.of() : view.followUp();
        int unverified = unverified(view, "follow_up");
        if (!bring && followUps.isEmpty() && unverified == 0) {
            return "";
        }
        StringBuilder b = new StringBuilder("<section id=\"next\"><h2>").append(copy.t(lang, "next.title")).append("</h2>");
        if (bring) {
            b.append("<p>").append(copy.t(lang, "next.bring")).append("</p>");
        }
        for (Map<String, Object> f : followUps) {
            Object due = f.get("due_date");
            String when = due == null || String.valueOf(due).isBlank() ? ""
                    : "<p class=\"due\">" + copy.t(lang, "next.followup", dueText(lang, String.valueOf(due))) + "</p>";
            b.append(ValueRows.paperQuote(copy, lang, documentId, f.get("instruction"), f.get("crop_key"), when));
        }
        if (unverified > 0) {
            b.append("<p class=\"warn\">").append(copy.t(lang, "next.unverified", unverified)).append("</p>");
        }
        return b.append("</section>").toString();
    }

    /** A stored due date as the page shows dates, unescaped (t escapes its arguments once); not ISO → as written. */
    private String dueText(Lang lang, String due) {
        try {
            return copy.dateText(lang, java.time.LocalDate.parse(due));
        } catch (java.time.format.DateTimeParseException e) {
            return due;
        }
    }

    /** DR-28: how many items of this kind were read but could not be located on the page, so were not stored. */
    private static int unverified(DocumentView view, String kind) {
        return view.unverified() != null && view.unverified().get(kind) instanceof Number n ? n.intValue() : 0;
    }

    private String pageImages(DocumentView view, UUID documentId) {
        // page keys are deterministic; the image endpoint checks the key against the document before signing
        StringBuilder b = new StringBuilder();
        int pages = view.pageCount() == null ? 1 : Math.max(1, view.pageCount());
        for (int n = 1; n <= pages; n++) {
            b.append("<img class=\"page-img\" loading=\"lazy\" alt=\"\" src=\"").append(BASE).append("/documents/").append(documentId)
                    .append("/page/").append(n).append("\">");
        }
        return b.toString();
    }

    private String deleteLink(Lang lang, UUID documentId) {
        return "<p class=\"small spaced\"><a href=\"" + BASE + "/documents/" + documentId + "/delete\">"
                + copy.t(lang, "doc.delete") + "</a></p>";
    }

    public Mono<ServerResponse> confirmDelete(ServerRequest request) {
        UUID documentId = PageChrome.uuid(request.pathVariable("id"));
        Lang lang = Lang.of(request);
        return Html.html(HttpStatus.OK, new AppShell(copy).inside(lang, Tab.PAPERS, """
                <h1>%s</h1>
                <div class="warn">%s</div>
                <form method="post" action="%s/documents/%s/delete">
                  <button type="submit" class="danger">%s</button>
                  <a class="btn quiet" href="%s/documents/%s">%s</a>
                </form>
                """.formatted(copy.t(lang, "doc.delete.q"), copy.t(lang, "doc.delete.warn"), BASE, documentId,
                copy.t(lang, "doc.delete.yes"), BASE, documentId, copy.t(lang, "doc.delete.no"))));
    }

    public Mono<ServerResponse> delete(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        Lang lang = Lang.of(request);
        UUID documentId = PageChrome.uuid(request.pathVariable("id"));
        return timeline.deleteDocument(session.familyId(), documentId).flatMap(report -> Html.html(HttpStatus.OK, new AppShell(copy).inside(lang, Tab.PAPERS,
                "<h1>" + copy.t(lang, "doc.deleted") + "</h1><div class=\"card\">" + copy.t(lang, "doc.deleted.objects", report.objectsDeleted())
                + "<br>" + copy.t(lang, "doc.deleted.rows", report.ledgerRowsDeleted()) + "</div>"
                + "<p>" + new AppShell(copy).back(lang, Tab.PAPERS.path, "app.back.papers") + "</p>")));
    }

    // ---- images: signed afresh on every load -------------------------------------------------------

    public Mono<ServerResponse> image(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID documentId = PageChrome.uuid(request.pathVariable("id"));
        String key = request.queryParam("key").orElseThrow(() -> BaymaxException.badRequest("invalid_request", "key is required"));
        return timeline.imageUrl(session.familyId(), documentId, key)
                .flatMap(url -> ServerResponse.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, url.toString())
                        .header(HttpHeaders.CACHE_CONTROL, "private, no-store").build());
    }

    public Mono<ServerResponse> pageImage(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID documentId = PageChrome.uuid(request.pathVariable("id"));
        int page;
        try {
            page = Integer.parseInt(request.pathVariable("n"));
        } catch (NumberFormatException e) {
            throw BaymaxException.badRequest("invalid_request", "page must be a number");
        }
        return timeline.pageUrl(session.familyId(), documentId, page)
                .flatMap(url -> ServerResponse.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, url.toString())
                        .header(HttpHeaders.CACHE_CONTROL, "private, no-store").build());
    }
}

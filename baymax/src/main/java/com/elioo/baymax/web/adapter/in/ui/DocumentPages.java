package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.time.PatientCalendar;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.extraction.domain.RetakeReason;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyReason;
import com.elioo.baymax.outbound.domain.ValueVerdict;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.domain.EarlierReading;
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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
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
 * what the review state allows, then its extracted items beside their crops, its pages, and its deletion; everything on
 * the paper at {@code /all} (s17), and one value's sheet at {@code /values/{obs}} (s13). Images are signed afresh on every
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
    /**
     * The reasons the rule raises a paper to NOW with (UrgencyService; derived from it in ResultNowRenderTest). Their
     * blocks come first; the THIS_WEEK reasons a NOW paper also carries follow them (spec §4.11, "shown below if present").
     */
    static final Set<UrgencyReason> NOW_REASONS = EnumSet.of(UrgencyReason.VALUE_CRITICAL, UrgencyReason.UNVERIFIED_VALUE_CRITICAL,
            UrgencyReason.TEXT_EMERGENCY_PHRASE);
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
                .GET(BASE + "/documents/{id}/all", this::detailPage)
                .GET(BASE + "/documents/{id}/values/{obs}", this::valueSheetPage)
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
     * GET /app/documents/{id}: while the paper is RECEIVED or PROCESSING, the reading page (s09); once finished, its own
     * page from {@link #finishedPage} — the result (s10), the retake card (s12) or the failed page (B5). The branch reads the
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
                ? finishedPage(session, lang, documentId)
                : results.reading(session.familyId(), documentId).flatMap(r -> {
                    Document.Status now = Document.Status.valueOf(r.document().status());
                    return inFlight(now) ? Html.html(HttpStatus.OK, progressPage(lang, r)) : finishedPage(session, lang, documentId);
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

    /**
     * A finished paper's page, from one read (ResultUseCase.result): DONE is the result (s10), NEEDS_RETAKE the retake
     * card (s12), FAILED its own page. Drawn from the status the read returned, so the body always matches the view.
     */
    private Mono<ServerResponse> finishedPage(WebSession session, Lang lang, UUID documentId) {
        return results.result(session.familyId(), documentId).flatMap(r -> {
            String body = switch (Document.Status.valueOf(r.document().status())) {
                case NEEDS_RETAKE -> retakeBody(lang, r);
                case FAILED -> failedBody(lang, r);
                default -> summaryBody(lang, r);
            };
            return Html.html(HttpStatus.OK, new AppShell(copy).inside(lang, Tab.PAPERS, body));
        });
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
                .append("<p class=\"meta\">").append(metaLine(lang, r)).append("</p>");
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
     * s11 / spec §4.11, only on a stored NOW: one block per distinct stored reason ({@link #drawnOrder}), then the action
     * for this reader's own phone (F7), the red-flag questions, what to take, and "I am not a doctor". A Bangladeshi
     * number gets tel:999 once, under the red-flag questions and nowhere else; a number abroad is never handed tel:999 (it
     * does not connect from abroad) and gets "call the person with them" and a WhatsApp share whose text names the
     * patient and carries no value, then the same questions with no call link. Every string is a copy key. No call
     * button for the person with them until Phase 5 stores that person.
     */
    String nowBody(Lang lang, ResultView r) {
        if (r.urgency() != Urgency.NOW) {
            return "";
        }
        DocumentView v = r.document();
        UUID documentId = PageChrome.uuid(v.documentId());
        StringBuilder b = new StringBuilder();
        drawnOrder(r.urgencyReasons()).forEach(reason -> b.append(reasonBlock(lang, v, documentId, reason.orElse(null))));
        boolean bangladesh = r.viewer().bangladesh();
        b.append(bangladesh ? "" : remoteAction(lang, r.patient().name())).append(redFlags(lang, bangladesh));
        return b.append("<section id=\"now-bring\" class=\"now-after\"><p>").append(copy.t(lang, bangladesh ? "now.bring" : "now.bring.remote"))
                .append("</p><p class=\"small\">").append(copy.t(lang, "now.notdoctor")).append("</p></section>").toString();
    }

    /**
     * The blocks of a NOW page, in the order drawn (spec §4.11): one per distinct stored reason, empty for a prefix the
     * enum does not know. The NOW reasons ({@link #NOW_REASONS}) and the unknown ones (their level is not known, so they
     * are not demoted) first, then the THIS_WEEK reasons "below if present", each group in stored order. The rule stores
     * values in printed order and the phrase last, so the stored order alone would put a THIS_WEEK block above the reason
     * the paper is NOW for. With no NOW reason stored (a legacy row; the rule never stores one) the generic block comes
     * first, so a NOW page always says why it is NOW.
     */
    static List<Optional<UrgencyReason>> drawnOrder(List<String> stored) {
        Set<Optional<UrgencyReason>> distinct = new LinkedHashSet<>();
        (stored == null ? List.<String>of() : stored).forEach(reason -> distinct.add(UrgencyReason.ofReason(reason)));
        List<Optional<UrgencyReason>> first = new ArrayList<>();
        List<Optional<UrgencyReason>> below = new ArrayList<>();
        for (Optional<UrgencyReason> reason : distinct) {
            (reason.isEmpty() || NOW_REASONS.contains(reason.get()) ? first : below).add(reason);
        }
        if (first.isEmpty()) {
            first.add(Optional.empty());
        }
        first.addAll(below);
        return first;
    }

    /**
     * The value kinds this page's NOW reason blocks draw: CRITICAL under a stored value_critical, nothing below NOW. An
     * OUTSIDE row stays in "যেগুলো সীমার বাইরে" below, as s11 draws it; its block points there. {@link #rowsOfKind} and
     * this set are the one rule both places read.
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
        return reason == UrgencyReason.VALUE_CRITICAL ? Optional.of(ValueVerdict.Kind.CRITICAL) : Optional.empty();
    }

    /** One reason's block; {@code reason} null is the generic block. The page images stand in where no stored row matches. */
    private String reasonBlock(Lang lang, DocumentView v, UUID documentId, UrgencyReason reason) {
        String id = reason == null ? OTHER_BLOCK : REASON_BLOCKS.get(reason);
        StringBuilder b = new StringBuilder("<section id=\"").append(id).append("\" class=\"reason now\">");
        if (reason == null) {
            return b.append(lead(lang, "now.why.other")).append(fullPages(lang, v, documentId)).append("</section>").toString();
        }
        // the block's lead, and what proves it on the paper; with no proof the whole pages stand in (never a bare line)
        String[] shown = switch (reason) {
            case VALUE_CRITICAL -> new String[]{lead(lang, "now.why.critical"), rowsOfKind(lang, v, documentId, ValueVerdict.Kind.CRITICAL)};
            case VALUE_OUTSIDE_RANGE -> hasKind(v, ValueVerdict.Kind.OUTSIDE)      // the rows stay in their group below
                    ? new String[]{"", "<a class=\"btn quiet\" href=\"#outside\">" + copy.t(lang, "now.why.outside") + "</a>"}
                    : new String[]{lead(lang, "value.group.outside"), ""};
            case UNVERIFIED_VALUE_CRITICAL -> new String[]{lead(lang, "now.why.unverified"), ""};   // never the number: not an observation
            case UNVERIFIED_VALUE_OUTSIDE_RANGE -> new String[]{lead(lang, "now.why.unverified_outside"), ""};
            case TEXT_EMERGENCY_PHRASE -> new String[]{lead(lang, "now.why.phrase"), ""};
            case FOLLOW_UP_PAST_DUE -> new String[]{lead(lang, "now.why.followup"), followUpQuotes(lang, v, documentId)};
            case DIAGNOSIS_PRESENT -> new String[]{"<p class=\"lead\">" + chrome.contextSection(lang, "diagnosis") + "</p>",
                    diagnosisQuotes(lang, v, documentId)};
        };
        return b.append(shown[0]).append(shown[1].isEmpty() ? fullPages(lang, v, documentId) : shown[1]).append("</section>").toString();
    }

    private static boolean hasKind(DocumentView v, ValueVerdict.Kind kind) {
        return v.values() != null && v.values().stream().anyMatch(row -> ValueRows.kindOf(row) == kind);
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

    /** The diagnosis lines as written, beside their crops; a line stored without a crop is not quoted. */
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
        return quotes.toString();
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

    /**
     * The red-flag questions, their own section for every reader (s11, s11--abroad). A Bangladeshi phone (F7) gets the one
     * tel:999 on the page, under them; a phone abroad gets "tell the person with them" and no call link at all.
     */
    private String redFlags(Lang lang, boolean bangladesh) {
        return "<section id=\"red-flags\" class=\"flags now\"><p class=\"lead\">" + copy.t(lang, "now.flags.q") + "</p><p>"
                + copy.t(lang, bangladesh ? "now.flags.go" : "now.remote.flags.go") + "</p>"
                + (bangladesh ? "<a class=\"btn now-outline\" href=\"tel:999\">" + copy.t(lang, "now.call999") + "</a>" : "")
                + "</section>";
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
                + "<p class=\"small\">" + copy.t(lang, "now.remote.999") + "</p></section>";
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

    /**
     * s12 / RetakeCard (spec §4.12, DR-24): a warn-tint card, never amber or red (a retake is not urgency). The lead, the
     * reason from the stored code in the reader's language (F5; never the developer prose, never the raw code), the photo
     * that was sent, and tips that escalate with this patient's streak ({@link ResultView#retakeOrdinal}). For the
     * patient's owner, the one upload entry for the same patient, camera first and working without script; from the
     * third retake in a row "অন্য কাগজ পাঠান" leads and "এই কাগজটাই আবার তুলুন" goes quiet. A view-only member is told why
     * and gets no form. Nothing extracted is shown: a retake keeps no value (HC3).
     */
    String retakeBody(Lang lang, ResultView r) {
        DocumentView v = r.document();
        UUID documentId = PageChrome.uuid(v.documentId());
        UUID patientId = r.patient().id();
        boolean third = r.retakeOrdinal() >= 3;
        StringBuilder b = new StringBuilder(header(lang, r, third ? "retake.title.third" : "retake.title", attempt(lang, r.retakeOrdinal())));
        b.append("<section id=\"retake\" class=\"retake-card\"><div class=\"retake-h\"><span class=\"retake-i\">").append(Icons.svg("camera", 24))
                .append("</span><p class=\"lead\">").append(copy.t(lang, "retake.head")).append("</p></div>")
                .append("<p>").append(retakeReason(lang, v.reasonCode())).append("</p>")
                .append(sentPhotos(lang, v, documentId));
        if (third) {
            b.append("<p>").append(copy.t(lang, "retake.third.p")).append("</p>");
        } else {
            b.append("<h2>").append(copy.t(lang, "retake.tips")).append("</h2><ul class=\"retake-tips\">");
            int tips = r.retakeOrdinal() >= 2 ? 3 : 2;   // messages_bn retake (daylight, flat), then retake.second's three
            for (int n = 1; n <= tips; n++) {
                b.append("<li>").append(Icons.svg("check", 18)).append("<span>").append(copy.t(lang, "retake.tip." + n)).append("</span></li>");
            }
            b.append("</ul>");
        }
        if (!r.owner()) {
            b.append("<p class=\"meta\">").append(copy.t(lang, "retake.viewonly")).append("</p>");
        } else if (third) {
            b.append(anotherPaper(lang, patientId, documentId, true))
                    .append(sendForm(lang, "retake-form", r.patient(), documentId, "retake.again.same", false));
        } else {
            b.append(sendForm(lang, "retake-form", r.patient(), documentId, "retake.again", true))
                    .append(anotherPaper(lang, patientId, documentId, false));
        }
        if (third || !r.owner()) {
            b.append("<p><a class=\"btn quiet\" href=\"").append(Tab.PAPERS.path).append("?patient=").append(patientId).append("\">")
                    .append(copy.t(lang, "retake.later")).append("</a></p>");
        }
        b.append("<p class=\"small\">").append(copy.t(lang, "retake.free")).append("</p>")
                .append("<p class=\"small\">").append(copy.t(lang, "retake.nothing_kept")).append("</p></section>");
        return b.append(deleteLink(lang, documentId)).toString();
    }

    /**
     * FAILED: our side, said honestly ("আমাদের দিকে একটা সমস্যা"), never a retake's tips and never the stored error. The
     * owner gets the same one upload entry for the same patient; a view-only member is told why.
     */
    String failedBody(Lang lang, ResultView r) {
        UUID documentId = PageChrome.uuid(r.document().documentId());
        StringBuilder b = new StringBuilder(header(lang, r, "failed.title", null))
                .append("<section id=\"failed\" class=\"retake-card\"><p>").append(copy.t(lang, "failed.p")).append("</p>");
        b.append(r.owner() ? sendForm(lang, "failed-form", r.patient(), documentId, "failed.again", true)
                : "<p class=\"meta\">" + copy.t(lang, "retake.viewonly") + "</p>");
        b.append("<p class=\"small\">").append(copy.t(lang, "failed.free")).append("</p></section>");
        return b.append(deleteLink(lang, documentId)).toString();
    }

    /**
     * Back to this patient's papers, the patient named, the page's title, and s12's meta line: when it was sent on the
     * patient's calendar ("আজ, " when that is today), how many pages, and {@code attempt} when there is one.
     */
    private String header(Lang lang, ResultView r, String titleKey, String attempt) {
        StringBuilder b = new StringBuilder("<p>")
                .append(new AppShell(copy).back(lang, Tab.PAPERS.path + "?patient=" + r.patient().id(), "app.back.papers")).append("</p>")
                .append("<span class=\"pchip\">").append(copy.t(lang, "result.patient", r.patient().name(), r.patient().age())).append("</span>")
                .append("<h1>").append(copy.t(lang, titleKey)).append("</h1>");
        if (r.receivedAt() != null) {
            LocalDate sent = LocalDate.ofInstant(r.receivedAt(), PatientCalendar.ZONE);
            String day = copy.dateText(lang, sent);
            String when = r.now() != null && sent.equals(LocalDate.ofInstant(r.now(), PatientCalendar.ZONE)) ? copy.raw(lang, "retake.meta.today", day) : day;
            int pages = r.document().pageCount() == null ? 1 : Math.max(1, r.document().pageCount());
            b.append("<p class=\"meta\">").append(copy.t(lang, "retake.meta", when, pages)).append(attempt == null ? "" : " · " + attempt).append("</p>");
        }
        return b.toString();
    }

    /** Which try this is, from the third retake in a row on (RetakeCard third state); nothing before. */
    private String attempt(Lang lang, int ordinal) {
        return ordinal == 3 ? copy.t(lang, "retake.meta.third") : ordinal > 3 ? copy.t(lang, "retake.meta.nth", ordinal) : null;
    }

    /**
     * The reason's line from its stored code: the section's own line where one exists, else the reason's; a code this
     * build does not know reads as the legacy line, so a retake always says why (V20 guarantees a code is stored).
     */
    private String retakeReason(Lang lang, String code) {
        return retakeReason(copy, lang, code);
    }

    /** As above, for any page that shows a retake (the কাগজপত্র row, C3): one mapping from code to line. */
    static String retakeReason(UiCopy copy, Lang lang, String code) {
        RetakeReason reason = RetakeReason.ofCode(code).orElse(RetakeReason.LEGACY);
        String section = RetakeReason.sectionOf(code);
        String specific = section == null ? null : copy.maybe(lang, "retake.reason." + reason.prefix() + "." + section);
        return specific != null ? specific : copy.t(lang, "retake.reason." + reason.prefix());
    }

    /** The photos the family sent, whole, each opening large: no region, the pipeline does not know one. */
    private String sentPhotos(Lang lang, DocumentView v, UUID documentId) {
        StringBuilder b = new StringBuilder();
        int pages = v.pageCount() == null ? 1 : Math.max(1, v.pageCount());
        for (int n = 1; n <= pages; n++) {
            String href = BASE + "/documents/" + documentId + "/page/" + n;
            b.append("<a class=\"fullpage\" href=\"").append(href).append("\"><img class=\"page-img\" loading=\"lazy\" alt=\"\" src=\"")
                    .append(href).append("\"><span class=\"cap\">").append(copy.t(lang, "retake.photo", n)).append("</span></a>");
        }
        return b.toString();
    }

    /**
     * A new photo of this paper for the same patient: the one upload entry (POST /app/add), camera first, a visible file
     * input and a submit, so it works without script. {@code back} brings a refusal's sheet back over this paper.
     */
    private String sendForm(Lang lang, String id, PatientProfile patient, UUID documentId, String submitKey, boolean primary) {
        UUID patientId = patient.id();
        return "<form id=\"" + id + "\" class=\"retake-send\" method=\"post\" enctype=\"multipart/form-data\" action=\"" + UploadUiHandler.SHEET + "\">"
                + "<input type=\"hidden\" name=\"patient\" value=\"" + patientId + "\">"
                + "<input type=\"hidden\" name=\"back\" value=\"" + BASE + "/documents/" + documentId + "\">"
                + "<label class=\"retake-file\"><span>" + copy.t(lang, "retake.file") + "</span>"
                + "<input type=\"file\" name=\"file\" accept=\"image/*,application/pdf\" capture=\"environment\" required></label>"
                + "<p class=\"meta\">" + copy.t(lang, "retake.same_patient", patient.name()) + "</p>"
                + "<button type=\"submit\" class=\"btn " + (primary ? "primary" : "quiet") + "\">" + copy.t(lang, submitKey) + "</button></form>";
    }

    /**
     * "অন্য কাগজ পাঠান": the add sheet (the one upload entry) for this patient, which comes back to this paper. The sheet
     * offers every source (camera, gallery, PDF) and the family may still choose whose paper it is.
     */
    private String anotherPaper(Lang lang, UUID patientId, UUID documentId, boolean primary) {
        String back = URLEncoder.encode(BASE + "/documents/" + documentId, StandardCharsets.UTF_8);
        return "<p><a class=\"btn " + (primary ? "primary" : "quiet") + "\" href=\"" + UploadUiHandler.SHEET + "?patient=" + patientId
                + "&amp;back=" + back + "\">" + copy.t(lang, "retake.other") + "</a></p>";
    }

    // ---- B6: everything on the paper (s17) and one value's sheet (s13) ---------------------------------------------

    /**
     * GET /app/documents/{id}/all: the detail of a read paper. A paper not yet DONE has no detail (nothing was read to
     * show), so it goes to its own page, which says what is happening; the status read records nothing.
     */
    public Mono<ServerResponse> detailPage(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        Lang lang = Lang.of(request);
        UUID documentId = PageChrome.uuid(request.pathVariable("id"));
        Mono<ServerResponse> toPage = ServerResponse.seeOther(URI.create(BASE + "/documents/" + documentId)).build();
        return results.status(session.familyId(), documentId).flatMap(status -> status != Document.Status.DONE ? toPage
                : results.result(session.familyId(), documentId).flatMap(r -> !r.done() ? toPage
                        : Html.html(HttpStatus.OK, new AppShell(copy).inside(lang, Tab.PAPERS, detail(lang, r)))));
    }

    /**
     * GET /app/documents/{id}/values/{obs}?from=summary|all: one value's sheet as a plain URL, over the কাগজপত্র tab. The
     * observation is resolved through {@code earlierReadings} first, which is 404 for another family and for an id that is
     * not one of THIS paper's values — before the result is read, so a probe records no view.
     */
    public Mono<ServerResponse> valueSheetPage(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        Lang lang = Lang.of(request);
        UUID documentId = PageChrome.uuid(request.pathVariable("id"));
        UUID observationId = PageChrome.uuid(request.pathVariable("obs"));
        String from = request.queryParam("from").filter(ValueRows.FROM_ALL::equals).orElse(ValueRows.FROM_SUMMARY);
        return results.earlierReadings(session.familyId(), documentId, observationId).collectList()
                .flatMap(earlier -> results.result(session.familyId(), documentId).flatMap(r -> Html.html(HttpStatus.OK,
                        new AppShell(copy).sheet(lang, Tab.PAPERS, "detail.seg.values", valueSheet(lang, r, observationId, earlier, from)))));
    }

    /**
     * s17: the back link to the result, the patient, the title, the meta lines, the stored banner (the summary's own
     * method, F1), anchors to the sections that exist (no script), then every value grouped by its drawn verdict (every
     * inside row, none collapsed), every medicine crop first, every line the doctor wrote — the context sections but the
     * referral, and the follow-ups — quoted beside its crop, and every page whole. Delete only for the owner (§4.16).
     */
    String detail(Lang lang, ResultView r) {
        DocumentView v = r.document();
        UUID documentId = PageChrome.uuid(v.documentId());
        List<Map<String, Object>> values = v.values() == null ? List.of() : v.values();
        int pages = v.pageCount() == null ? 1 : Math.max(1, v.pageCount());
        StringBuilder b = new StringBuilder("<p><a class=\"back\" href=\"").append(BASE).append("/documents/").append(documentId)
                .append("\">‹ ").append(copy.t(lang, "detail.back")).append("</a></p>")
                .append("<span class=\"pchip\">").append(copy.t(lang, "result.patient", r.patient().name(), r.patient().age())).append("</span>")
                .append("<h1>").append(copy.t(lang, "detail.title", typeText(lang, v.documentType()))).append("</h1>")
                .append("<p class=\"meta\">").append(metaLine(lang, r)).append("</p>")
                .append("<p class=\"meta\">").append(values.isEmpty() ? copy.t(lang, "detail.pagecount", pages)
                        : copy.t(lang, "detail.counts", values.size(), pages)).append("</p>");
        b.append(banner(lang, r));
        String valuesBlock = detailValues(lang, v, documentId, values);
        String medicines = medicinesSection(lang, v, documentId);
        String doctor = detailDoctor(lang, v, documentId);
        Map<String, String> segments = new java.util.LinkedHashMap<>();
        if (!valuesBlock.isEmpty()) {
            segments.put("values", "detail.seg.values");
        }
        if (!medicines.isEmpty()) {
            segments.put("medicines", "detail.seg.medicines");
        }
        if (!doctor.isEmpty()) {
            segments.put("doctor", "detail.seg.doctor");
        }
        segments.put("pages", "detail.seg.pages");
        b.append("<nav id=\"seg\" class=\"seg\" aria-label=\"").append(copy.t(lang, "detail.seg.label")).append("\">");
        segments.forEach((id, key) -> b.append("<a href=\"#").append(id).append("\">").append(copy.t(lang, key)).append("</a>"));
        b.append("</nav>").append(valuesBlock).append(medicines).append(doctor).append(detailPages(lang, v, documentId, pages));
        if (r.owner()) {
            b.append("<p><a class=\"btn quiet\" href=\"").append(BASE).append("/documents/").append(documentId).append("/delete\">")
                    .append(copy.t(lang, "detail.delete")).append("</a></p><p class=\"small\">").append(copy.t(lang, "detail.delete.note")).append("</p>");
        }
        return b.toString();
    }

    /** s17 মান: the count note, then outside (critical first), unknown with why, inside — every row, each opening its sheet. */
    private String detailValues(Lang lang, DocumentView v, UUID documentId, List<Map<String, Object>> values) {
        int unverified = unverified(v, "values");
        if (values.isEmpty() && unverified == 0) {
            return "";
        }
        StringBuilder b = new StringBuilder("<section id=\"values\"><h2>").append(copy.t(lang, "detail.seg.values")).append("</h2>");
        if (!values.isEmpty()) {
            b.append("<p class=\"meta\">").append(copy.t(lang, "detail.values.note", values.size())).append("</p>");
        }
        if (unverified > 0) {
            b.append("<p class=\"warn\">").append(copy.t(lang, "value.unverified")).append("</p>");
        }
        List<Map<String, Object>> outside = new ArrayList<>();
        List<Map<String, Object>> unknown = new ArrayList<>();
        List<Map<String, Object>> inside = new ArrayList<>();
        for (Map<String, Object> row : values) {
            switch (ValueRows.kindOf(row)) {
                case CRITICAL, OUTSIDE -> outside.add(row);
                case UNKNOWN -> unknown.add(row);
                case INSIDE -> inside.add(row);
            }
        }
        outside.sort(Comparator.comparing(row -> ValueRows.kindOf(row) == ValueVerdict.Kind.CRITICAL ? 0 : 1));   // stable
        detailGroup(b, lang, documentId, "value.group.outside", null, outside);
        detailGroup(b, lang, documentId, "value.group.unknown", "value.group.unknown.why", unknown);
        detailGroup(b, lang, documentId, "value.group.inside", null, inside);
        return b.append("</section>").toString();
    }

    private void detailGroup(StringBuilder b, Lang lang, UUID documentId, String title, String why, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        b.append("<div class=\"vgroup\"><h3>").append(copy.t(lang, "detail.group.count", copy.raw(lang, title), rows.size())).append("</h3>");
        if (why != null) {
            b.append("<p class=\"why\">").append(copy.t(lang, why)).append("</p>");
        }
        rows.forEach(row -> b.append(ValueRows.row(copy, lang, documentId, row, ValueRows.FROM_ALL)));
        b.append("</div>");
    }

    /** s17 ডাক্তার লিখেছেন: the context sections (not the referral) and the follow-ups, each line as written beside its crop. */
    private String detailDoctor(Lang lang, DocumentView v, UUID documentId) {
        String quotes = contextQuotes(lang, v, documentId);
        String followUps = followUpQuotes(lang, v, documentId);
        int unverified = unverified(v, "follow_up");
        if (quotes.isEmpty() && followUps.isEmpty() && unverified == 0) {
            return "";
        }
        // DR-28, as the summary's পরের ধাপ says it: follow-up lines read but not located on the page were not stored
        String notice = unverified == 0 ? "" : "<p class=\"warn\">" + copy.t(lang, "next.unverified", unverified) + "</p>";
        return "<section id=\"doctor\" class=\"doctor\"><h2>" + copy.t(lang, "detail.seg.doctor") + "</h2>" + quotes
                + (followUps.isEmpty() && notice.isEmpty() ? "" : "<h3>" + copy.t(lang, "next.title") + "</h3>" + followUps + notice)
                + "</section>";
    }

    /** s17 মূল কাগজ: every page whole, each opening the page image itself (where the browser pinch-zooms). */
    private String detailPages(Lang lang, DocumentView v, UUID documentId, int pages) {
        StringBuilder b = new StringBuilder("<section id=\"pages\"><h2>").append(copy.t(lang, "detail.seg.pages")).append("</h2><p class=\"meta\">")
                .append(copy.t(lang, "prescription".equals(v.documentType()) ? "detail.pages.rx" : "detail.pages.note")).append("</p>");
        for (int n = 1; n <= pages; n++) {
            String href = BASE + "/documents/" + documentId + "/page/" + n;
            b.append("<figure id=\"page-").append(n).append("\" class=\"pagefig\"><img class=\"page-img\" loading=\"lazy\" alt=\"\" src=\"")
                    .append(href).append("\"><figcaption><span>").append(copy.t(lang, "detail.page", n)).append("</span>")
                    .append("<a class=\"btn quiet\" href=\"").append(href).append("\">").append(copy.t(lang, "crop.zoom")).append("</a></figcaption></figure>");
        }
        return b.append("</section>").toString();
    }

    /**
     * s13, the value sheet: a page styled as a sheet whose close goes back to the same row ({@code from}: the summary or
     * the detail). The crop large with its zoom, the value as printed, the range, the chip, and the bar only through
     * {@link ValueRows#pin} ({@link ValueRows#sheetValue}); the "could not match" sentence for an UNKNOWN; then the earlier
     * readings in the same unit, each beside its own crop on its own paper. No glossary until D3 is signed.
     */
    String valueSheet(Lang lang, ResultView r, UUID observationId, List<EarlierReading> earlier, String from) {
        DocumentView doc = r.document();
        UUID documentId = PageChrome.uuid(doc.documentId());
        Map<String, Object> v = (doc.values() == null ? List.<Map<String, Object>>of() : doc.values()).stream()
                .filter(row -> observationId.toString().equals(String.valueOf(row.get("id")))).findFirst()
                .orElseThrow(() -> BaymaxException.notFound("observation_not_found", "no such value on document"));
        String close = (ValueRows.FROM_ALL.equals(from) ? BASE + "/documents/" + documentId + "/all" : BASE + "/documents/" + documentId)
                + "#v-" + observationId;
        String src = PageChrome.imageHref(documentId, ValueRows.crop(v.get("crop_key"), "a value"));
        StringBuilder b = new StringBuilder("<section class=\"sheet\" role=\"dialog\" aria-modal=\"true\" aria-labelledby=\"sheet-title\">")
                .append("<div class=\"sheet-h\"><div><h1 id=\"sheet-title\">").append(esc(v.get("name"))).append("</h1><p class=\"meta\">")
                .append(copy.t(lang, "sheet.of", r.patient().name(), paperDateText(lang, r),
                        typeText(lang, doc.documentType())))
                .append("</p></div><a class=\"icon-btn\" href=\"").append(close).append("\" aria-label=\"").append(copy.t(lang, "sheet.close"))
                .append("\">").append(Icons.svg("x", 24)).append("</a></div>")
                .append("<a class=\"sheet-crop\" href=\"").append(src).append("\"><img class=\"crop big\" alt=\"\" src=\"").append(src)
                .append("\"><span class=\"zoom\">").append(copy.t(lang, "crop.zoom")).append("</span></a>")
                .append(ValueRows.sheetValue(copy, lang, v));
        if (ValueRows.kindOf(v) == ValueVerdict.Kind.UNKNOWN) {
            b.append("<p class=\"why\">").append(copy.t(lang, "sheet.unknown.why")).append("</p>");
        }
        return b.append(earlierReadings(lang, v, earlier)).append("</section>").toString();
    }

    /**
     * The paper's date as unescaped text: its printed date, else its upload day on the patient's calendar marked
     * "(আপলোডের তারিখ)", as the timeline marks it. Never empty, so no meta line starts or a subtitle sits on " ·  · ".
     */
    private String paperDateText(Lang lang, ResultView r) {
        String printed = r.document().documentDate();
        if (printed != null && !printed.isBlank()) {
            return dueText(lang, printed);
        }
        return r.receivedAt() == null ? copy.raw(lang, "timeline.uploaddate")
                : copy.dateText(lang, PatientCalendar.dayOf(r.receivedAt())) + " " + copy.raw(lang, "timeline.uploaddate");
    }

    /** The result's and the detail's meta line, escaped: the paper's date, then the facility only when one was read. */
    private String metaLine(Lang lang, ResultView r) {
        String facility = r.document().facility();
        return esc(paperDateText(lang, r)) + (facility == null || facility.isBlank() ? "" : " · " + esc(facility));
    }

    /**
     * The document type as unescaped text, for an argument to {@link UiCopy#t} (which escapes it once): its label, the
     * default label when none was read, or the type as stored when the bundle has no label for it (as documentType does).
     */
    private String typeText(Lang lang, String type) {
        if (type == null) {
            return copy.raw(lang, "type.default");
        }
        return copy.maybe(lang, "type." + type) != null ? copy.raw(lang, "type." + type) : type;
    }

    /**
     * আগের রিপোর্টে: one compact row per earlier reading, its date on the patient's calendar, or "first time". A value whose
     * name did not canonicalise has no history Medioo could look up (ResultService returns none), so the sheet claims
     * neither earlier readings nor a first time. Each row opens its own paper's sheet from the detail ({@code from=all}),
     * which draws every row, so that sheet's close lands on a row that is on the page.
     */
    private String earlierReadings(Lang lang, Map<String, Object> v, List<EarlierReading> earlier) {
        if (v.get("canonical_name") == null) {
            return "";
        }
        StringBuilder b = new StringBuilder("<section id=\"earlier\" class=\"earlier\">");
        if (earlier.isEmpty()) {
            return b.append("<p>").append(copy.t(lang, "sheet.earlier.none")).append("</p></section>").toString();
        }
        b.append("<h2>").append(v.get("unit") == null ? copy.t(lang, "sheet.earlier.nounit") : copy.t(lang, "sheet.earlier", String.valueOf(v.get("unit"))))
                .append("</h2>");
        for (EarlierReading e : earlier) {
            String src = PageChrome.imageHref(e.documentId(), ValueRows.crop(e.cropKey(), "an earlier value"));
            b.append("<a class=\"erow\" href=\"").append(ValueRows.sheetHref(e.documentId(), e.observationId(), ValueRows.FROM_ALL)).append("\">")
                    .append("<span class=\"vhead\"><span class=\"edate\">").append(e.date() == null ? "" : copy.date(lang, e.date()))
                    .append(e.dateIsFallback() ? " <span class=\"meta\">" + copy.t(lang, "timeline.uploaddate") + "</span>" : "")
                    .append("</span> <span class=\"val\">").append(esc(e.value())).append("</span>")
                    .append(e.unit() == null ? "" : " <span class=\"unit\">" + esc(e.unit()) + "</span>").append("</span>")
                    .append("<img class=\"crop\" loading=\"lazy\" alt=\"\" src=\"").append(src).append("\"></a>");
        }
        return b.append("</section>").toString();
    }

    String render(DocumentView view, UUID documentId) {
        return render(Lang.BN, view, documentId);
    }

    /**
     * The pre-A12 document page, which production no longer serves (B5: every status has its own page). Kept for the
     * item tests, which call it with a DONE view and get the same {@link #items} the summary shows; the page that shows
     * everything on a paper is B6's {@link #detail}. A view that is not DONE gets its page images and the delete link.
     */
    String render(Lang lang, DocumentView view, UUID documentId) {
        StringBuilder b = new StringBuilder(new AppShell(copy).back(lang, Tab.PAPERS.path, "app.back.papers"));
        b.append("<h1>").append(chrome.documentType(lang, view.documentType())).append(" ").append(chrome.status(lang, view.status())).append("</h1>");
        b.append("<div class=\"meta\">").append(copy.date(lang, view.documentDate()))
                .append(view.facility() == null ? "" : " · " + esc(view.facility())).append("</div>");

        if (!"DONE".equals(view.status())) {
            return b.append(pageImages(view, documentId)).append(deleteLink(lang, documentId)).toString();
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
                + "<p><a class=\"btn quiet\" href=\"" + BASE + "/documents/" + documentId + "/all\">" + copy.t(lang, "result.all") + "</a></p>"
                + "<p><a class=\"btn quiet\" href=\"" + Tab.CHAT.path + "\">" + copy.t(lang, "result.ask") + "</a></p>"
                + deleteLink(lang, documentId);
    }

    /**
     * Spec §4.10: outside first (critical before outside), then the values Medioo could not place with why, then inside
     * — the first row, and the rest behind "আরও {n}টি … সব দেখুন", which opens the detail at its values (B6, s10). Only
     * inside rows collapse: a value that needs a look is never one tap away. Grouped by {@link ValueRows#kindOf}, the kind
     * each row is drawn as. A row of a kind in {@code shownAbove} is drawn once, in its NOW reason block, and not again here.
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
        group(b, lang, documentId, "outside", "value.group.outside", null, outside, "");
        group(b, lang, documentId, "unknown", "value.group.unknown", "value.group.unknown.why", unknown, "");
        String more = inside.size() <= 1 ? "" : "<a class=\"more\" href=\"" + BASE + "/documents/" + documentId + "/all#values\">"
                + copy.t(lang, "value.more.inside", inside.size() - 1) + "</a>";
        group(b, lang, documentId, "inside", "value.group.inside", null, inside.isEmpty() ? inside : inside.subList(0, 1), more);
        return b.toString();
    }

    private void group(StringBuilder b, Lang lang, UUID documentId, String id, String title, String why, List<Map<String, Object>> rows,
                       String after) {
        if (rows.isEmpty()) {
            return;
        }
        b.append("<section id=\"").append(id).append("\" class=\"vgroup\"><h2>").append(copy.t(lang, title)).append("</h2>");
        if (why != null) {
            b.append("<p class=\"why\">").append(copy.t(lang, why)).append("</p>");
        }
        rows.forEach(v -> b.append(ValueRows.row(copy, lang, documentId, v, ValueRows.FROM_SUMMARY)));
        b.append(after).append("</section>");
    }

    /**
     * ডাক্তার লিখেছেন (DR-26, principle 6): every clinical-context section but the referral, each line quoted verbatim
     * under "কাগজে লেখা" beside its crop. A line stored without a crop is not quoted: no fact without its proof.
     */
    String doctorSection(Lang lang, DocumentView view, UUID documentId) {
        String inner = contextQuotes(lang, view, documentId);
        return inner.isEmpty() ? "" : "<section id=\"doctor\" class=\"doctor\"><h2>" + copy.t(lang, "doctor.title") + "</h2>" + inner + "</section>";
    }

    /** Every clinical-context section but the referral, each under its label, each line quoted beside its crop. */
    private String contextQuotes(Lang lang, DocumentView view, UUID documentId) {
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
        return inner.toString();
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

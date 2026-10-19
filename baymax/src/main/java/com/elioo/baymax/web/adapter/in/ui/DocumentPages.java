package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.domain.ReadingView;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.elioo.baymax.web.adapter.in.ui.Html.esc;
import static com.elioo.baymax.web.adapter.in.ui.PageChrome.BASE;

/**
 * One document: its extracted items beside their crops, its pages, and its deletion. Images are signed afresh on every
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
     * GET /app/documents/{id}: while the paper is RECEIVED or PROCESSING, the reading page (s09); otherwise the document
     * page as before (A12 replaces DONE, B5 NEEDS_RETAKE and FAILED). The branch reads the status alone, which records
     * nothing, so a finished paper's page never reads the reading view and records the one DOCUMENT_VIEW of the document
     * page. A paper finished between the two reads is drawn finished, never as a reading page that would wait for a
     * change that already happened; that page records two DOCUMENT_VIEW rows (the reading view's and the document
     * page's), accepted for a window of milliseconds rather than a second audit path.
     */
    public Mono<ServerResponse> documentPage(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        Lang lang = Lang.of(request);
        UUID documentId = PageChrome.uuid(request.pathVariable("id"));
        return results.status(session.familyId(), documentId).flatMap(status -> !inFlight(status)
                ? finishedPage(session, lang, documentId)
                : results.reading(session.familyId(), documentId).flatMap(r -> inFlight(Document.Status.valueOf(r.document().status()))
                        ? Html.html(HttpStatus.OK, progressPage(lang, r))
                        : finishedPage(session, lang, documentId)));
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

    private Mono<ServerResponse> finishedPage(WebSession session, Lang lang, UUID documentId) {
        return timeline.document(session.familyId(), documentId)
                .zipWith(timeline.explanationOf(session.familyId(), documentId).map(Optional::of).defaultIfEmpty(Optional.empty()))
                .flatMap(t -> Html.html(HttpStatus.OK, new AppShell(copy).inside(lang, Tab.PAPERS, render(lang, t.getT1(), documentId, t.getT2()))));
    }

    String render(DocumentView view, UUID documentId) {
        return render(Lang.BN, view, documentId, Optional.empty());
    }

    /**
     * The page as the ticket specifies it: extracted items beside their crops, and for a retake nothing but why
     * and the page.
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

        // F1 (interim, A12 replaces it): the stored level's fixed line, at once and whatever the explanation's state
        if (view.urgency() != null && !"routine".equals(view.urgency())) {
            Urgency level = Urgency.fromDbValue(view.urgency());
            b.append("<section class=\"banner ").append(level == Urgency.NOW ? "now" : "soon")
                    .append("\" id=\"urgency\" role=\"status\"><p class=\"head\">")
                    .append(copy.urgency(lang, level, false)).append("</p></section>");
        }

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

        if (view.unverified() != null) {
            Object total = view.unverified().get("total");
            b.append("<div class=\"warn\">").append(copy.t(lang, "doc.unverified", total)).append("</div>");
        }
        section(b, copy.t(lang, "doc.values"), view.values(), documentId, m -> esc(m.get("name")) + ": <strong>" + esc(m.get("value")) + "</strong> " + esc(m.get("unit")));
        section(b, copy.t(lang, "doc.medicines"), view.medicines(), documentId, m -> "<strong>" + esc(m.get("name")) + "</strong> " + esc(m.get("dose_text"))
                + "<div class=\"meta\">" + esc(m.get("frequency_text")) + " " + esc(m.get("timing_text")) + " " + esc(m.get("duration_text")) + "</div>");
        section(b, copy.t(lang, "doc.followup"), view.followUp(), documentId, m -> esc(m.get("instruction"))
                + (m.get("due_date") == null ? "" : "<div class=\"meta\">" + copy.date(lang, String.valueOf(m.get("due_date"))) + "</div>"));
        if (view.clinicalContext() != null && !view.clinicalContext().isEmpty()) {
            b.append("<h2>").append(copy.t(lang, "doc.context")).append("</h2>");
            for (Map.Entry<String, Object> sec : view.clinicalContext().entrySet()) {
                String label = chrome.contextSection(lang, sec.getKey());
                if (label != null && sec.getValue() instanceof List<?> items && !items.isEmpty()) {
                    b.append("<div class=\"card\"><div class=\"meta\">").append(label).append("</div>");
                    for (Object item : items) {
                        if (item instanceof Map<?, ?> m) {
                            b.append("<div class=\"item\"><div class=\"text\">").append(esc(m.get("text"))).append("</div>");
                            if (m.get("crop_key") != null) {
                                b.append("<img class=\"crop\" loading=\"lazy\" alt=\"\" src=\"").append(PageChrome.imageHref(documentId, String.valueOf(m.get("crop_key")))).append("\">");
                            }
                            b.append("</div>");
                        }
                    }
                    b.append("</div>");
                }
            }
        }
        b.append("<h2>").append(copy.t(lang, "doc.pages")).append("</h2>").append(pageImages(view, documentId));
        b.append(deleteLink(lang, documentId));
        return b.toString();
    }

    private void section(StringBuilder b, String title, List<Map<String, Object>> items, UUID documentId,
                         java.util.function.Function<Map<String, Object>, String> line) {
        if (items == null || items.isEmpty()) {
            return;
        }
        b.append("<h2>").append(title).append("</h2><div class=\"card\">");
        for (Map<String, Object> m : items) {
            b.append("<div class=\"item\"><div class=\"text\">").append(line.apply(m)).append("</div>");
            if (m.get("crop_key") != null) {
                b.append("<img class=\"crop\" loading=\"lazy\" alt=\"\" src=\"").append(PageChrome.imageHref(documentId, String.valueOf(m.get("crop_key")))).append("\">");
            }
            b.append("</div>");
        }
        b.append("</div>");
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

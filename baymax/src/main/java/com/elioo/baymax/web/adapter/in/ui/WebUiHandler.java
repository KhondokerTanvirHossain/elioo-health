package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.TimelineEntry;
import com.elioo.baymax.web.domain.WebSession;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.elioo.baymax.web.adapter.in.ui.Html.esc;

/**
 * The landing page at {@code /} and the family's pages under {@code /app}: number → code → patients →
 * timeline → document. Every app page is built from the same use cases the JSON API uses, so what the
 * browser sees is exactly what the API allows. Strings come from {@link UiCopy} in the language the
 * {@code baymax_lang} cookie names (Bangla by default).
 *
 * <p>The phone number is never in a URL: between the number page and the code page it travels as its HMAC
 * in a short-lived HttpOnly cookie. Images are never linked by signed URL: {@code <img src>} points at
 * {@code /app/documents/{id}/image?key=…}, which signs afresh on every load, so a page left open past the
 * 15-minute signature never shows a broken image.
 */
@Component
@RequiredArgsConstructor
public class WebUiHandler {

    static final String BASE = "/app";
    static final String LOGIN_COOKIE = "baymax_login";
    /** Files under classpath {@code baymax/ui/} that {@code /assets/{file}} may serve; nothing else leaves the jar. */
    static final Set<String> ASSETS = Set.of("baymax.css", "landing-shot.png", "mascot.png", "mascot.svg", "mascot.webp");

    private final WebAuthUseCase auth;
    private final TimelineUseCase timeline;
    private final SessionAuthFilter sessions;
    private final UiCopy copy;

    // ---- landing, language, assets --------------------------------------------------------------------

    public Mono<ServerResponse> landing(ServerRequest request) {
        Lang lang = Lang.of(request);
        String t = copy.t(lang, "app.title");
        StringBuilder b = new StringBuilder();
        b.append("<div class=\"top\"><a class=\"brand\" href=\"/\"><span class=\"dot\"></span>").append(t).append("</a>")
                .append("<div class=\"actions\">").append(langToggle(lang, "/"))
                .append("<a class=\"btn\" href=\"").append(BASE).append("\">").append(copy.t(lang, "landing.login")).append("</a></div></div>");
        b.append("<section class=\"hero\"><div><h1>").append(t).append("</h1>")
                .append("<p class=\"lead\"><strong>").append(copy.t(lang, "app.tagline")).append("</strong></p>")
                .append("<p class=\"lead\">").append(copy.t(lang, "landing.lead")).append("</p>")
                .append("<p class=\"cta\"><a class=\"btn\" href=\"").append(BASE).append("\">").append(copy.t(lang, "landing.cta")).append("</a></p></div>")
                .append("<div class=\"mascot\"><div class=\"mascot-slot\">")
                .append(Html.MASCOT == null ? "" : "<img alt=\"\" src=\"" + Html.MASCOT + "\">")
                .append("</div></div></section>");
        b.append("<section class=\"section\"><h2>").append(copy.t(lang, "landing.how")).append("</h2><div class=\"steps\">");
        for (int i = 1; i <= 3; i++) {
            b.append("<div class=\"step\"><div class=\"n\">").append(i).append("</div><div><strong>")
                    .append(copy.t(lang, "landing.step" + i + ".title")).append("</strong><div class=\"meta\">")
                    .append(copy.t(lang, "landing.step" + i + ".text")).append("</div></div></div>");
        }
        b.append("</div></section>");
        b.append("<section class=\"section\"><h2>").append(copy.t(lang, "landing.shot")).append("</h2>")
                .append("<div class=\"shot\"><img alt=\"\" loading=\"lazy\" width=\"360\" height=\"740\" src=\"").append(Html.LANDING_SHOT).append("\"></div>")
                .append("<p class=\"small\">").append(copy.t(lang, "landing.shot.caption")).append("</p></section>");
        b.append("<section class=\"section\"><h2>").append(copy.t(lang, "landing.plans")).append("</h2><div class=\"plans\">");
        plan(b, lang, "free", 4, "plan");
        plan(b, lang, "family", 5, "plan family");
        b.append("</div></section>");
        b.append("<section class=\"section\"><h2>").append(copy.t(lang, "landing.data")).append("</h2><ul class=\"facts\">");
        for (int i = 1; i <= 4; i++) {
            b.append("<li>").append(copy.t(lang, "landing.data." + i)).append("</li>");
        }
        b.append("</ul></section>");
        return Html.html(HttpStatus.OK, Html.page(lang, t, b.toString(), 0, true, copy.t(lang, "disclaimer"), true));
    }

    private void plan(StringBuilder b, Lang lang, String key, int lines, String cls) {
        b.append("<div class=\"").append(cls).append("\"><strong>").append(copy.t(lang, "landing.plan." + key)).append("</strong>")
                .append("<div class=\"price\">").append(copy.t(lang, "landing.plan." + key + ".price")).append("</div><ul>");
        for (int i = 1; i <= lines; i++) {
            b.append("<li>").append(copy.t(lang, "landing.plan." + key + "." + i)).append("</li>");
        }
        b.append("</ul></div>");
    }

    /** {@code GET /lang/{code}?back=/path}: set the cookie, go back. Only a local path is followed. */
    public Mono<ServerResponse> switchLang(ServerRequest request) {
        Lang lang = Lang.parse(request.pathVariable("code"));
        String back = request.queryParam("back").filter(p -> p.startsWith("/") && !p.startsWith("//")).orElse("/");
        return ServerResponse.seeOther(URI.create(back))
                .header(HttpHeaders.SET_COOKIE, lang.cookie(sessions.cookie("x").isSecure()).toString()).build();
    }

    /** {@code GET /assets/{file}}: the stylesheet and the landing images from the jar, cacheable for a day. */
    public Mono<ServerResponse> asset(ServerRequest request) {
        String file = request.pathVariable("file");
        if (!ASSETS.contains(file)) {
            return ServerResponse.notFound().build();
        }
        InputStream in = WebUiHandler.class.getResourceAsStream("/baymax/ui/" + file);
        if (in == null) {
            return ServerResponse.notFound().build();
        }
        byte[] bytes;
        try (in) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        MediaType type = file.endsWith(".css") ? new MediaType("text", "css", java.nio.charset.StandardCharsets.UTF_8)
                : file.endsWith(".svg") ? MediaType.valueOf("image/svg+xml")
                : file.endsWith(".webp") ? MediaType.valueOf("image/webp") : MediaType.IMAGE_PNG;
        return ServerResponse.ok().contentType(type).cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .eTag("\"" + Integer.toHexString(java.util.Arrays.hashCode(bytes)) + "\"").bodyValue(bytes);
    }

    // ---- login -----------------------------------------------------------------------------------

    public Mono<ServerResponse> loginPage(ServerRequest request) {
        Lang lang = Lang.of(request);
        return sessions.resolve(request).flatMap(s -> Html.redirect(BASE + "/home"))
                .switchIfEmpty(Mono.defer(() -> app(lang, top(lang, null, false) + """
                        <h1>%s</h1>
                        <form method="post" action="%s/login">
                          <label for="n">%s</label>
                          <input id="n" type="tel" name="whatsapp_number" placeholder="01XXXXXXXXX" autocomplete="tel" required>
                          <button type="submit">%s</button>
                          <p class="small">%s</p>
                        </form>
                        """.formatted(copy.t(lang, "app.home.title"), BASE, copy.t(lang, "login.number"),
                        copy.t(lang, "login.send"), copy.t(lang, "login.hint")))));
    }

    public Mono<ServerResponse> login(ServerRequest request) {
        return request.formData()
                .map(form -> Optional.ofNullable(form.getFirst("whatsapp_number")).orElse(""))
                .flatMap(auth::requestCode)
                .flatMap(hash -> ServerResponse.seeOther(URI.create(BASE + "/verify"))
                        .header(HttpHeaders.SET_COOKIE, loginCookie(hash, Duration.ofMinutes(10)).toString())
                        .build());
    }

    public Mono<ServerResponse> verifyPage(ServerRequest request) {
        return verifyPage(request, null);
    }

    private Mono<ServerResponse> verifyPage(ServerRequest request, String error) {
        if (loginHash(request).isEmpty()) {
            return Html.redirect(BASE + "/");
        }
        Lang lang = Lang.of(request);
        return app(lang, top(lang, null, false) + """
                <h1>%s</h1>
                %s
                <form method="post" action="%s/verify">
                  <label for="c">%s</label>
                  <input id="c" type="text" name="code" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" autocomplete="one-time-code" required>
                  <button type="submit">%s</button>
                </form>
                <p class="small"><a href="%s/">%s</a></p>
                """.formatted(copy.t(lang, "app.home.title"), error == null ? "" : "<div class=\"err\">" + error + "</div>", BASE,
                copy.t(lang, "login.code"), copy.t(lang, "login.enter"), BASE, copy.t(lang, "login.other")));
    }

    public Mono<ServerResponse> verify(ServerRequest request) {
        Optional<String> hash = loginHash(request);
        if (hash.isEmpty()) {
            return Html.redirect(BASE + "/");
        }
        return request.formData()
                .map(form -> Optional.ofNullable(form.getFirst("code")).orElse("").trim())
                .flatMap(code -> auth.verify(hash.get(), code))
                .flatMap(issued -> ServerResponse.seeOther(URI.create(BASE + "/home"))
                        .header(HttpHeaders.SET_COOKIE, sessions.cookie(issued.token()).toString())
                        .header(HttpHeaders.SET_COOKIE, loginCookie("", Duration.ZERO).toString())
                        .build())
                .onErrorResume(BaymaxException.class, e -> e.status() == HttpStatus.UNAUTHORIZED
                        ? verifyPage(request, copy.t(Lang.of(request), "login.bad"))
                        : Mono.error(e));
    }

    public Mono<ServerResponse> logout(ServerRequest request) {
        return Mono.justOrEmpty(sessions.token(request)).flatMap(auth::logout)
                .then(ServerResponse.seeOther(URI.create(BASE + "/"))
                        .header(HttpHeaders.SET_COOKIE, sessions.clearedCookie().toString()).build());
    }

    // ---- pages behind a session ----------------------------------------------------------------------

    public Mono<ServerResponse> home(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        Lang lang = Lang.of(request);
        return timeline.me(session.familyId()).flatMap(me -> {
            StringBuilder b = new StringBuilder(top(lang, me.family().ownerName(), true));
            b.append("<h2>").append(copy.t(lang, "patients")).append("</h2>");
            if (me.patients().isEmpty()) {
                b.append("<div class=\"card\">").append(copy.t(lang, "patients.none")).append("</div>");
            }
            for (PatientSummary p : me.patients()) {
                b.append("<a class=\"card\" href=\"").append(BASE).append("/patients/").append(p.patient().id()).append("\">")
                        .append("<strong>").append(esc(p.patient().name())).append("</strong> ")
                        .append("<span class=\"meta\">").append(copy.t(lang, "patients.years", p.patient().age())).append("</span>")
                        .append(p.owner() ? "" : " <span class=\"tag\">" + copy.t(lang, "patients.shared") + "</span>")
                        .append("<div class=\"meta\">").append(copy.t(lang, "patients.docs", p.documents()))
                        .append(p.lastDocumentAt() == null ? "" : " · " + copy.t(lang, "patients.last", p.lastDocumentAt().toString().substring(0, 10)))
                        .append("</div></a>");
            }
            return app(lang, b.toString());
        });
    }

    public Mono<ServerResponse> timelinePage(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        Lang lang = Lang.of(request);
        UUID patientId = uuid(request.pathVariable("id"));
        String cursor = request.queryParam("cursor").orElse(null);
        return timeline.me(session.familyId()).flatMap(me -> {
            PatientSummary patient = me.patients().stream().filter(p -> p.patient().id().equals(patientId)).findFirst()
                    .orElseThrow(() -> BaymaxException.notFound("patient_not_found", "no patient with id " + patientId));
            return timeline.timeline(session.familyId(), patientId, cursor).flatMap(page -> {
                StringBuilder b = new StringBuilder(top(lang, me.family().ownerName(), true));
                b.append("<p class=\"small\"><a href=\"").append(BASE).append("/home\">").append(copy.t(lang, "app.back.patients")).append("</a></p>");
                b.append("<h1>").append(esc(patient.patient().name())).append("</h1>");
                if (patient.owner()) {
                    b.append("<form class=\"card\" method=\"post\" enctype=\"multipart/form-data\" action=\"")
                            .append(BASE).append("/patients/").append(patientId).append("/upload\">")
                            .append("<label for=\"f\">").append(copy.t(lang, "timeline.add")).append("</label>")
                            .append("<input id=\"f\" type=\"file\" name=\"file\" accept=\"image/*,application/pdf\" required>")
                            .append("<button type=\"submit\">").append(copy.t(lang, "timeline.upload")).append("</button></form>");
                }
                b.append("<h2>").append(copy.t(lang, "timeline.docs")).append("</h2>");
                if (page.entries().isEmpty()) {
                    b.append("<div class=\"card\">").append(copy.t(lang, "timeline.none")).append("</div>");
                }
                for (TimelineEntry e : page.entries()) {
                    String href = BASE + "/documents/" + e.documentId();
                    b.append("<div class=\"card\"><div class=\"row\">")
                            .append("<a href=\"").append(href).append("\"><img alt=\"\" loading=\"lazy\" src=\"")
                            .append(imageHref(e.documentId(), e.pageOneKey())).append("\"></a>")
                            .append("<div><div><a href=\"").append(href).append("\"><strong>").append(documentType(lang, e.documentType()))
                            .append("</strong></a> ").append(status(lang, e.status())).append("</div>")
                            .append("<div class=\"meta\">").append(esc(e.date().toString()))
                            .append(e.dateIsFallback() ? " <span class=\"small\">" + copy.t(lang, "timeline.uploaddate") + "</span>" : "")
                            .append(e.facility() == null || e.facility().isBlank() ? "" : " · " + esc(e.facility())).append("</div>");
                    if ("DONE".equals(e.status())) {
                        b.append("<div class=\"meta\">").append(copy.t(lang, "timeline.counts", e.values(), e.medicines(), e.followUps())).append("</div>");
                        if (e.unverified() > 0) {
                            b.append("<div class=\"small\">").append(copy.t(lang, "timeline.unverified", e.unverified())).append("</div>");
                        }
                    }
                    b.append("</div></div></div>");
                }
                if (page.nextCursor() != null) {
                    b.append("<p><a class=\"btn quiet\" href=\"").append(BASE).append("/patients/").append(patientId)
                            .append("?cursor=").append(esc(page.nextCursor())).append("\">").append(copy.t(lang, "timeline.more")).append("</a></p>");
                }
                // a document still being read: reload every few seconds until it is not
                boolean inFlight = page.entries().stream().anyMatch(e -> "RECEIVED".equals(e.status()) || "PROCESSING".equals(e.status()));
                return Html.html(HttpStatus.OK, Html.page(lang, copy.t(lang, "app.home.title"), b.toString(), inFlight ? 5 : 0, false,
                        copy.t(lang, "disclaimer"), false));
            });
        });
    }

    public Mono<ServerResponse> upload(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID patientId = uuid(request.pathVariable("id"));
        return request.multipartData().flatMap(parts -> {
            List<Part> files = parts.get("file");
            if (files == null || files.isEmpty()) {
                return Mono.error(BaymaxException.badRequest("no_file", copy.t(Lang.of(request), "timeline.nofile")));
            }
            return Flux.fromIterable(files).filter(FilePart.class::isInstance).cast(FilePart.class)
                    .concatMap(WebUiHandler::readBytes).collectList()
                    .flatMap(uploads -> timeline.upload(session.familyId(), patientId, uploads));
        }).flatMap(d -> Html.redirect(BASE + "/patients/" + patientId));
    }

    public Mono<ServerResponse> documentPage(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        Lang lang = Lang.of(request);
        UUID documentId = uuid(request.pathVariable("id"));
        return timeline.document(session.familyId(), documentId)
                .zipWith(timeline.explanationOf(session.familyId(), documentId).map(Optional::of).defaultIfEmpty(Optional.empty()))
                .flatMap(t -> app(lang, render(lang, t.getT1(), documentId, t.getT2())));
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
        StringBuilder b = new StringBuilder(top(lang, null, true));
        b.append("<p class=\"small\"><a href=\"javascript:history.back()\">").append(copy.t(lang, "app.back")).append("</a></p>");
        b.append("<h1>").append(documentType(lang, view.documentType())).append(" ").append(status(lang, view.status())).append("</h1>");
        b.append("<div class=\"meta\">").append(esc(view.documentDate() == null ? "" : view.documentDate()))
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

        if (view.unverified() != null) {
            Object total = view.unverified().get("total");
            b.append("<div class=\"warn\">").append(copy.t(lang, "doc.unverified", total)).append("</div>");
        }
        section(b, copy.t(lang, "doc.values"), view.values(), documentId, m -> esc(m.get("name")) + ": <strong>" + esc(m.get("value")) + "</strong> " + esc(m.get("unit")));
        section(b, copy.t(lang, "doc.medicines"), view.medicines(), documentId, m -> "<strong>" + esc(m.get("name")) + "</strong> " + esc(m.get("dose_text"))
                + "<div class=\"meta\">" + esc(m.get("frequency_text")) + " " + esc(m.get("timing_text")) + " " + esc(m.get("duration_text")) + "</div>");
        section(b, copy.t(lang, "doc.followup"), view.followUp(), documentId, m -> esc(m.get("instruction"))
                + (m.get("due_date") == null ? "" : "<div class=\"meta\">" + esc(m.get("due_date")) + "</div>"));
        if (view.clinicalContext() != null && !view.clinicalContext().isEmpty()) {
            b.append("<h2>").append(copy.t(lang, "doc.context")).append("</h2>");
            for (Map.Entry<String, Object> sec : view.clinicalContext().entrySet()) {
                String label = contextSection(lang, sec.getKey());
                if (label != null && sec.getValue() instanceof List<?> items && !items.isEmpty()) {
                    b.append("<div class=\"card\"><div class=\"meta\">").append(label).append("</div>");
                    for (Object item : items) {
                        if (item instanceof Map<?, ?> m) {
                            b.append("<div class=\"item\"><div class=\"text\">").append(esc(m.get("text"))).append("</div>");
                            if (m.get("crop_key") != null) {
                                b.append("<img class=\"crop\" loading=\"lazy\" alt=\"\" src=\"").append(imageHref(documentId, String.valueOf(m.get("crop_key")))).append("\">");
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
                b.append("<img class=\"crop\" loading=\"lazy\" alt=\"\" src=\"").append(imageHref(documentId, String.valueOf(m.get("crop_key")))).append("\">");
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
        return "<p class=\"small\" style=\"margin-top:var(--sp-6)\"><a href=\"" + BASE + "/documents/" + documentId + "/delete\">"
                + copy.t(lang, "doc.delete") + "</a></p>";
    }

    public Mono<ServerResponse> confirmDelete(ServerRequest request) {
        UUID documentId = uuid(request.pathVariable("id"));
        Lang lang = Lang.of(request);
        return app(lang, top(lang, null, true) + """
                <h1>%s</h1>
                <div class="warn">%s</div>
                <form method="post" action="%s/documents/%s/delete">
                  <button type="submit" class="danger">%s</button>
                  <a class="btn quiet" href="%s/documents/%s">%s</a>
                </form>
                """.formatted(copy.t(lang, "doc.delete.q"), copy.t(lang, "doc.delete.warn"), BASE, documentId,
                copy.t(lang, "doc.delete.yes"), BASE, documentId, copy.t(lang, "doc.delete.no")));
    }

    public Mono<ServerResponse> delete(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        Lang lang = Lang.of(request);
        UUID documentId = uuid(request.pathVariable("id"));
        return timeline.deleteDocument(session.familyId(), documentId).flatMap(report -> app(lang, top(lang, null, true)
                + "<h1>" + copy.t(lang, "doc.deleted") + "</h1><div class=\"card\">" + copy.t(lang, "doc.deleted.objects", report.objectsDeleted())
                + "<br>" + copy.t(lang, "doc.deleted.rows", report.ledgerRowsDeleted()) + "</div>"
                + "<p><a class=\"btn\" href=\"" + BASE + "/home\">" + copy.t(lang, "doc.deleted.back") + "</a></p>"));
    }

    // ---- images: signed afresh on every load -------------------------------------------------------

    public Mono<ServerResponse> image(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID documentId = uuid(request.pathVariable("id"));
        String key = request.queryParam("key").orElseThrow(() -> BaymaxException.badRequest("invalid_request", "key is required"));
        return timeline.imageUrl(session.familyId(), documentId, key)
                .flatMap(url -> ServerResponse.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, url.toString())
                        .header(HttpHeaders.CACHE_CONTROL, "private, no-store").build());
    }

    public Mono<ServerResponse> pageImage(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID documentId = uuid(request.pathVariable("id"));
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

    static String imageHref(UUID documentId, String key) {
        return BASE + "/documents/" + documentId + "/image?key=" + java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---- shared pieces -----------------------------------------------------------------------------------

    /** An app page: the column layout, this language, the disclaimer footer, not indexed. */
    Mono<ServerResponse> app(Lang lang, String body) {
        return Html.html(HttpStatus.OK, Html.page(lang, copy.t(lang, "app.home.title"), body, 0, false, copy.t(lang, "disclaimer"), false));
    }

    /** A small error page in the app shell, in the request's language. */
    Mono<ServerResponse> errorPage(ServerRequest request, HttpStatus status, String text) {
        Lang lang = Lang.of(request);
        return Html.html(status, Html.page(lang, copy.t(lang, "app.home.title"),
                "<h1>" + copy.t(lang, "app.sorry") + "</h1><div class=\"err\">" + text + "</div>"
                        + "<p><a class=\"btn quiet\" href=\"" + BASE + "/home\">" + copy.t(lang, "app.err.return") + "</a></p>",
                0, false, copy.t(lang, "disclaimer"), false));
    }

    String text(ServerRequest request, String key, Object... args) {
        return copy.t(Lang.of(request), key, args);
    }

    /** The top bar: brand home, language toggle, and the logout button when signed in. */
    private String top(Lang lang, String who, boolean signedIn) {
        String here = signedIn ? BASE + "/home" : BASE + "/";
        return "<div class=\"top\"><a class=\"brand\" href=\"" + here + "\"><span class=\"dot\"></span>" + copy.t(lang, "app.title") + "</a>"
                + "<div class=\"actions\">" + langToggle(lang, here)
                + (signedIn ? "<form method=\"post\" action=\"" + BASE + "/logout\"><button type=\"submit\" class=\"quiet\">"
                + (who == null ? "" : "<span class=\"who\">" + esc(who) + " · </span>") + copy.t(lang, "app.logout") + "</button></form>" : "")
                + "</div></div>";
    }

    private String langToggle(Lang lang, String back) {
        String q = "?back=" + java.net.URLEncoder.encode(back, java.nio.charset.StandardCharsets.UTF_8);
        return "<nav class=\"lang\" aria-label=\"language\">"
                + "<a href=\"/lang/bn" + q + "\" aria-current=\"" + (lang == Lang.BN) + "\" hreflang=\"bn\">" + copy.t(lang, "lang.bn") + "</a>"
                + "<a href=\"/lang/en" + q + "\" aria-current=\"" + (lang == Lang.EN) + "\" hreflang=\"en\">" + copy.t(lang, "lang.en") + "</a></nav>";
    }

    /** Label for a document type; the raw value if unknown, never a guess. */
    String documentType(Lang lang, String type) {
        if (type == null) {
            return copy.t(lang, "type.default");
        }
        String label = copy.maybe(lang, "type." + type);
        return label != null ? label : esc(type);
    }

    /**
     * Label for a clinical-context section, or null for one the UI does not show. {@code advice} is the
     * doctor's own words transcribed from the page, shown under "as written on the prescription" (PO ruling,
     * 2026-09-18): "no advice in this UI" means no Baymax-generated advice, which still holds absolutely —
     * nothing here is composed, reordered or interpreted. {@code referral} is a single field, not a list.
     */
    String contextSection(Lang lang, String key) {
        if (key == null || "referral".equals(key)) {
            return null;
        }
        String label = copy.maybe(lang, "ctx." + key);
        return label != null ? label : esc(key);
    }

    String status(Lang lang, String status) {
        if (status == null) {
            return "";
        }
        String cls = "NEEDS_RETAKE".equals(status) || "FAILED".equals(status) ? "tag retake" : "tag";
        String label = copy.maybe(lang, "status." + status);
        return "<span class=\"" + cls + "\">" + (label != null ? label : copy.t(lang, "status.PROCESSING")) + "</span>";
    }

    private Optional<String> loginHash(ServerRequest request) {
        HttpCookie c = request.cookies().getFirst(LOGIN_COOKIE);
        return Optional.ofNullable(c).map(HttpCookie::getValue).filter(v -> v.matches("[0-9a-f]{64}"));
    }

    private ResponseCookie loginCookie(String value, Duration maxAge) {
        return ResponseCookie.from(LOGIN_COOKIE, value).httpOnly(true).secure(sessions.cookie("x").isSecure())
                .sameSite("Lax").path(BASE).maxAge(maxAge).build();
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw BaymaxException.notFound("not_found", "no such page");
        }
    }

    private static Mono<Upload> readBytes(FilePart part) {
        return DataBufferUtils.join(part.content()).map(buffer -> {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            DataBufferUtils.release(buffer);
            return new Upload(part.filename(), bytes);
        });
    }
}

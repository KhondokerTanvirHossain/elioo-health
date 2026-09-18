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
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.elioo.baymax.web.adapter.in.ui.Html.esc;

/**
 * The family's pages under {@code /baymax/}: number → code → patients → timeline → document. Every page is
 * built from the same use cases the JSON API uses, so what the browser sees is exactly what the API allows.
 *
 * <p>The phone number is never in a URL: between the number page and the code page it travels as its HMAC
 * in a short-lived HttpOnly cookie. Images are never linked by signed URL: {@code <img src>} points at
 * {@code /baymax/documents/{id}/image?key=…}, which signs afresh on every load, so a page left open past the
 * 15-minute signature never shows a broken image.
 */
@Component
@RequiredArgsConstructor
public class WebUiHandler {

    static final String BASE = "/baymax";
    static final String LOGIN_COOKIE = "baymax_login";
    static final String TITLE = "আপনার স্বাস্থ্য নথি";

    private final WebAuthUseCase auth;
    private final TimelineUseCase timeline;
    private final SessionAuthFilter sessions;

    // ---- login -----------------------------------------------------------------------------------

    public Mono<ServerResponse> loginPage(ServerRequest request) {
        return sessions.resolve(request).flatMap(s -> Html.redirect(BASE + "/home"))
                .switchIfEmpty(Mono.defer(() -> Html.ok(TITLE, """
                        <h1>%s</h1>
                        <form method="post" action="%s/login">
                          <label for="n">আপনার হোয়াটসঅ্যাপ নম্বর</label>
                          <input id="n" type="tel" name="whatsapp_number" placeholder="+8801XXXXXXXXX" autocomplete="tel" required>
                          <button type="submit">কোড পাঠান</button>
                          <p class="small">এই নম্বরে একটি ৬ সংখ্যার কোড পাঠানো হবে।</p>
                        </form>
                        """.formatted(esc(TITLE), BASE))));
    }

    public Mono<ServerResponse> login(ServerRequest request) {
        return request.formData()
                .map(form -> Optional.ofNullable(form.getFirst("whatsapp_number")).orElse(""))
                .flatMap(auth::requestCode)
                .flatMap(hash -> ServerResponse.seeOther(java.net.URI.create(BASE + "/verify"))
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
        return Html.ok(TITLE, """
                <h1>%s</h1>
                %s
                <form method="post" action="%s/verify">
                  <label for="c">৬ সংখ্যার কোড</label>
                  <input id="c" type="text" name="code" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" autocomplete="one-time-code" required>
                  <button type="submit">প্রবেশ করুন</button>
                </form>
                <p class="small"><a href="%s/">অন্য নম্বর দিন</a></p>
                """.formatted(esc(TITLE), error == null ? "" : "<div class=\"err\">" + esc(error) + "</div>", BASE, BASE));
    }

    public Mono<ServerResponse> verify(ServerRequest request) {
        Optional<String> hash = loginHash(request);
        if (hash.isEmpty()) {
            return Html.redirect(BASE + "/");
        }
        return request.formData()
                .map(form -> Optional.ofNullable(form.getFirst("code")).orElse("").trim())
                .flatMap(code -> auth.verify(hash.get(), code))
                .flatMap(issued -> ServerResponse.seeOther(java.net.URI.create(BASE + "/home"))
                        .header(HttpHeaders.SET_COOKIE, sessions.cookie(issued.token()).toString())
                        .header(HttpHeaders.SET_COOKIE, loginCookie("", Duration.ZERO).toString())
                        .build())
                .onErrorResume(BaymaxException.class, e -> e.status() == HttpStatus.UNAUTHORIZED
                        ? verifyPage(request, "কোডটি সঠিক নয়, মেয়াদ শেষ, বা ব্যবহার করা হয়েছে। আবার চেষ্টা করুন বা নতুন কোড নিন।")
                        : Mono.error(e));
    }

    public Mono<ServerResponse> logout(ServerRequest request) {
        return Mono.justOrEmpty(sessions.token(request)).flatMap(auth::logout)
                .then(ServerResponse.seeOther(java.net.URI.create(BASE + "/"))
                        .header(HttpHeaders.SET_COOKIE, sessions.clearedCookie().toString()).build());
    }

    // ---- pages behind a session ----------------------------------------------------------------------

    public Mono<ServerResponse> home(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        return timeline.me(session.familyId()).flatMap(me -> {
            StringBuilder b = new StringBuilder(top(esc(me.family().ownerName())));
            b.append("<h2>রোগী</h2>");
            if (me.patients().isEmpty()) {
                b.append("<div class=\"card\">এখনও কোনো রোগীর প্রোফাইল নেই।</div>");
            }
            for (PatientSummary p : me.patients()) {
                b.append("<a class=\"card\" style=\"display:block;text-decoration:none;color:inherit\" href=\"")
                        .append(BASE).append("/patients/").append(p.patient().id()).append("\">")
                        .append("<strong>").append(esc(p.patient().name())).append("</strong> ")
                        .append("<span class=\"meta\">").append(p.patient().age()).append(" বছর</span>")
                        .append(p.owner() ? "" : " <span class=\"tag\">শেয়ার করা</span>")
                        .append("<div class=\"meta\">নথি: ").append(p.documents())
                        .append(p.lastDocumentAt() == null ? "" : " · শেষ নথি: " + esc(p.lastDocumentAt().toString().substring(0, 10)))
                        .append("</div></a>");
            }
            return Html.ok(TITLE, b.toString());
        });
    }

    public Mono<ServerResponse> timelinePage(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID patientId = uuid(request.pathVariable("id"));
        String cursor = request.queryParam("cursor").orElse(null);
        return timeline.me(session.familyId()).flatMap(me -> {
            PatientSummary patient = me.patients().stream().filter(p -> p.patient().id().equals(patientId)).findFirst()
                    .orElseThrow(() -> BaymaxException.notFound("patient_not_found", "no patient with id " + patientId));
            return timeline.timeline(session.familyId(), patientId, cursor).flatMap(page -> {
                StringBuilder b = new StringBuilder(top(esc(me.family().ownerName())));
                b.append("<p class=\"small\"><a href=\"").append(BASE).append("/home\">← রোগী</a></p>");
                b.append("<h1>").append(esc(patient.patient().name())).append("</h1>");
                if (patient.owner()) {
                    b.append("<form class=\"card\" method=\"post\" enctype=\"multipart/form-data\" action=\"")
                            .append(BASE).append("/patients/").append(patientId).append("/upload\">")
                            .append("<label for=\"f\">নতুন নথি যোগ করুন</label>")
                            .append("<input id=\"f\" type=\"file\" name=\"file\" accept=\"image/*,application/pdf\" required>")
                            .append("<button type=\"submit\">আপলোড করুন</button></form>");
                }
                b.append("<h2>নথিসমূহ</h2>");
                if (page.entries().isEmpty()) {
                    b.append("<div class=\"card\">এখনও কোনো নথি নেই।</div>");
                }
                for (TimelineEntry e : page.entries()) {
                    String href = BASE + "/documents/" + e.documentId();
                    b.append("<div class=\"card\"><div class=\"row\">")
                            .append("<a href=\"").append(href).append("\"><img alt=\"\" loading=\"lazy\" src=\"")
                            .append(imageHref(e.documentId(), e.pageOneKey())).append("\"></a>")
                            .append("<div><div><a href=\"").append(href).append("\"><strong>").append(Html.documentType(e.documentType()))
                            .append("</strong></a> ").append(Html.status(e.status())).append("</div>")
                            .append("<div class=\"meta\">").append(esc(e.date().toString()))
                            .append(e.dateIsFallback() ? " <span class=\"small\">(আপলোডের তারিখ)</span>" : "")
                            .append(e.facility() == null || e.facility().isBlank() ? "" : " · " + esc(e.facility())).append("</div>");
                    if ("DONE".equals(e.status())) {
                        b.append("<div class=\"meta\">মান ").append(e.values()).append(" · ওষুধ ").append(e.medicines())
                                .append(" · ফলো-আপ ").append(e.followUps()).append("</div>");
                        if (e.unverified() > 0) {
                            b.append("<div class=\"small\">").append(e.unverified()).append(" টি অংশ মূল ছবিতে খুঁজে পাওয়া যায়নি</div>");
                        }
                    }
                    b.append("</div></div></div>");
                }
                if (page.nextCursor() != null) {
                    b.append("<p><a class=\"btn quiet\" href=\"").append(BASE).append("/patients/").append(patientId)
                            .append("?cursor=").append(esc(page.nextCursor())).append("\">আরও দেখুন</a></p>");
                }
                return Html.ok(TITLE, b.toString());
            });
        });
    }

    public Mono<ServerResponse> upload(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID patientId = uuid(request.pathVariable("id"));
        return request.multipartData().flatMap(parts -> {
            List<Part> files = parts.get("file");
            if (files == null || files.isEmpty()) {
                return Mono.error(BaymaxException.badRequest("no_file", "একটি ফাইল বেছে নিন"));
            }
            return Flux.fromIterable(files).filter(FilePart.class::isInstance).cast(FilePart.class)
                    .concatMap(WebUiHandler::readBytes).collectList()
                    .flatMap(uploads -> timeline.upload(session.familyId(), patientId, uploads));
        }).flatMap(d -> Html.redirect(BASE + "/patients/" + patientId));
    }

    public Mono<ServerResponse> documentPage(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID documentId = uuid(request.pathVariable("id"));
        return timeline.document(session.familyId(), documentId)
                .zipWith(timeline.explanationOf(session.familyId(), documentId).map(Optional::of).defaultIfEmpty(Optional.empty()))
                .flatMap(t -> Html.ok(TITLE, render(t.getT1(), documentId, t.getT2())));
    }

    String render(DocumentView view, UUID documentId) {
        return render(view, documentId, Optional.empty());
    }

    /** The page as the ticket specifies it: extracted items beside their crops, and for a retake nothing but why and the page. */
    /**
     * @param explanation the released BMX-6 message for this document, shown above the extraction exactly as
     *                    composed — it is the only interpretive text on the page and it passed the checklist
     */
    String render(DocumentView view, UUID documentId, Optional<String> explanation) {
        StringBuilder b = new StringBuilder(top(null));
        b.append("<p class=\"small\"><a href=\"javascript:history.back()\">← ফিরে যান</a></p>");
        b.append("<h1>").append(Html.documentType(view.documentType())).append(" ").append(Html.status(view.status())).append("</h1>");
        b.append("<div class=\"meta\">").append(esc(view.documentDate() == null ? "" : view.documentDate()))
                .append(view.facility() == null ? "" : " · " + esc(view.facility())).append("</div>");

        explanation.ifPresent(text -> b.append("<div class=\"card\"><div class=\"meta\">সংক্ষেপে</div><div style=\"white-space:pre-line\">")
                .append(esc(text)).append("</div></div>"));

        if (!"DONE".equals(view.status())) {
            // NEEDS_RETAKE / FAILED / in flight: the reason and the page image, and nothing extracted anywhere
            if (view.reason() != null) {
                b.append("<div class=\"warn\">").append(esc(view.reason())).append("</div>");
            }
            if ("NEEDS_RETAKE".equals(view.status())) {
                b.append("<p>ছবিটি স্পষ্ট নয়। আরও আলোতে, সোজা করে, আবার ছবি তুলে পাঠান।</p>");
            }
            b.append(pageImages(view, documentId));
            b.append(deleteLink(documentId));
            return b.toString();
        }

        if (view.unverified() != null) {
            Object total = view.unverified().get("total");
            b.append("<div class=\"warn\">").append(esc(total)).append(" টি অংশ মূল ছবিতে খুঁজে পাওয়া যায়নি, তাই দেখানো হয়নি।</div>");
        }
        section(b, "মান", view.values(), documentId, m -> esc(m.get("name")) + ": <strong>" + esc(m.get("value")) + "</strong> " + esc(m.get("unit")));
        section(b, "ওষুধ", view.medicines(), documentId, m -> "<strong>" + esc(m.get("name")) + "</strong> " + esc(m.get("dose_text"))
                + "<div class=\"meta\">" + esc(m.get("frequency_text")) + " " + esc(m.get("timing_text")) + " " + esc(m.get("duration_text")) + "</div>");
        section(b, "ফলো-আপ", view.followUp(), documentId, m -> esc(m.get("instruction"))
                + (m.get("due_date") == null ? "" : "<div class=\"meta\">" + esc(m.get("due_date")) + "</div>"));
        if (view.clinicalContext() != null && !view.clinicalContext().isEmpty()) {
            b.append("<h2>ক্লিনিক্যাল নোট</h2>");
            for (Map.Entry<String, Object> sec : view.clinicalContext().entrySet()) {
                String label = Html.contextSection(sec.getKey());
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
        b.append("<h2>মূল ছবি</h2>").append(pageImages(view, documentId));
        b.append(deleteLink(documentId));
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
            b.append("<img class=\"page\" loading=\"lazy\" alt=\"\" src=\"").append(BASE).append("/documents/").append(documentId)
                    .append("/page/").append(n).append("\">");
        }
        return b.toString();
    }

    private static String deleteLink(UUID documentId) {
        return "<p class=\"small\" style=\"margin-top:24px\"><a href=\"" + BASE + "/documents/" + documentId + "/delete\">নথিটি মুছুন</a></p>";
    }

    public Mono<ServerResponse> confirmDelete(ServerRequest request) {
        UUID documentId = uuid(request.pathVariable("id"));
        return Html.ok(TITLE, top(null) + """
                <h1>নথিটি মুছবেন?</h1>
                <div class="warn">এটি মুছে ফেললে নথির ছবি ও পড়া তথ্য স্থায়ীভাবে মুছে যাবে। এটি ফেরানো যাবে না।</div>
                <form method="post" action="%s/documents/%s/delete">
                  <button type="submit" class="danger">হ্যাঁ, মুছুন</button>
                  <a class="btn quiet" href="%s/documents/%s">না, ফিরে যান</a>
                </form>
                """.formatted(BASE, documentId, BASE, documentId));
    }

    public Mono<ServerResponse> delete(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID documentId = uuid(request.pathVariable("id"));
        return timeline.deleteDocument(session.familyId(), documentId).flatMap(report -> Html.ok(TITLE, top(null)
                + "<h1>নথিটি মুছে ফেলা হয়েছে</h1><div class=\"card\">ছবি মুছেছে: " + report.objectsDeleted()
                + "<br>রেকর্ড মুছেছে: " + report.ledgerRowsDeleted() + "</div>"
                + "<p><a class=\"btn\" href=\"" + BASE + "/home\">রোগীর তালিকায় ফিরুন</a></p>"));
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

    // ---- helpers -----------------------------------------------------------------------------------

    private static String top(String who) {
        return "<div class=\"top\"><a href=\"" + BASE + "/home\" style=\"text-decoration:none;font-weight:700\">" + esc(TITLE) + "</a>"
                + "<form method=\"post\" action=\"" + BASE + "/logout\"><button type=\"submit\" class=\"quiet\" style=\"margin:0;padding:6px 10px\">"
                + (who == null ? "" : esc(who) + " · ") + "বের হন</button></form></div>";
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

package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.healthrecord.domain.DocumentAllowance;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.UploadUseCase;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.UploadOptions;
import com.elioo.baymax.web.domain.WebSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.elioo.baymax.web.adapter.in.ui.Html.esc;
import static com.elioo.baymax.web.adapter.in.ui.PageChrome.BASE;

/**
 * The add-report sheet (spec 4.7, s07), the one way a family adds a paper on the web, and the legacy form post. The
 * sheet's form posts as it is, with no script, to {@code POST /app/add} ({@code /assets/upload.js}, D4, only enhances
 * it), and lands on the document's page; a refusal comes back on the sheet in the family's language. Never guesses the patient: preselected only when the viewer may send for exactly one, or when the link names one
 * of those the viewer may send for; an id it may not send for is ignored, never trusted. The free allowance is stated
 * before any upload, from the same value intake refuses by ({@link DocumentAllowance}).
 *
 * <p>Logs carry counts, bytes and ids only. A file's own name may name the patient and is never logged or shown.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadUiHandler implements WebPages {

    static final String SHEET = BASE + "/add";

    private final UploadUseCase uploads;
    private final UiCopy copy;
    /** The per-file and per-request byte caps ({@code baymax.upload.*}) and the page limit the refusal names. */
    private final BaymaxProperties properties;

    @Override
    public int order() {
        return 30;   // signed in
    }

    @Override
    public RouterFunction<ServerResponse> routes(HandlerFilterFunction<ServerResponse, ServerResponse> requireSession) {
        return RouterFunctions.route()
                .GET(SHEET, this::sheet)
                .POST(SHEET, this::submit)
                .POST(BASE + "/patients/{id}/upload", this::legacy)
                .filter(requireSession)
                .build();
    }

    /** {@code GET /app/add?patient=<id>&back=<local path>}: the sheet, drawn over the tab {@code back} belongs to. */
    public Mono<ServerResponse> sheet(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        Lang lang = Lang.of(request);
        String back = back(request);
        Optional<String> asked = request.queryParam("patient");
        return uploads.uploadOptions(session.familyId()).flatMap(options -> Html.html(HttpStatus.OK,
                new AppShell(copy).sheet(lang, Tab.of(back), "add.title", sheetBody(lang, options, asked, back, null))));
    }

    String sheetBody(Lang lang, UploadOptions o, Optional<String> asked, String back, String alert) {
        StringBuilder b = new StringBuilder("<section class=\"sheet\" role=\"dialog\" aria-modal=\"true\" aria-labelledby=\"sheet-title\">");
        b.append("<div class=\"sheet-h\"><h1 id=\"sheet-title\">").append(copy.t(lang, "add.title")).append("</h1>")
                .append("<a class=\"icon-btn\" href=\"").append(esc(back)).append("\" aria-label=\"").append(copy.t(lang, "sheet.close"))
                .append("\">").append(Icons.svg("x", 24)).append("</a></div>");
        b.append("<p id=\"add-alert\" class=\"warn\" role=\"alert\"").append(alert == null ? " hidden>" : ">" + alert).append("</p>");
        DocumentAllowance a = o.allowance();
        if (o.canSend().isEmpty() || !a.allows(1)) {
            String why = !o.canSend().isEmpty() ? capText(lang, a)
                    : copy.t(lang, o.viewOnly().isEmpty() ? "add.nopatient" : "add.viewonly");
            return b.append("<p class=\"card\">").append(why).append("</p><a class=\"btn quiet\" href=\"").append(esc(back))
                    .append("\">").append(copy.t(lang, "app.err.return")).append("</a></section>").toString();
        }
        Optional<UUID> chosen = chosen(o, asked);
        b.append("<form id=\"add\" class=\"add\" method=\"post\" enctype=\"multipart/form-data\" action=\"").append(SHEET).append("\">")
                .append("<input type=\"hidden\" name=\"back\" value=\"").append(esc(back)).append("\">");
        b.append("<fieldset class=\"pick\"><legend>").append(copy.t(lang, "add.who")).append("</legend><div class=\"chips\">");
        for (PatientSummary p : o.canSend()) {
            boolean checked = chosen.filter(p.patient().id()::equals).isPresent();
            b.append("<label class=\"chip\"><input type=\"radio\" name=\"patient\" value=\"").append(p.patient().id())
                    .append("\" required").append(checked ? " checked" : "").append(">")
                    .append(copy.t(lang, "add.patient", p.patient().name(), p.patient().age())).append("</label>");
        }
        String hint = copy.t(lang, o.preselected().isPresent() ? "add.onlyone" : "add.choose");
        b.append("</div>");
        if (!hint.equals(alert)) {   // refused for no patient: the alert already says it, and s07 says it once
            b.append("<p class=\"hint\">").append(hint).append("</p>");
        }
        b.append("</fieldset>");
        b.append("<div class=\"src\">")
                .append(row(lang, "camera", "accept=\"image/*\" capture=\"environment\"", "camera"))
                .append(row(lang, "gallery", "accept=\"image/*\" multiple", "papers"))
                .append(row(lang, "pdf", "accept=\"application/pdf,image/*\"", "page"))
                .append("</div>");
        b.append("<button type=\"submit\" class=\"btn primary nojs\">").append(copy.t(lang, "add.send")).append("</button>");
        b.append("<section class=\"tips\" aria-labelledby=\"tips-title\"><h2 id=\"tips-title\">").append(copy.t(lang, "add.tips"))
                .append("</h2><ul>").append(tip(lang, "flat")).append(tip(lang, "light")).append(tip(lang, "frame")).append("</ul></section>");
        if (a.limited()) {
            b.append("<p class=\"quota\">").append(copy.t(lang, "add.quota", a.remaining())).append("</p>");
        }
        return b.append("</form></section>").toString();
    }

    /** The only one the viewer may send for, else the one the link names if the viewer may send for it, else nobody. */
    static Optional<UUID> chosen(UploadOptions o, Optional<String> asked) {
        if (o.preselected().isPresent()) {
            return o.preselected().map(p -> p.patient().id());
        }
        return asked.flatMap(raw -> o.canSend().stream().map(p -> p.patient().id())
                .filter(id -> id.toString().equalsIgnoreCase(raw)).findFirst());
    }

    private String row(Lang lang, String src, String attrs, String icon) {
        return "<label class=\"src-row\"><span class=\"src-i\" aria-hidden=\"true\">" + Icons.svg(icon, 24) + "</span>"
                + "<span class=\"src-t\"><strong>" + copy.t(lang, "add." + src) + "</strong><span>" + copy.t(lang, "add." + src + ".sub")
                + "</span></span><input type=\"file\" name=\"file\" " + attrs + " data-src=\"" + src + "\"></label>";
    }

    private String tip(Lang lang, String which) {
        return "<li><span class=\"tip-i\" aria-hidden=\"true\">" + Icons.svg("tip-" + which, 36) + "</span><span>"
                + copy.t(lang, "add.tip." + which) + "</span></li>";
    }

    String capText(Lang lang, DocumentAllowance a) {
        return copy.t(lang, "add.cap", a.max(), copy.dateText(lang, a.resetsOn()));
    }

    static String back(ServerRequest request) {
        return LocalPath.or(request.queryParam("back").orElse(null), Tab.CHAT.path);
    }

    /** reason → copy key. Completeness against every reason intake can throw is swept by TryDocumentsThroughTheWebTest. */
    static final Map<String, String> REFUSALS = Map.ofEntries(
            Map.entry("choose_patient", "add.choose"),
            Map.entry("no_file", "upload.err.no_file"),
            Map.entry("empty_file", "upload.err.empty_file"),
            Map.entry("unsupported_image", "upload.err.unsupported"),
            Map.entry("encode_failed", "upload.err.unsupported"),
            Map.entry("empty_pdf", "upload.err.pdf"),
            Map.entry("unreadable_pdf", "upload.err.pdf"),
            Map.entry("too_many_pages", "upload.err.too_many_pages"),
            Map.entry("file_too_large", "upload.err.too_large"),
            Map.entry("request_too_large", "upload.err.too_large"),
            Map.entry("shared_patient_read_only", "add.viewonly"));

    /** {@code POST /app/add}: the sheet's form, with or without script. The patient is the one the family chose. */
    public Mono<ServerResponse> submit(ServerRequest request) {
        return receive(request, null);
    }

    /** Today's timeline form (and any page still cached with it): the patient is in the path. */
    public Mono<ServerResponse> legacy(ServerRequest request) {
        return Mono.defer(() -> receive(request, PageChrome.uuid(request.pathVariable("id"))));
    }

    private Mono<ServerResponse> receive(ServerRequest request, UUID pathPatient) {
        WebSession session = SessionAuthFilter.session(request);
        if (request.headers().contentLength().orElse(-1L) > properties.getUpload().getMaxRequestBytes()) {
            return refuse(request, tooLarge("request_too_large"), Optional.ofNullable(pathPatient).map(UUID::toString),
                    backOf(request, null, pathPatient));
        }
        return request.multipartData().flatMap(parts -> {
            UUID patientId = pathPatient != null ? pathPatient : uuidOrNull(field(parts, "patient"));
            String back = backOf(request, field(parts, "back"), pathPatient);
            if (patientId == null) {
                return refuse(request, BaymaxException.badRequest("choose_patient", "no patient chosen"), Optional.empty(), back);
            }
            List<FilePart> files = parts.getOrDefault("file", List.of()).stream()
                    .filter(FilePart.class::isInstance).map(FilePart.class::cast).toList();
            return Flux.fromIterable(files).concatMap(this::readBytes)
                    // an input the family did not use posts filename="" and nothing; a chosen empty file keeps its name
                    .filter(u -> !(u.bytes().length == 0 && (u.filename() == null || u.filename().isBlank())))
                    .collectList()
                    .flatMap(pages -> {
                        long total = pages.stream().mapToLong(u -> u.bytes().length).sum();
                        if (pages.isEmpty()) {
                            return Mono.error(BaymaxException.badRequest("no_file", "attach at least one file"));
                        }
                        if (total > properties.getUpload().getMaxRequestBytes()) {
                            return Mono.error(tooLarge("request_too_large"));
                        }
                        return uploads.upload(session.familyId(), patientId, pages)
                                .doOnNext(d -> log.info("[baymax] web upload documentId={} files={} bytes={}", d.id(), pages.size(), total))
                                .map(d -> BASE + "/documents/" + d.id());
                    })
                    .flatMap(next -> wantsJson(request)
                            ? ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("next", next))
                            : Html.redirect(next))
                    .onErrorResume(BaymaxException.class, e -> refuse(request, e, Optional.of(patientId.toString()), back));
        }).onErrorResume(BaymaxException.class, e -> refuse(request, e, Optional.ofNullable(pathPatient).map(UUID::toString),
                backOf(request, null, pathPatient)));
    }

    private Mono<Upload> readBytes(FilePart part) {
        int max = Math.toIntExact(Math.min(Integer.MAX_VALUE, properties.getUpload().getMaxFileBytes()));
        return DataBufferUtils.join(part.content(), max)
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return new Upload(part.filename(), bytes);
                })
                .defaultIfEmpty(new Upload(part.filename(), new byte[0]))
                .onErrorMap(DataBufferLimitException.class, e -> tooLarge("file_too_large"));
    }

    private static BaymaxException tooLarge(String reason) {
        return new BaymaxException(HttpStatus.PAYLOAD_TOO_LARGE, reason, "upload over the size limit");
    }

    /**
     * The refusal, on the sheet, in the family's language, with the patient they chose still chosen: never the
     * exception's message (developer English, which may carry a filename) and never a filename. A 404 or a missing
     * session goes on to the router's own page.
     */
    private Mono<ServerResponse> refuse(ServerRequest request, BaymaxException e, Optional<String> chosen, String back) {
        if (e.status() == HttpStatus.NOT_FOUND || e.status() == HttpStatus.UNAUTHORIZED) {
            return Mono.error(e);
        }
        Lang lang = Lang.of(request);
        UUID familyId = SessionAuthFilter.session(request).familyId();
        log.info("[baymax] web upload refused reason={} status={}", e.reason(), e.status().value());
        return uploads.uploadOptions(familyId).flatMap(o -> {
            boolean cap = e instanceof FreeTierExceededException;
            String key = REFUSALS.getOrDefault(e.reason(), "app.err.generic");
            int maxPages = properties.getExtract().getMaxPages();
            if (wantsJson(request)) {
                String text = cap ? copy.plain(lang, "add.cap", o.allowance().max(), copy.dateText(lang, o.allowance().resetsOn()))
                        : copy.plain(lang, key, maxPages);
                return ServerResponse.status(e.status()).contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("reason", e.reason(), "text", text));
            }
            // at the cap the sheet itself already says so, with the reset date; the alert does not say it twice
            String alert = cap ? (o.allowance().allows(1) ? capText(lang, o.allowance()) : null) : copy.t(lang, key, maxPages);
            return Html.html(e.status(), new AppShell(copy).sheet(lang, Tab.of(back), "add.title", sheetBody(lang, o, chosen, back, alert)));
        });
    }

    /**
     * JSON only when the caller asks for it by name and not for a page (the script, D4). A browser's form post accepts
     * {@code *}{@code /*}, which is "compatible" with JSON; it must still get the page.
     */
    static boolean wantsJson(ServerRequest request) {
        List<MediaType> accept = request.headers().accept();
        return accept.stream().anyMatch(m -> !m.isWildcardType() && !m.isWildcardSubtype() && MediaType.APPLICATION_JSON.isCompatibleWith(m))
                && accept.stream().noneMatch(m -> !m.isWildcardType() && MediaType.TEXT_HTML.isCompatibleWith(m));
    }

    /** Where the sheet's close goes: the form's own {@code back}, else the patient's papers (legacy), else চ্যাট. */
    private static String backOf(ServerRequest request, String posted, UUID pathPatient) {
        String fallback = pathPatient != null ? Tab.PAPERS.path + "?patient=" + pathPatient : back(request);
        return LocalPath.or(posted, fallback);
    }

    private static String field(MultiValueMap<String, Part> parts, String name) {
        Part part = parts.getFirst(name);
        return part instanceof FormFieldPart f && !f.value().isBlank() ? f.value().trim() : null;
    }

    private static UUID uuidOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

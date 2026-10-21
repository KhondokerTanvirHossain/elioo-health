package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.domain.DocumentAllowance;
import com.elioo.baymax.outbound.application.service.BanglaDate;
import com.elioo.baymax.web.adapter.in.MultipartUploads;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.UploadUseCase;
import com.elioo.baymax.web.domain.Grouping;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.UploadOptions;
import com.elioo.baymax.web.domain.WebSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
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
 * it), and lands on the document's page — or, for several papers ("আলাদা আলাদা", D3), on the patient's papers; a refusal comes back on the sheet in the family's language. Never guesses the patient: preselected only when the viewer may send for exactly one, or when the link names one
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
    /**
     * Where the sheet's {@code upload_key} comes from: a fresh random UUID per draw. Package-private and not final so a
     * test can draw a known key (PhoneNumberGuardTest pins the number-shaped key that once flaked the page sweep).
     */
    java.util.function.Supplier<UUID> uploadKeys = UUID::randomUUID;

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
                page(lang, back, options, sheetBody(lang, options, asked, back, null, Grouping.ONE))));
    }

    /**
     * The sheet's page over the tab {@code back} belongs to. {@code /assets/upload.js} (D4) is loaded only when the sheet
     * draws its form: with no form there is nothing to enhance, and a page without script loads none (spec §10).
     */
    private String page(Lang lang, String back, UploadOptions o, String body) {
        AppShell shell = new AppShell(copy);
        Html.Head head = shell.head(lang, "add.title");
        return shell.sheet(lang, Tab.of(back), formShown(o) ? head.script("upload.js") : head, body);
    }

    /** The sheet draws its form when the viewer may send for someone and the allowance has room for one more paper. */
    private static boolean formShown(UploadOptions o) {
        return !o.canSend().isEmpty() && o.allowance().allows(1);
    }

    /**
     * The sheet. Its form carries a fresh {@code upload_key} each time it is drawn (D3): a double tap or a browser
     * resubmit of this form with the same files is the same press and makes one document (other files are a new press:
     * intake keys the press by the key and the files); a re-drawn sheet, a refusal's included, is a new
     * press. {@code grouping} is the family's answer to s08's question, kept when a refusal draws the sheet again.
     */
    String sheetBody(Lang lang, UploadOptions o, Optional<String> asked, String back, String alert, Grouping grouping) {
        StringBuilder b = new StringBuilder("<section class=\"sheet\" role=\"dialog\" aria-modal=\"true\" aria-labelledby=\"sheet-title\">");
        b.append("<div class=\"sheet-h\"><h1 id=\"sheet-title\">").append(copy.t(lang, "add.title")).append("</h1>")
                .append("<a class=\"icon-btn\" href=\"").append(esc(back)).append("\" aria-label=\"").append(copy.t(lang, "sheet.close"))
                .append("\">").append(Icons.svg("x", 24)).append("</a></div>");
        b.append("<p id=\"add-alert\" class=\"warn\" role=\"alert\"").append(alert == null ? " hidden>" : ">" + alert).append("</p>");
        DocumentAllowance a = o.allowance();
        if (!formShown(o)) {
            String why = !o.canSend().isEmpty() ? capText(lang, a)
                    : copy.t(lang, o.viewOnly().isEmpty() ? "add.nopatient" : "add.viewonly");
            return b.append("<p class=\"card\">").append(why).append("</p><a class=\"btn quiet\" href=\"").append(esc(back))
                    .append("\">").append(copy.t(lang, "app.err.return")).append("</a></section>").toString();
        }
        Optional<UUID> chosen = chosen(o, asked);
        b.append("<form id=\"add\" class=\"add\" method=\"post\" enctype=\"multipart/form-data\" action=\"").append(SHEET).append("\"")
                .append(scriptLimits(lang, a)).append(">")
                .append("<input type=\"hidden\" name=\"upload_key\" value=\"").append(uploadKeys.get()).append("\">")
                .append("<input type=\"hidden\" name=\"back\" value=\"").append(esc(back)).append("\">");
        b.append("<fieldset class=\"pick\"><legend>").append(copy.t(lang, "add.who")).append("</legend><div class=\"chips\">");
        for (PatientSummary p : o.canSend()) {
            boolean checked = chosen.filter(p.patient().id()::equals).isPresent();
            // data-up-title: s09's title for this patient, which upload.js shows while the paper is on its way (principle 9)
            b.append("<label class=\"chip\"><input type=\"radio\" data-up-title=\"")
                    .append(copy.t(lang, "up.title.for", copy.poss(lang, p.patient().name()))).append("\" name=\"patient\" value=\"").append(p.patient().id())
                    .append("\" required").append(checked ? " checked" : "").append(">")
                    .append(copy.t(lang, "add.patient", p.patient().name(), p.patient().age())).append("</label>");
        }
        String hint = o.preselected().map(p -> p.patient().name())
                .map(only -> copy.t(lang, "add.onlyone", copy.poss(lang, only), copy.obj(lang, only)))
                .orElseGet(() -> copy.t(lang, "add.choose"));
        b.append("</div>");
        if (!hint.equals(alert)) {   // refused for no patient: the alert already says it, and s07 says it once
            b.append("<p class=\"hint\">").append(hint).append("</p>");
        }
        b.append("</fieldset>");
        b.append("<div id=\"add-src\" class=\"src\">")
                .append(row(lang, "camera", "accept=\"image/*\" capture=\"environment\"", "camera"))
                .append(row(lang, "gallery", "accept=\"image/*\" multiple", "papers"))
                .append(row(lang, "pdf", "accept=\"application/pdf,image/*\"", "page"))
                .append("</div>");
        boolean separate = grouping == Grouping.SEPARATE;
        // s08's question, asked on the sheet itself so it works with no script: the family chooses, Medioo never matches pages
        b.append("<fieldset id=\"group\" class=\"group\"><legend>").append(copy.t(lang, "add.group.q")).append("</legend><div class=\"opts\">")
                .append(option(lang, "one", !separate)).append(option(lang, "separate", separate))
                .append("</div><p class=\"hint\">").append(copy.t(lang, "add.group.hint")).append("</p></fieldset>");
        b.append(tray(lang)).append(uploadStep(lang));
        b.append("<button type=\"submit\" class=\"btn primary nojs\">").append(copy.t(lang, "add.send")).append("</button>");
        b.append("<section id=\"add-tips\" class=\"tips\" aria-labelledby=\"tips-title\"><h2 id=\"tips-title\">").append(copy.t(lang, "add.tips"))
                .append("</h2><ul>").append(tip(lang, "flat")).append(tip(lang, "light")).append(tip(lang, "frame")).append("</ul></section>");
        if (a.limited()) {
            b.append("<p id=\"add-quota\" class=\"quota\">").append(copy.t(lang, "add.quota", a.remaining())).append("</p>");
        }
        return b.append("</form>").append(scriptCopy(lang, a)).append("</section>").toString();
    }

    /**
     * What upload.js (D4) reads off the form: the limits the server refuses by, as machine values (ASCII digits on every
     * page), the free papers left ({@code -1}: a paying family, no cap), the downscale the server turned on ({@code 0}:
     * off until D5) and the digit set the page counts in.
     */
    private String scriptLimits(Lang lang, DocumentAllowance a) {
        BaymaxProperties.Upload up = properties.getUpload();
        return " data-max-pages=\"" + properties.getExtract().getMaxPages() + "\""
                + " data-remaining=\"" + (a.limited() ? a.remaining() : -1) + "\""
                + " data-max-request-bytes=\"" + up.getMaxRequestBytes() + "\""
                + " data-downscale-edge=\"" + up.getDownscaleMaxEdge() + "\""
                + " data-downscale-quality=\"" + up.getDownscaleQuality() + "\""
                + " data-pass-bytes=\"" + up.getPassThroughBytes() + "\""
                + " data-digits=\"" + (lang == Lang.BN ? BanglaDate.digits(ASCII_DIGITS) : ASCII_DIGITS) + "\"";
    }

    private static final String ASCII_DIGITS = "0123456789";

    /**
     * s08, the page tray, for the script to fill and show; hidden, and inside the form so the group question it takes
     * in stays one of the form's fields. Every button is {@code type="button"}: none of them posts the form by itself.
     */
    private String tray(Lang lang) {
        return "<section id=\"tray\" class=\"tray\" hidden aria-labelledby=\"tray-title\"><h2 id=\"tray-title\" tabindex=\"-1\"></h2>"
                + "<p id=\"tray-order\" class=\"tray-order\">" + copy.t(lang, "tray.order") + "</p>"
                + "<ol id=\"tray-list\" class=\"tray-list\"></ol>"
                + "<button type=\"button\" id=\"tray-more\" class=\"btn quiet\">" + Icons.svg("plus", 20) + "<span>" + copy.t(lang, "tray.more") + "</span></button>"
                + "<p id=\"tray-limit\" class=\"hint\"></p>"
                + "<button type=\"button\" id=\"tray-send\" class=\"btn primary\">" + copy.t(lang, "add.send") + "</button></section>"
                + "<template id=\"t-item\"><li class=\"t\"><span class=\"t-thumb\" aria-hidden=\"true\"></span>"
                + "<span class=\"t-text\"><strong class=\"t-page\"></strong><span class=\"t-src\"></span></span>"
                + "<button type=\"button\" class=\"icon-btn t-up\">" + Icons.svg("chevron-up", 24) + "</button>"
                + "<button type=\"button\" class=\"icon-btn t-down\">" + Icons.svg("chevron-down", 24) + "</button>"
                + "<button type=\"button\" class=\"icon-btn t-remove\">" + Icons.svg("x", 24) + "</button></li></template>";
    }

    /**
     * s09's upload step: the title (the script names the patient, from the chosen chip's {@code data-up-title}), a byte
     * progress bar, the per-page lines, the slow line and the retry, hidden until a send starts. Both titles take focus
     * when their screen opens ({@code tabindex="-1"}), so a phone scrolls to them.
     */
    private String uploadStep(Lang lang) {
        return "<section id=\"up\" class=\"up\" hidden aria-live=\"polite\" aria-labelledby=\"up-title\">"
                + "<h2 id=\"up-title\" tabindex=\"-1\">" + copy.t(lang, "up.title") + "</h2>"
                + "<p id=\"up-count\" class=\"up-count\"></p><progress id=\"up-bar\" max=\"1\" value=\"0\"></progress>"
                + "<ol id=\"up-pages\" class=\"up-pages\"></ol>"
                + "<p id=\"up-slow\" class=\"warn\" hidden>" + copy.t(lang, "up.slow") + "</p>"
                + "<button type=\"button\" id=\"up-retry\" class=\"btn\" hidden>" + copy.t(lang, "up.retry") + "</button>"
                + "<p class=\"hint\">" + copy.t(lang, "up.after") + "</p><p class=\"hint\">" + copy.t(lang, "up.keep") + "</p></section>";
    }

    /**
     * Every string upload.js shows, in the page's language: the script holds no copy (UploadScriptTest derives the keys
     * from the script and holds them to this element). Templates keep their named slots ({n}, {total}, {max}) for the
     * script to fill in the page's digits. A press over the allowance is refused in the server's own words, with the
     * count; a paying family's sheet has no allowance to be over, so it carries no such line.
     */
    private String scriptCopy(Lang lang, DocumentAllowance a) {
        StringBuilder b = new StringBuilder("<div id=\"up-copy\" hidden");
        SCRIPT_COPY.forEach((attr, key) -> b.append(" data-").append(attr).append("=\"").append(copy.t(lang, key)).append('"'));
        if (a.limited()) {
            b.append(" data-over-allowance=\"").append(copy.t(lang, "add.cap.separate", a.remaining())).append('"');
        }
        return b.append("></div>").toString();
    }

    /** {@code data-*} on {@code #up-copy} → its copy key; in the order the script meets them. */
    private static final Map<String, String> SCRIPT_COPY = scriptCopyKeys();

    private static Map<String, String> scriptCopyKeys() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("tray-title", "tray.title");
        m.put("tray-page", "tray.page");
        m.put("tray-src-camera", "tray.src.camera");
        m.put("tray-src-gallery", "tray.src.gallery");
        m.put("tray-src-pdf", "tray.src.pdf");
        m.put("tray-up", "tray.up");
        m.put("tray-down", "tray.down");
        m.put("tray-remove", "tray.remove");
        m.put("tray-limit", "tray.limit");
        m.put("up-count", "up.count");
        m.put("up-sent", "up.sent");
        m.put("up-stuck", "up.stuck");
        m.put("choose-patient", "add.choose");
        m.put("no-file", "upload.err.no_file");
        m.put("unsupported", "upload.err.unsupported");
        m.put("too-large", "upload.err.too_large");
        m.put("signed-out", "upload.err.signed_out");
        m.put("generic", "app.err.generic");
        return java.util.Collections.unmodifiableMap(m);
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

    private String option(Lang lang, String value, boolean checked) {
        return "<label class=\"opt\"><input type=\"radio\" name=\"group\" value=\"" + value + "\"" + (checked ? " checked" : "") + ">"
                + "<span>" + copy.t(lang, "add.group." + value) + "</span></label>";
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
            Map.entry("too_many_files", "upload.err.too_many_files"),
            Map.entry("file_too_large", "upload.err.too_large"),
            Map.entry("request_too_large", "upload.err.too_large"),
            Map.entry("shared_patient_read_only", "add.viewonly"),
            // D3: the same sheet sent again for someone else (Back, another patient, পাঠান); the re-drawn sheet has a new key
            Map.entry("upload_key_reused", "upload.err.key_reused"),
            // D1: a retake page naming a paper that no longer waits for one (only a stale page can send it): no own copy
            Map.entry("not_a_retake", "app.err.generic"));

    /** {@code POST /app/add}: the sheet's form, with or without script. The patient is the one the family chose. */
    public Mono<ServerResponse> submit(ServerRequest request) {
        return receive(request, null);
    }

    /** Today's timeline form (and any page still cached with it): the patient is in the path. */
    public Mono<ServerResponse> legacy(ServerRequest request) {
        return Mono.defer(() -> receive(request, PageChrome.uuid(request.pathVariable("id"))));
    }

    /** The caps and what counts as a file are {@link MultipartUploads}', shared with {@code POST /app/api/uploads}. */
    private Mono<ServerResponse> receive(ServerRequest request, UUID pathPatient) {
        WebSession session = SessionAuthFilter.session(request);
        BaymaxProperties.Upload caps = properties.getUpload();
        Optional<BaymaxException> declared = MultipartUploads.declaredTooLarge(request, caps);
        if (declared.isPresent()) {
            return refuse(request, declared.get(), Optional.ofNullable(pathPatient).map(UUID::toString),
                    backOf(request, null, pathPatient), Grouping.ONE);
        }
        return MultipartUploads.withParts(request, properties, parts -> {
            UUID patientId = pathPatient != null ? pathPatient : MultipartUploads.uuidOrNull(MultipartUploads.field(parts, "patient"));
            String back = backOf(request, MultipartUploads.field(parts, "back"), pathPatient);
            if (patientId == null) {
                return refuse(request, BaymaxException.badRequest("choose_patient", "no patient chosen"), Optional.empty(), back, grouping(parts));
            }
            Grouping grouping = grouping(parts);
            String key = MultipartUploads.uploadKey(parts);
            // D1: the retake page names the paper this photo replaces; the sheet and the legacy form never do
            UUID replaces = MultipartUploads.replacesOrNull(parts);
            // who may send for this patient, and the retake it replaces, are asked before a file is joined into memory
            return uploads.admit(session.familyId(), patientId, replaces)
                    .then(MultipartUploads.files(parts, properties, grouping))
                    .flatMap(pages -> {
                        long total = pages.stream().mapToLong(u -> u.bytes().length).sum();
                        return uploads.upload(session.familyId(), patientId, pages, grouping, key, replaces)
                                .doOnNext(docs -> docs.forEach(d -> log.info("[baymax] web upload documentId={} files={} bytes={} documents={}",
                                        d.id(), pages.size(), total, docs.size())))
                                // one document opens; several land on the patient's papers, where each one is
                                .map(docs -> docs.size() == 1 ? BASE + "/documents/" + docs.get(0).id()
                                        : Tab.PAPERS.path + "?patient=" + patientId);
                    })
                    .flatMap(next -> wantsJson(request)
                            ? ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("next", next))
                            : Html.redirect(next))
                    .onErrorResume(BaymaxException.class, e -> refuse(request, e, Optional.of(patientId.toString()), back, grouping));
        }).onErrorResume(BaymaxException.class, e -> refuse(request, e, Optional.ofNullable(pathPatient).map(UUID::toString),
                backOf(request, null, pathPatient), Grouping.ONE));
    }

    /**
     * The refusal, on the sheet, in the family's language, with the patient they chose still chosen: never the
     * exception's message (developer English, which may carry a filename) and never a filename. A 404 or a missing
     * session goes on to the router's own page.
     */
    private Mono<ServerResponse> refuse(ServerRequest request, BaymaxException e, Optional<String> chosen, String back, Grouping grouping) {
        if (e.status() == HttpStatus.NOT_FOUND || e.status() == HttpStatus.UNAUTHORIZED) {
            return Mono.error(e);
        }
        Lang lang = Lang.of(request);
        UUID familyId = SessionAuthFilter.session(request).familyId();
        log.info("[baymax] web upload refused reason={} status={}", e.reason(), e.status().value());
        return uploads.uploadOptions(familyId).flatMap(o -> {
            boolean cap = e instanceof FreeTierExceededException;
            // D3: "আলাদা আলাদা" takes a slot per file, so it can be refused while slots remain; saying the month is used up
            // would be false, so the line says how many are left
            boolean tooFew = cap && grouping == Grouping.SEPARATE && o.allowance().allows(1);
            String key = REFUSALS.getOrDefault(e.reason(), "app.err.generic");
            int maxPages = properties.getExtract().getMaxPages();
            if (wantsJson(request)) {
                String text = tooFew ? copy.plain(lang, "add.cap.separate", o.allowance().remaining())
                        : cap ? copy.plain(lang, "add.cap", o.allowance().max(), copy.dateText(lang, o.allowance().resetsOn()))
                        : copy.plain(lang, key, maxPages);
                return ServerResponse.status(e.status()).contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("reason", e.reason(), "text", text));
            }
            // at the cap the sheet itself already says so, with the reset date; the alert does not say it twice
            String alert = tooFew ? copy.t(lang, "add.cap.separate", o.allowance().remaining())
                    : cap ? (o.allowance().allows(1) ? capText(lang, o.allowance()) : null) : copy.t(lang, key, maxPages);
            return Html.html(e.status(), page(lang, back, o, sheetBody(lang, o, chosen, back, alert, grouping)));
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

    /**
     * "আলাদা আলাদা" only when the family said so; anything else, or nothing (the legacy form), is one report. Lenient on
     * purpose, unlike {@code POST /app/api/uploads}, which refuses a value it does not know: this form's values are the
     * radio buttons the sheet itself draws, so the only other value it can receive is none at all (a page still cached
     * with the pre-D3 form), and refusing that would turn an old page's paper away.
     */
    private static Grouping grouping(MultiValueMap<String, Part> parts) {
        return "separate".equals(MultipartUploads.field(parts, "group")) ? Grouping.SEPARATE : Grouping.ONE;
    }
}

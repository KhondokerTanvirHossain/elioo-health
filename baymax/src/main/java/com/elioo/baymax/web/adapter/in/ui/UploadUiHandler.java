package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.healthrecord.domain.DocumentAllowance;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.UploadUseCase;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.UploadOptions;
import com.elioo.baymax.web.domain.WebSession;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.elioo.baymax.web.adapter.in.ui.Html.esc;
import static com.elioo.baymax.web.adapter.in.ui.PageChrome.BASE;

/**
 * The add-report sheet (spec 4.7, s07), the one way a family adds a paper on the web, and the legacy form post. The
 * sheet's form posts as it is, with no script ({@code POST /app/add} is A8; {@code /assets/upload.js}, D4, only enhances
 * it). Never guesses the patient: preselected only when the viewer may send for exactly one, or when the link names one
 * of those the viewer may send for; an id it may not send for is ignored, never trusted. The free allowance is stated
 * before any upload, from the same value intake refuses by ({@link DocumentAllowance}).
 *
 * <p>Logs carry counts, bytes and ids only. A file's own name may name the patient and is never logged or shown.</p>
 */
@Component
@RequiredArgsConstructor
public class UploadUiHandler implements WebPages {

    static final String SHEET = BASE + "/add";

    private final UploadUseCase uploads;
    private final PageChrome chrome;
    private final UiCopy copy;

    @Override
    public int order() {
        return 30;   // signed in
    }

    @Override
    public RouterFunction<ServerResponse> routes(HandlerFilterFunction<ServerResponse, ServerResponse> requireSession) {
        return RouterFunctions.route()
                .GET(SHEET, this::sheet)
                .POST(BASE + "/patients/{id}/upload", this::upload)
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
        b.append("<form id=\"add\" class=\"add\" method=\"post\" enctype=\"multipart/form-data\" action=\"").append(SHEET).append("\">");
        b.append("<fieldset class=\"pick\"><legend>").append(copy.t(lang, "add.who")).append("</legend><div class=\"chips\">");
        for (PatientSummary p : o.canSend()) {
            boolean checked = chosen.filter(p.patient().id()::equals).isPresent();
            b.append("<label class=\"chip\"><input type=\"radio\" name=\"patient\" value=\"").append(p.patient().id())
                    .append("\" required").append(checked ? " checked" : "").append(">")
                    .append(copy.t(lang, "add.patient", p.patient().name(), p.patient().age())).append("</label>");
        }
        b.append("</div><p class=\"hint\">").append(copy.t(lang, o.preselected().isPresent() ? "add.onlyone" : "add.choose"))
                .append("</p></fieldset>");
        b.append("<div class=\"src\">")
                .append(row(lang, "camera", "accept=\"image/*\" capture=\"environment\"", "camera"))
                .append(row(lang, "gallery", "accept=\"image/*\" multiple", "papers"))
                .append(row(lang, "pdf", "accept=\"application/pdf,image/*\"", "page"))
                .append("</div>");
        b.append("<button type=\"submit\" class=\"btn primary nojs\">").append(copy.t(lang, "add.send")).append("</button>");
        b.append("<section class=\"tips\" aria-labelledby=\"tips-title\"><h2 id=\"tips-title\">").append(copy.t(lang, "add.tips"))
                .append("</h2><ul><li>").append(copy.t(lang, "add.tip.flat")).append("</li><li>").append(copy.t(lang, "add.tip.light"))
                .append("</li><li>").append(copy.t(lang, "add.tip.frame")).append("</li></ul></section>");
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

    String capText(Lang lang, DocumentAllowance a) {
        return copy.t(lang, "add.cap", a.max(), copy.dateText(lang, a.resetsOn()));
    }

    static String back(ServerRequest request) {
        return LocalPath.or(request.queryParam("back").orElse(null), Tab.CHAT.path);
    }

    /** The legacy form post (pages cached before A7): kept as a route; A8 moves it onto the sheet's submit. */
    public Mono<ServerResponse> upload(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID patientId = PageChrome.uuid(request.pathVariable("id"));
        return request.multipartData().flatMap(parts -> {
            List<Part> files = parts.get("file");
            if (files == null || files.isEmpty()) {
                return Mono.error(BaymaxException.badRequest("no_file", chrome.text(request, "timeline.nofile")));
            }
            return Flux.fromIterable(files).filter(FilePart.class::isInstance).cast(FilePart.class)
                    .concatMap(UploadUiHandler::readBytes).collectList()
                    .flatMap(pages -> uploads.upload(session.familyId(), patientId, pages));
        }).flatMap(d -> Html.redirect(Tab.PAPERS.path + "?patient=" + patientId));
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

package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.TimelineEntry;
import com.elioo.baymax.web.domain.WebSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static com.elioo.baymax.web.adapter.in.ui.Html.esc;
import static com.elioo.baymax.web.adapter.in.ui.PageChrome.BASE;

/** The family's patients and a patient's timeline. */
@Component
@RequiredArgsConstructor
public class AppTabs implements WebPages {

    private final TimelineUseCase timeline;
    private final UiCopy copy;
    private final PageChrome chrome;

    @Override
    public int order() {
        return 20;   // signed in
    }

    @Override
    public RouterFunction<ServerResponse> routes(HandlerFilterFunction<ServerResponse, ServerResponse> requireSession) {
        return RouterFunctions.route()
                .GET(BASE + "/home", this::chat)
                .GET(BASE + "/timeline", this::papers)
                .GET(BASE + "/family", this::family)
                .GET(BASE + "/patients/{id}", this::timelinePage)
                .filter(requireSession)
                .build();
    }

    public Mono<ServerResponse> chat(ServerRequest request) {
        return Html.html(HttpStatus.OK, "");
    }

    public Mono<ServerResponse> papers(ServerRequest request) {
        return Html.html(HttpStatus.OK, "");
    }

    public Mono<ServerResponse> family(ServerRequest request) {
        return Html.html(HttpStatus.OK, "");
    }

    public Mono<ServerResponse> home(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        Lang lang = Lang.of(request);
        return timeline.me(session.familyId()).flatMap(me -> {
            StringBuilder b = new StringBuilder(chrome.top(lang, me.family().ownerName(), true));
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
                        .append(p.lastDocumentAt() == null ? "" : " · " + copy.t(lang, "patients.last", copy.dateText(lang, java.time.LocalDate.ofInstant(p.lastDocumentAt(), java.time.ZoneOffset.UTC))))
                        .append("</div></a>");
            }
            return chrome.app(lang, b.toString());
        });
    }

    public Mono<ServerResponse> timelinePage(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        Lang lang = Lang.of(request);
        UUID patientId = PageChrome.uuid(request.pathVariable("id"));
        String cursor = request.queryParam("cursor").orElse(null);
        return timeline.me(session.familyId()).flatMap(me -> {
            PatientSummary patient = me.patients().stream().filter(p -> p.patient().id().equals(patientId)).findFirst()
                    .orElseThrow(() -> BaymaxException.notFound("patient_not_found", "no patient with id " + patientId));
            return timeline.timeline(session.familyId(), patientId, cursor).flatMap(page -> {
                StringBuilder b = new StringBuilder(chrome.top(lang, me.family().ownerName(), true));
                b.append("<p class=\"small\"><a href=\"").append(BASE).append("/home\">").append(copy.t(lang, "app.back.patients")).append("</a></p>");
                b.append("<h1>").append(esc(patient.patient().name())).append("</h1>");
                b.append("<p class=\"small\"><a href=\"").append(BASE).append("/patients/").append(patientId).append("/profile\">").append(copy.t(lang, "profile.link")).append("</a></p>");
                // BMX-8 (PO option 1): a patient with no chronic flags is asked once, here, where the family already is —
                // nothing infers them, and the silence rule can never fire until someone answers
                if (patient.patient().chronicFlags() == null || patient.patient().chronicFlags().isEmpty()) {
                    b.append("<div class=\"card ask\"><strong>").append(copy.t(lang, "timeline.flags.ask")).append("</strong>")
                            .append("<p class=\"small\">").append(copy.t(lang, "timeline.flags.why")).append("</p>")
                            .append("<a class=\"btn quiet\" href=\"").append(BASE).append("/patients/").append(patientId).append("/profile\">")
                            .append(copy.t(lang, "timeline.flags.cta")).append("</a></div>");
                }
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
                            .append(PageChrome.imageHref(e.documentId(), e.pageOneKey())).append("\"></a>")
                            .append("<div><div><a href=\"").append(href).append("\"><strong>").append(chrome.documentType(lang, e.documentType()))
                            .append("</strong></a> ").append(chrome.status(lang, e.status())).append("</div>")
                            .append("<div class=\"meta\">").append(copy.date(lang, e.date()))
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
}

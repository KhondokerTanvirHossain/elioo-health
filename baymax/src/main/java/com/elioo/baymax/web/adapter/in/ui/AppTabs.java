package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.TimelineFilter;
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

import java.util.UUID;

import static com.elioo.baymax.web.adapter.in.ui.Html.esc;
import static com.elioo.baymax.web.adapter.in.ui.PageChrome.BASE;

/**
 * The three tabs (spec §3): চ্যাট {@code /app/home}, কাগজপত্র {@code /app/timeline}, পরিবার {@code /app/family}, each in
 * the {@link AppShell}. Whose papers are in view is {@link ActivePatient}: never guessed between two, so with two or
 * more, no {@code ?patient=} and no remembered choice, the চ্যাট and কাগজপত্র tabs show the chooser and read no one's
 * papers. Every tab with a patient in view re-sets the remembered choice (a sliding cookie); with two or more,
 * {@code ?sheet=patient} draws the switcher sheet over the tab. The pre-tab {@code /app/patients/{id}} is a 302 to
 * কাগজপত্র with that patient asked for, where it is checked like any {@code ?patient=}.
 */
@Component
@RequiredArgsConstructor
public class AppTabs implements WebPages {

    private final TimelineUseCase timeline;
    private final UiCopy copy;
    private final PageChrome chrome;
    private final NudgeDataPort nudgeData;
    private final BaymaxProperties properties;

    @Override
    public int order() {
        return 20;   // signed in
    }

    @Override
    public RouterFunction<ServerResponse> routes(HandlerFilterFunction<ServerResponse, ServerResponse> requireSession) {
        return RouterFunctions.route()
                .GET(Tab.CHAT.path, this::chat)
                .GET(Tab.PAPERS.path, this::papers)
                .GET(Tab.FAMILY.path, this::family)
                .GET(BASE + "/patients/{id}", request -> movedToTab(request, Tab.PAPERS))
                .filter(requireSession)
                .build();
    }

    record Body(String html, Html.Head head, String bodyClass) {
        static Body of(String html, Html.Head head) {
            return new Body(html, head, "");
        }
    }

    @FunctionalInterface
    interface Renderer {
        Mono<Body> render(ServerRequest request, Lang lang, FamilyOverview me, ActivePatient active);
    }

    public Mono<ServerResponse> chat(ServerRequest request) {
        return tab(request, Tab.CHAT, this::chatBody);
    }

    public Mono<ServerResponse> papers(ServerRequest request) {
        return tab(request, Tab.PAPERS, this::papersBody);
    }

    public Mono<ServerResponse> family(ServerRequest request) {
        return tab(request, Tab.FAMILY, this::familyBody);
    }

    /**
     * A patient URL from before the tabs (WhatsApp links, bookmarks): 302 to its tab with the patient asked for. Nothing
     * is read here; the tab checks the id against this family as it checks any {@code ?patient=} (a 404 for one it
     * cannot see), so the redirect says nothing about whether the patient exists. Not an id at all: a 404 at once.
     */
    static Mono<ServerResponse> movedToTab(ServerRequest request, Tab tab) {
        UUID id;
        try {
            id = UUID.fromString(request.pathVariable("id"));
        } catch (IllegalArgumentException e) {
            return Mono.error(BaymaxException.notFound("not_found", "no such page"));
        }
        return ServerResponse.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, tab.path + "?patient=" + id).build();
    }

    private Mono<ServerResponse> tab(ServerRequest request, Tab tab, Renderer renderer) {
        WebSession session = SessionAuthFilter.session(request);
        Lang lang = Lang.of(request);
        AppShell shell = new AppShell(copy);
        return timeline.me(session.familyId()).flatMap(me -> {
            ActivePatient active = ActivePatient.resolve(request, me);
            Mono<Body> body = active.patient().isEmpty() && tab != Tab.FAMILY
                    ? Mono.just(Body.of(me.patients().isEmpty() ? shell.noPatients(lang, tab) : shell.chooser(lang, tab, me, active), head(lang, tab)))
                    : renderer.render(request, lang, me, active);
            // the switcher: only with someone in view and someone else to switch to (with none in view the page is the chooser)
            String sheet = "patient".equals(request.queryParam("sheet").orElse("")) && me.patients().size() > 1 && active.patient().isPresent()
                    ? shell.patientSheet(lang, tab, me, active) : "";
            return body.flatMap(b -> {
                ServerResponse.BodyBuilder response = ServerResponse.ok().contentType(MediaType.TEXT_HTML);
                active.cookie(properties.getAuth().isCookieSecure()).ifPresent(response::cookie);
                return response.bodyValue(shell.page(lang, tab, me, active, b.html(), b.head(), b.bodyClass(), sheet));
            });
        });
    }

    private Html.Head head(Lang lang, Tab tab) {
        return Html.Head.of(copy.t(lang, tab.labelKey) + " — " + copy.t(lang, "app.title"));
    }

    /** C4 replaces this with the attention stack (s06). Until then: the greeting, the papers, the honest composer. */
    Mono<Body> chatBody(ServerRequest request, Lang lang, FamilyOverview me, ActivePatient active) {
        PatientSummary p = active.patient().orElseThrow();
        String owner = me.family().ownerName();
        String hello = owner == null || owner.isBlank() ? copy.t(lang, "chat.hello.plain") : copy.t(lang, "chat.hello", owner);
        return Mono.just(new Body("<p class=\"bubble\">" + hello + "</p>"
                + "<p><a class=\"btn quiet\" href=\"" + Tab.PAPERS.path + "?patient=" + p.patient().id() + "\">"
                + copy.t(lang, "chat.papers", p.patient().name()) + "</a></p>"
                + "<div class=\"composer\"><p class=\"soon\">" + copy.t(lang, "chat.soon") + "</p></div>",
                head(lang, Tab.CHAT), "has-composer"));
    }

    /**
     * The কাগজপত্র tab (s16, spec §4.15): the title and whose papers these are, the owner's tonal "কাগজ পাঠান" to the
     * add-report sheet, the filter chips, then {@link TimelineView}'s month headers and rows, newest document date first
     * (C2's read model), and "আরও দেখুন" keeping the filter and the cursor. No paper at all is the s16 empty panel (its
     * camera link for the owner only); a filter that matches nothing keeps the filters, so "সব" brings everything back,
     * and an empty later page under "সব" says only that there are no more.
     * Reloads itself while a paper is still being read; no script on this page.
     */
    Mono<Body> papersBody(ServerRequest request, Lang lang, FamilyOverview me, ActivePatient active) {
        PatientSummary patient = active.patient().orElseThrow();
        UUID patientId = patient.patient().id();
        String cursor = request.queryParam("cursor").orElse(null);
        TimelineFilter filter = TimelineFilter.fromParam(request.queryParam("type").orElse(null));
        UUID familyId = SessionAuthFilter.session(request).familyId();
        String list = Tab.PAPERS.path + "?patient=" + patientId;
        String name = patient.patient().name();
        return timeline.timeline(familyId, patientId, cursor, filter).map(page -> {
            boolean nothingAtAll = page.entries().isEmpty() && filter == TimelineFilter.ALL && cursor == null;
            StringBuilder b = new StringBuilder("<div class=\"phead\"><div><h1>").append(copy.t(lang, "timeline.docs")).append("</h1>")
                    .append("<p class=\"psub\">").append(copy.t(lang, "timeline.sub", name)).append("</p></div>");
            if (patient.owner() && !nothingAtAll) {
                // the one upload entry (A7): the add-report sheet for this patient, which comes back here
                b.append("<a class=\"btn tonal\" href=\"").append(addHref(patientId)).append("\">").append(Icons.svg("camera", 24))
                        .append("<span>").append(copy.t(lang, "timeline.add")).append("</span></a>");
            }
            b.append("</div>");
            if (nothingAtAll) {
                b.append(empty(lang, patient));
            } else {
                b.append("<nav class=\"filters\" aria-label=\"").append(copy.t(lang, "timeline.filter.label")).append("\">");
                for (TimelineFilter f : TimelineFilter.values()) {
                    // kit §26b: the chosen chip is marked and carries the check icon — colour is never the only cue
                    b.append("<a href=\"").append(list).append(typeParam(f)).append("\"").append(f == filter ? " aria-current=\"true\">" + Icons.svg("check", 18) : ">")
                            .append(copy.t(lang, "timeline.filter." + (f == TimelineFilter.ALL ? "all" : f.param()))).append("</a>");
                }
                b.append("</nav>");
                if (page.entries().isEmpty()) {
                    // a filter that matches nothing says so; an empty later page under "সব" (a stale "আরও দেখুন") says
                    // only that there are no more — never "none of this kind", and never "no papers yet"
                    String line = filter == TimelineFilter.ALL ? "timeline.end" : "timeline.filter.none";
                    b.append("<p class=\"psub\">").append(copy.t(lang, line)).append("</p>");
                }
                b.append(new TimelineView(copy).items(lang, page.entries(), name));
            }
            if (page.nextCursor() != null) {
                b.append("<p><a class=\"btn quiet\" href=\"").append(list).append(typeParam(filter))
                        .append("&amp;cursor=").append(esc(page.nextCursor())).append("\">").append(copy.t(lang, "timeline.more")).append("</a></p>");
            }
            // BMX-8 (PO option 1): a patient with no chronic flags is asked once, here, where the family already is —
            // nothing infers them, and the silence rule can never fire until someone answers (under the list, C3)
            if (patient.patient().chronicFlags() == null || patient.patient().chronicFlags().isEmpty()) {
                b.append("<div class=\"card ask\"><strong>").append(copy.t(lang, "timeline.flags.ask")).append("</strong>")
                        .append("<p class=\"small\">").append(copy.t(lang, "timeline.flags.why")).append("</p>")
                        .append("<a class=\"btn quiet\" href=\"").append(Tab.FAMILY.path).append("?patient=").append(patientId).append("\">")
                        .append(copy.t(lang, "timeline.flags.cta")).append("</a></div>");
            }
            // a document still being read: reload every few seconds until it is not
            boolean inFlight = page.entries().stream().anyMatch(e -> "RECEIVED".equals(e.status()) || "PROCESSING".equals(e.status()));
            return Body.of(b.toString(), head(lang, Tab.PAPERS).refresh(inFlight ? 5 : 0));
        });
    }

    /** s16 empty: whose papers, why send one, and — for the owner only — the camera to the sheet for this patient. */
    private String empty(Lang lang, PatientSummary patient) {
        StringBuilder b = new StringBuilder("<section class=\"empty\"><span class=\"disc\" aria-hidden=\"true\">")
                .append(Icons.svg("papers", 32)).append("</span><h2>").append(copy.t(lang, "timeline.empty.title", patient.patient().name()))
                .append("</h2><p>").append(copy.t(lang, "timeline.empty.body")).append("</p>");
        if (patient.owner()) {
            b.append("<a class=\"btn\" href=\"").append(addHref(patient.patient().id())).append("\">").append(Icons.svg("camera", 24))
                    .append("<span>").append(copy.t(lang, "chat.empty.action")).append("</span></a>");
        }
        return b.append("<p class=\"small\">").append(copy.t(lang, "timeline.empty.foot")).append("</p></section>").toString();
    }

    /** The add-report sheet for this patient, coming back to this tab (A7's one upload entry); escaped for an href. */
    private static String addHref(UUID patientId) {
        String back = java.net.URLEncoder.encode(Tab.PAPERS.path + "?patient=" + patientId, java.nio.charset.StandardCharsets.UTF_8);
        return UploadUiHandler.SHEET + "?patient=" + patientId + "&amp;back=" + back;
    }

    /** The list's {@code type} parameter, HTML-escaped for an href; nothing for every paper. */
    private static String typeParam(TimelineFilter f) {
        return f == TimelineFilter.ALL ? "" : "&amp;type=" + f.param();
    }

    /** s21 basics ({@link FamilyView}): the cards, the patient in view, the consent, the reminders, the account. */
    Mono<Body> familyBody(ServerRequest request, Lang lang, FamilyOverview me, ActivePatient active) {
        boolean saved = request.queryParam("saved").filter("1"::equals).isPresent();
        Mono<Boolean> optedOut = active.patient()
                .map(p -> nudgeData.patient(p.patient().id()).map(NudgeDataPort.PatientRef::optedOut).defaultIfEmpty(false))
                .orElse(Mono.just(false));
        return optedOut.map(off -> Body.of(new FamilyView(copy, chrome).render(lang, me, active, off, saved), head(lang, Tab.FAMILY)));
    }
}

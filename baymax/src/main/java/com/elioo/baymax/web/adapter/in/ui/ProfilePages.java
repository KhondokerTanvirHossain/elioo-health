package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.WebSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static com.elioo.baymax.web.adapter.in.ui.Html.esc;
import static com.elioo.baymax.web.adapter.in.ui.PageChrome.BASE;

/** A patient's profile: chronic flags (asked, never inferred) and the nudge switch (BMX-8). */
@Component
@RequiredArgsConstructor
public class ProfilePages implements WebPages {

    static final List<String> FLAGS = List.of("diabetes", "hypertension", "kidney", "thyroid", "heart");

    private final TimelineUseCase timeline;
    private final com.elioo.baymax.nudge.application.port.in.NudgeOptOutUseCase optOut;
    private final com.elioo.baymax.nudge.application.port.out.NudgeDataPort nudgeData;
    private final UiCopy copy;
    private final PageChrome chrome;

    @Override
    public int order() {
        return 40;   // signed in
    }

    @Override
    public RouterFunction<ServerResponse> routes(HandlerFilterFunction<ServerResponse, ServerResponse> requireSession) {
        return RouterFunctions.route()
                .GET(BASE + "/patients/{id}/profile", this::profilePage)
                .POST(BASE + "/patients/{id}/profile", this::saveProfile)
                .POST(BASE + "/patients/{id}/nudges/opt-out", this::optOutFromProfile)
                .filter(requireSession)
                .build();
    }

    public Mono<ServerResponse> profilePage(ServerRequest request) {
        return profilePage(request, false);
    }

    private Mono<ServerResponse> profilePage(ServerRequest request, boolean saved) {
        WebSession session = SessionAuthFilter.session(request);
        Lang lang = Lang.of(request);
        UUID patientId = PageChrome.uuid(request.pathVariable("id"));
        return timeline.me(session.familyId()).flatMap(me -> {
            PatientSummary patient = me.patients().stream().filter(p -> p.patient().id().equals(patientId)).findFirst()
                    .orElseThrow(() -> BaymaxException.notFound("patient_not_found", "no patient with id " + patientId));
            return nudgeData.patient(patientId).map(ref -> ref.optedOut()).defaultIfEmpty(false).flatMap(optedOut -> {
                List<String> flags = patient.patient().chronicFlags() == null ? List.of() : patient.patient().chronicFlags();
                StringBuilder b = new StringBuilder(new AppShell(copy).back(lang, Tab.FAMILY.path, "app.back.family"));
                b.append("<h1>").append(esc(patient.patient().name())).append(" · ").append(copy.t(lang, "profile.title")).append("</h1>");
                if (saved) {
                    b.append("<div class=\"card\">").append(copy.t(lang, "profile.saved")).append("</div>");
                }
                b.append("<form class=\"card\" method=\"post\" action=\"").append(BASE).append("/patients/").append(patientId).append("/profile\">")
                        .append("<strong>").append(copy.t(lang, "profile.flags")).append("</strong><p class=\"small\">").append(copy.t(lang, "profile.flags.hint")).append("</p>");
                String other = "";
                for (String f : flags) {
                    if (!FLAGS.contains(f)) {
                        other = other.isEmpty() ? f : other + ", " + f;
                    }
                }
                for (String f : FLAGS) {
                    b.append("<label class=\"check\"><input type=\"checkbox\" name=\"flag\" value=\"").append(f).append("\"")
                            .append(flags.contains(f) ? " checked" : "").append("> ").append(copy.t(lang, "flag." + f)).append("</label>");
                }
                b.append("<label for=\"o\">").append(copy.t(lang, "flag.other")).append("</label><input id=\"o\" type=\"text\" name=\"other\" maxlength=\"40\" value=\"").append(esc(other)).append("\">")
                        .append(patient.owner() ? "<button type=\"submit\">" + copy.t(lang, "profile.save") + "</button>" : "").append("</form>");
                b.append("<h2>").append(copy.t(lang, "profile.nudges")).append("</h2><div class=\"card\">")
                        .append(optedOut ? copy.t(lang, "profile.nudges.off") : copy.t(lang, "profile.nudges.on"));
                if (!optedOut && patient.owner()) {
                    b.append("<form method=\"post\" action=\"").append(BASE).append("/patients/").append(patientId).append("/nudges/opt-out\">")
                            .append("<button type=\"submit\" class=\"quiet\">").append(copy.t(lang, "profile.nudges.stop")).append("</button></form>");
                }
                b.append("</div>");
                return Html.html(org.springframework.http.HttpStatus.OK, new AppShell(copy).inside(lang, Tab.FAMILY, b.toString()));
            });
        });
    }

    public Mono<ServerResponse> saveProfile(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID patientId = PageChrome.uuid(request.pathVariable("id"));
        return request.formData().flatMap(form -> {
            List<String> flags = new java.util.ArrayList<>(form.getOrDefault("flag", List.of()));
            String other = form.getFirst("other");
            if (other != null && !other.isBlank()) {
                flags.add(other.trim());
            }
            return timeline.updateChronicFlags(session.familyId(), patientId, flags);
        }).flatMap(p -> profilePage(request, true));
    }

    /** From the profile page, with a session: stop nudges for this patient. */
    public Mono<ServerResponse> optOutFromProfile(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID patientId = PageChrome.uuid(request.pathVariable("id"));
        return timeline.me(session.familyId())
                .filter(me -> me.patients().stream().anyMatch(p -> p.patient().id().equals(patientId) && p.owner()))
                .switchIfEmpty(Mono.error(BaymaxException.notFound("patient_not_found", "no patient with id " + patientId)))
                .flatMap(me -> optOut.optOutPatient(patientId))
                .then(Mono.defer(() -> profilePage(request, false)));
    }
}

package com.elioo.baymax.web.adapter.in.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * The browser's JSON namespace for the Next.js frontend (rebuild plan A3, B1). It lives under {@code /app} so the
 * {@code Path=/app} session cookie travels with it and is never widened; Caddy sends {@code /app/api/**} to Java.
 *
 * <p>It is its own router with its own filter, {@link AppApiGuard}, and is ordered ahead of every other router bean, so
 * no request under {@code /app/api} ever reaches the HTML router's {@code requireSession} (a 303 to sign-in) or its HTML
 * error pages — today because no HTML route matches {@code /app/api/**}, and after any later HTML catch-all because of
 * this order ({@code AppApiRouteContractTest.anHtmlCatchAllUnderAppNeverCapturesAnApiRoute} registers one and fails if
 * the order goes). The last route catches any other {@code /app/api} path, so an unknown route answers
 * {@code {"reason":"NOT_FOUND"}} (after the session) instead of falling through to a page or Spring's error JSON:
 * a newer frontend on an older Java gets a reason it understands (A2: additive only).
 */
@Configuration
public class AppApiRouter {

    public static final String BASE = "/app/api";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public RouterFunction<ServerResponse> mediooAppApiRoutes(MeApiHandler me, SignInApiHandler signIn, PaperApiHandler papers,
                                                             UploadApiHandler uploads, PaperStatusApiHandler status,
                                                             PaperDeleteApiHandler deletes, FamilyApiHandler family,
                                                             HealthApiHandler health,
                                                             NowBannerApiHandler nowBanner,
                                                             AttentionApiHandler attention, PatientTimelineApiHandler timeline,
                                                             PreferencesApiHandler preferences,
                                                             ThreadApiHandler thread,
                                                             AskApiHandler ask,
                                                             AppApiGuard guard) {
        return RouterFunctions.route()
                .GET(BASE + "/me", me::me)
                .PUT(BASE + "/active-patient", me::setActivePatient)
                // plan B3: the global NOW banner and its দেখেছি, per account holder (DR-41 item 5)
                .GET(BASE + "/now-banner", nowBanner::banner)
                .PUT(BASE + "/now-banner/ack", nowBanner::acknowledge)
                .GET(BASE + "/patients/{id}/attention", attention::attention)
                .GET(BASE + "/patients/{id}/timeline", timeline::timeline)
                .GET(BASE + "/patients/{id}/calendar", timeline::calendar)
                .GET(BASE + "/patients/{id}/follow-ups", timeline::followUps)
                // plan W5-api: the চ্যাট thread, papers and the messages the provider accepted for this account (S4, S10)
                .GET(BASE + "/patients/{id}/thread", thread::thread)
                // the two mutations that need no session (AppApiGuard.OPEN); CSRF still applies, Origin required
                .POST(BASE + "/sign-in/request", signIn::request)
                .POST(BASE + "/sign-in/verify", signIn::verify)
                // plan W3-api: the code step read back (open, a read), the checked held place and sign-out (session)
                .GET(BASE + "/sign-in/state", signIn::state)
                .GET(BASE + "/sign-in/next", signIn::next)
                .POST(BASE + "/sign-out", signIn::signOut)
                .GET(BASE + "/documents/{id}/status", status::status)
                .GET(BASE + "/documents/{id}/delete-token", deletes::token)
                // plan W6-api: one value's sheet, the earlier readings each with its own crop
                .GET(BASE + "/documents/{id}/values/{obs}", papers::valueSheet)
                .GET(BASE + "/documents/{id}", papers::paper)
                .DELETE(BASE + "/documents/{id}", deletes::delete)
                .GET(BASE + "/upload-options", uploads::options)
                .POST(BASE + "/uploads", uploads::upload)
                // the পরিবার tab (plan B5): reminders can only be stopped here until E1/E2-API (Q5, Q9)
                .GET(BASE + "/family", family::family)
                .PUT(BASE + "/family/reminders", family::familyReminders)
                .PUT(BASE + "/patients/{id}/flags", family::setFlags)
                .PUT(BASE + "/patients/{id}/reminders", family::patientReminders)
                .PUT(BASE + "/language", family::language)
                // the theme (plan W10-api, S12): a cookie under /app, nothing stored
                .PUT(BASE + "/preferences", preferences::preferences)
                .GET(BASE + "/patients/{id}/labs", health::labs)
                .GET(BASE + "/patients/{id}/medicines", health::medicines)
                // plan CHAT-api (PO ruling P7, DR-50): a question about one patient, answered from the patient's own papers
                .POST(BASE + "/patients/{id}/ask", ask::ask)
                .route(RequestPredicates.path(BASE + "/**"), request -> ApiReason.NOT_FOUND.respond())
                .filter(guard)
                .build();
    }
}

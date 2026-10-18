package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.wa.WaFixtures;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import com.elioo.baymax.wa.application.service.WaIntakeService;
import com.elioo.baymax.wa.domain.WaInbound;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.adapter.out.delivery.CompositeOtpDelivery;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.application.port.out.InboundWindowPort;
import com.elioo.baymax.web.application.port.out.OtpStorePort;
import com.elioo.baymax.web.application.port.out.SessionStorePort;
import com.elioo.baymax.web.application.service.PhoneHasher;
import com.elioo.baymax.web.application.service.WebAuthService;
import com.elioo.baymax.web.domain.OtpCode;
import com.elioo.baymax.web.domain.WebSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.elioo.baymax.web.adapter.in.ui.SignInPagesTest.assertNoSendPromise;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sign-in is free (PO 2026-10-18, DR-39 amended), end to end with no network, both directions, over the real
 * WhatsApp intake, the real WebAuthService, the real CompositeOtpDelivery and the real sign-in pages:
 * <ol>
 *   <li>a family that wrote to Medioo on WhatsApp inside the window types its number on the web; the code goes to its
 *       WhatsApp at once (free: a session message inside the window it opened), and the page says it was sent;</li>
 *   <li>outside the window, with no account, off the allowlist, or when WhatsApp refuses the send, nothing goes out —
 *       or nothing went out — and the page never says sent: it shows the button that opens WhatsApp with কোড.</li>
 * </ol>
 * Only the window store is an in-memory stand-in; its constraints (HMAC key, FK, forward-only) are Testcontainers'
 * job in WebPersistenceAdaptersTest.
 */
class SignInIsFreeAcceptanceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final String FROM = "8801700000000";
    private static final String NUMBER = "+8801700000000";
    private static final String TYPED = "01700000000";
    private static final String BUTTON = "<a class=\"btn wa\" href=\"https://wa.me/8801700000999?text=%E0%A6%95%E0%A7%8B%E0%A6%A1\">";
    private static final UUID FAMILY = UUID.randomUUID();

    private final OtpStorePort otps = mock(OtpStorePort.class);
    private final SessionStorePort sessions = mock(SessionStorePort.class);
    private final AuditPort audit = mock(AuditPort.class);
    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final WaMessagingPort graph = mock(WaMessagingPort.class);
    private final BaymaxProperties props = new BaymaxProperties();
    private final Map<String, Instant> lastInbound = new ConcurrentHashMap<>();
    private final AtomicReference<OtpCode> row = new AtomicReference<>();
    private PhoneHasher hasher;
    private WaIntakeService intake;
    private WebTestClient web;

    /** Forward-only, like the SQL's GREATEST; keyed by whatever hash it is given. */
    private final InboundWindowPort window = new InboundWindowPort() {
        @Override
        public Mono<Void> recordInbound(String phoneHash, UUID familyId, Instant at) {
            return Mono.fromRunnable(() -> lastInbound.merge(phoneHash, at, (a, b) -> a.isAfter(b) ? a : b));
        }

        @Override
        public Mono<Instant> lastInbound(String phoneHash) {
            return Mono.justOrEmpty(lastInbound.get(phoneHash));
        }
    };

    @BeforeEach
    void wire() {
        props.getAuth().setHmacSecret("test-secret");
        props.getWa().setEnabled(true);
        props.getWa().setAllowlist(List.of(NUMBER, "+8801700000300"));
        props.getWa().setPublicNumber("8801700000999");
        hasher = new PhoneHasher(props);
        when(audit.record(any())).thenReturn(Mono.empty());
        when(otps.countCreatedSince(anyString(), any())).thenReturn(Mono.just(0L));
        when(otps.create(any())).thenAnswer(i -> {
            OtpCode c = i.getArgument(0);
            row.set(new OtpCode(UUID.randomUUID(), c.phoneHash(), c.codeHash(), c.familyId(), c.expiresAt(), 0, null, null, c.createdAt()));
            return Mono.just(row.get());
        });
        when(otps.latestLive(anyString(), any())).thenAnswer(i -> Mono.justOrEmpty(row.get())
                .filter(c -> c.phoneHash().equals(i.getArgument(0)) && c.isLive(i.getArgument(1))));
        when(otps.latestSince(anyString(), any())).thenReturn(Mono.empty());
        when(otps.consume(any(), any())).thenReturn(Mono.empty());
        when(otps.recordAttempt(any(), anyInt(), any())).thenReturn(Mono.empty());
        when(sessions.create(anyString(), any(), any(), any())).thenAnswer(i ->
                Mono.just(new WebSession(UUID.randomUUID(), i.getArgument(1), NOW, NOW, i.getArgument(3))));
        when(sessions.findLive(anyString(), any())).thenReturn(Mono.empty());
        when(records.findFamilyByWhatsapp(anyString())).thenReturn(Mono.empty());
        when(records.findFamilyByWhatsapp(NUMBER)).thenReturn(Mono.just(
                new FamilyAccount(FAMILY, NUMBER, "Rahim", FamilyAccount.Plan.FREE, NOW, NOW)));
        when(records.patientsOf(FAMILY)).thenReturn(Flux.empty());
        when(graph.sendText(anyString(), anyString())).thenReturn(Mono.just(WaFixtures.wamid(FROM, 5)));

        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("wa", graph);
        WebAuthService auth = new WebAuthService(otps, sessions,
                new CompositeOtpDelivery(beans.getBeanProvider(WaMessagingPort.class), new Copy(), props),
                audit, records, hasher, props, Clock.fixed(NOW, ZoneOffset.UTC), window);
        intake = new WaIntakeService(records, mock(DocumentRecordPort.class), mock(ExplainDocumentUseCase.class),
                mock(DocumentIntakeUseCase.class), graph, new Copy(), props, auth);
        SessionAuthFilter filter = new SessionAuthFilter(auth, props);
        WebUiHandler ui = new WebUiHandler(auth, mock(TimelineUseCase.class), filter, new UiCopy(),
                mock(com.elioo.baymax.nudge.application.port.in.NudgeOptOutUseCase.class),
                mock(com.elioo.baymax.nudge.application.port.out.NudgeDataPort.class), props);
        web = WebTestClient.bindToRouterFunction(new BaymaxWebUiRouter().baymaxWebUiRoutes(ui, filter)).build();
    }

    /** The family's own WhatsApp message, through the one entry point; then forget the reply it got. */
    private void familyWritesOnWhatsapp() {
        intake.onInbound(new WaInbound("w1", FROM, WaInbound.Type.TEXT, "hi", "", "")).block();
        clearInvocations(graph);
    }

    /** Number page → confirm → the code page; returns the login cookie the browser now holds. */
    private String signInOnTheWeb(String typed) {
        return web.post().uri("/app/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("whatsapp_number", typed).with("confirmed", "1"))
                .exchange().expectStatus().isSeeOther()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/verify")
                .returnResult(Void.class).getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE)
                .replaceFirst("^baymax_login=([^;]*);.*$", "$1");
    }

    private String codePage(String cookie) {
        return web.get().uri("/app/verify").cookie("baymax_login", cookie).exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
    }

    private void thePageNeverSaysSentAndShowsTheButton(String cookie) {
        assertThat(cookie).doesNotEndWith(".s");
        String html = codePage(cookie);
        assertNoSendPromise(html);
        assertThat(html).contains(BUTTON).contains("এখন WhatsApp-এ Medioo-কে লিখুন:");
    }

    // ---- direction 1 ------------------------------------------------------------------------------------------

    @Test
    void insideTheWindowTheCodeGoesToWhatsappAtOnceThePageSaysSoAndTheCodeOpensTheSession() {
        familyWritesOnWhatsapp();
        assertThat(lastInbound).as("the window is recorded under the HMAC, never the number")
                .containsOnlyKeys(hasher.phone(NUMBER));

        String cookie = signInOnTheWeb(TYPED);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(graph).sendText(eq(NUMBER), body.capture());
        assertThat(body.getValue()).contains("Medioo-তে ঢোকার কোড").contains("এই কোড কাউকে দেবেন না");
        Matcher m = Pattern.compile("(?<![0-9])([0-9]{6})(?![0-9])").matcher(body.getValue());
        assertThat(m.find()).isTrue();
        assertThat(cookie).endsWith(".000.s").doesNotContain("01700000000");

        String html = codePage(cookie);
        assertThat(html).contains("কোডটা আপনার WhatsApp-এ পাঠানো হয়েছে").doesNotContain("class=\"btn wa\"");

        web.post().uri("/app/verify").cookie("baymax_login", cookie).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("code", m.group(1)))
                .exchange().expectStatus().isSeeOther().expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/home");
    }

    /** 22 h 59 min is still inside the 23 h margin. */
    @Test
    void aMessageFromYesterdayEveningStillCounts() {
        lastInbound.put(hasher.phone(NUMBER), NOW.minus(Duration.ofHours(22).plusMinutes(59)));
        assertThat(signInOnTheWeb(TYPED)).endsWith(".s");
        verify(graph).sendText(eq(NUMBER), anyString());
    }

    // ---- direction 2 ------------------------------------------------------------------------------------------

    @Test
    void aFamilyThatHasNotWrittenGetsNothingSentAndTheButton() {
        thePageNeverSaysSentAndShowsTheButton(signInOnTheWeb(TYPED));
        verify(graph, never()).sendText(anyString(), anyString());
    }

    @Test
    void aMessageOlderThanTheMarginIsOutsideTheWindow() {
        lastInbound.put(hasher.phone(NUMBER), NOW.minus(Duration.ofHours(23).plusMinutes(1)));
        thePageNeverSaysSentAndShowsTheButton(signInOnTheWeb(TYPED));
        verify(graph, never()).sendText(anyString(), anyString());
        verify(otps, never()).create(any());
    }

    /** (d) never an open sender: a window exists for the number, but no account does. */
    @Test
    void aNumberWithNoAccountIsNeverSentACodeEvenWithAWindow() {
        lastInbound.put(hasher.phone("+8801700000300"), NOW.minusSeconds(60));
        thePageNeverSaysSentAndShowsTheButton(signInOnTheWeb("01700000300"));
        verify(graph, never()).sendText(anyString(), anyString());
    }

    @Test
    void aNumberOffTheAllowlistIsNeverSentACode() {
        familyWritesOnWhatsapp();
        props.getWa().setAllowlist(List.of("+8801700000300"));
        thePageNeverSaysSentAndShowsTheButton(signInOnTheWeb(TYPED));
        verify(graph, never()).sendText(anyString(), anyString());
    }

    /** WhatsApp refused the message (window closed early, token, anything): the page must not claim it arrived. */
    @Test
    void whenWhatsappRefusesTheSendThePageNeverSaysSent() {
        familyWritesOnWhatsapp();
        when(graph.sendText(eq(NUMBER), argThat(b -> b.contains("Medioo-তে ঢোকার কোড"))))
                .thenReturn(Mono.error(new RuntimeException("(#131047) Re-engagement message")));
        thePageNeverSaysSentAndShowsTheButton(signInOnTheWeb(TYPED));
        verify(graph).sendText(eq(NUMBER), anyString());   // it was tried, and failed
    }

    /**
     * The window is WhatsApp's, so it runs from when the family sent the message (Meta's {@code timestamp}), not from
     * when Medioo received the callback. Meta retries a callback it could not deliver — a deploy, a non-200 — for
     * hours; a message sent 30 hours ago and received now was recorded as "now" and opened a window WhatsApp had
     * already closed: a code minted, the hour's cap spent, a send Graph refuses.
     */
    @Test
    void aCallbackRetriedLateForAMessageSentThirtyHoursAgoOpensNoWindow() {
        intake.onInbound(new WaInbound("w-late", FROM, WaInbound.Type.TEXT, "hi", "", "", NOW.minus(Duration.ofHours(30)))).block();
        clearInvocations(graph);
        thePageNeverSaysSentAndShowsTheButton(signInOnTheWeb(TYPED));
        verify(graph, never()).sendText(anyString(), anyString());
        verify(otps, never()).create(any());
    }

    /** The other direction: a callback with a recent send time opens it, and a send time ahead of our clock counts as now. */
    @Test
    void aCallbackForAMessageSentAnHourAgoOpensTheWindowAndAFutureSendTimeCountsAsNow() {
        intake.onInbound(new WaInbound("w-ok", FROM, WaInbound.Type.TEXT, "hi", "", "", NOW.minus(Duration.ofHours(1)))).block();
        assertThat(lastInbound).containsEntry(hasher.phone(NUMBER), NOW.minus(Duration.ofHours(1)));
        intake.onInbound(new WaInbound("w-skew", FROM, WaInbound.Type.TEXT, "hi", "", "", NOW.plus(Duration.ofHours(5)))).block();
        assertThat(lastInbound).as("a skewed clock at Meta never stretches the window past now")
                .containsEntry(hasher.phone(NUMBER), NOW);
        clearInvocations(graph);
        assertThat(signInOnTheWeb(TYPED)).endsWith(".s");
        verify(graph).sendText(eq(NUMBER), anyString());
    }

    /** The button's path, free too: the family texts কোড and the reply inside the window it opened carries a code. */
    @Test
    void outsideTheWindowTheButtonsPathStillGetsTheFamilyACode() {
        thePageNeverSaysSentAndShowsTheButton(signInOnTheWeb(TYPED));
        assertThat(intake.onInbound(new WaInbound("w2", FROM, WaInbound.Type.TEXT, "কোড", "", "")).block())
                .isEqualTo(WaIntakeService.Outcome.LOGIN_CODE);
        verify(graph).sendText(eq(NUMBER), argThat(b -> b.contains("Medioo-তে ঢোকার কোড")));
    }
}

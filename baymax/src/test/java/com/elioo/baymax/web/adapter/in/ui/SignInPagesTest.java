package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.CodeIssue;
import com.elioo.baymax.web.domain.CodeRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two sign-in screens (spec §4.2, §4.3), restyled on today's tokens. Sign-in is free (PO 2026-10-18, DR-39
 * amended): the code page says a code was sent ONLY when WhatsApp accepted it; every other state shows the one-tap
 * button that opens WhatsApp with "কোড" typed in.
 */
class SignInPagesTest {

    final WebAuthUseCase auth = mock(WebAuthUseCase.class);
    final BaymaxProperties props = new BaymaxProperties();
    final WebTestClient client;

    SignInPagesTest() {
        props.getWa().setPublicNumber("8801700000000");
        SessionAuthFilter sessions = new SessionAuthFilter(auth, props);
        when(auth.authenticate(anyString())).thenReturn(Mono.empty());
        WebUiHandler ui = new WebUiHandler(auth, mock(TimelineUseCase.class), sessions, new UiCopy(),
                mock(com.elioo.baymax.nudge.application.port.in.NudgeOptOutUseCase.class),
                mock(com.elioo.baymax.nudge.application.port.out.NudgeDataPort.class), props);
        client = WebTestClient.bindToRouterFunction(new BaymaxWebUiRouter().baymaxWebUiRoutes(ui, sessions)).build();
    }

    String get(String path, String loginCookie) {
        var req = client.get().uri(path);
        if (loginCookie != null) {
            req = req.cookie("baymax_login", loginCookie);
        }
        return req.exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }

    String post(String path, MultiValueMap<String, String> form, String loginCookie) {
        var req = client.post().uri(path);
        if (loginCookie != null) {
            req = req.cookie("baymax_login", loginCookie);
        }
        return req.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(BodyInserters.fromFormData(form))
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }

    static MultiValueMap<String, String> form(String... kv) {
        var m = new LinkedMultiValueMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) {
            m.add(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** No page says a code was or will be sent unless WhatsApp accepted it (DR-39 amended): a page that does is lying. */
    static void assertNoSendPromise(String html) {
        assertThat(html).doesNotContain("পাঠিয়েছি").doesNotContain("পাঠানো হবে").doesNotContain("পাঠানো হয়েছে")
                .doesNotContain("will be sent").doesNotContain("on its way").doesNotContainIgnoringCase("we sent");
    }

    /** Regression guard, replayed on the pre-fix code: login.hint promised "একটি ৬ সংখ্যার কোড পাঠানো হবে". */
    @Test
    void noSignInPagePromisesASend() {
        String number = get("/app/", null);
        assertNoSendPromise(number);                                    // first, so the replay fails ON the promise
        assertThat(number).contains("<form").contains("name=\"whatsapp_number\"");   // and not on an empty page
        String code = get("/app/verify", "c".repeat(64) + ".171");
        assertNoSendPromise(code);
        assertThat(code).contains("name=\"code\"");
    }

    @Test
    void theGuardCanFailAndNoLoginCopyCarriesTheWordInEitherLanguage() {
        assertThatThrownBy(() -> assertNoSendPromise("<p>কোড পাঠিয়েছি</p>")).isInstanceOf(AssertionError.class);
        UiCopy copy = new UiCopy();
        for (Lang lang : Lang.values()) {
            for (String key : copy.keys(lang)) {
                if (key.startsWith("login.")) {
                    assertNoSendPromise(copy.t(lang, key));
                }
            }
        }
    }

    @Test
    void theNumberPageIsOneFieldWithNoPrefixAndNoDeepLink() {
        String html = get("/app/", null);
        assertThat(html).contains("Medioo-তে ঢুকুন").contains("আপনার WhatsApp নম্বর").contains("যে নম্বরে WhatsApp চালান, সেটাই দিন")
                .contains("পরের ধাপ").contains("type=\"tel\"").contains("autocomplete=\"tel\"");
        assertThat(html).doesNotContain("wa.me").doesNotContain("8801700000000").doesNotContain("placeholder=\"01");   // D37
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "01700000000|+880 1700-000000", "+880 1700-000000|+880 1700-000000", "008801700000000|+880 1700-000000",
            "০১৭০০০০০০০০|+880 1700-000000", "0170-000 0000|+880 1700-000000", "+447700900123|+447700900123"})
    void aValidNumberIsShownBackNormalisedAndNothingHappensYet(String typed, String shown) {
        String html = post("/app/login", form("whatsapp_number", typed), null);
        assertThat(html).contains(shown).contains("ঠিক আছে?")
                .contains("<input type=\"hidden\" name=\"confirmed\" value=\"1\">");
        verify(auth, never()).requestCode(anyString());
    }

    @Test
    void anInvalidNumberIsRefusedInlineAndRevealsNothing() {
        String html = post("/app/login", form("whatsapp_number", "1700000000"), null);
        assertThat(html).contains("<div class=\"err\" role=\"alert\">").contains("নম্বরটা ঠিক মনে হচ্ছে না")
                .contains("value=\"1700000000\"");
        verify(auth, never()).requestCode(anyString());
    }

    /** DR-23: the next URL carries nothing; the cookie carries the HMAC and three digits, never the number. */
    @Test
    void confirmingSetsAShortLivedCookieWithOnlyTheHashAndTheLastThreeDigits() {
        String hash = "c".repeat(64);
        when(auth.requestCode("+8801700000171")).thenReturn(Mono.just(new CodeRequest(hash, CodeIssue.OUTSIDE_WINDOW)));
        client.post().uri("/app/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form("whatsapp_number", "01700000171", "confirmed", "1")))
                .exchange().expectStatus().isSeeOther()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/verify")
                .expectHeader().value(HttpHeaders.SET_COOKIE, c -> assertThat(c)
                        .startsWith("baymax_login=" + hash + ".171;").contains("HttpOnly").contains("Path=/app")
                        .contains("Max-Age=1800").doesNotContain("01700000171").doesNotContain("8801700000171"));
    }
}

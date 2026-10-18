package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.web.application.port.in.CodeRejectedException;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.application.port.out.InboundWindowPort;
import com.elioo.baymax.web.application.port.out.OtpDeliveryPort;
import com.elioo.baymax.web.application.port.out.OtpStorePort;
import com.elioo.baymax.web.application.port.out.SessionStorePort;
import com.elioo.baymax.web.domain.CodeIssue;
import com.elioo.baymax.web.domain.CodeRequest;
import com.elioo.baymax.web.domain.OtpCode;
import com.elioo.baymax.web.domain.VerifyFailure;
import com.elioo.baymax.web.domain.WebSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The OTP contract, with an in-memory stand-in for the code row so attempts and burning are observable.
 *
 * <p>Sign-in is free (PO 2026-10-18, DR-39 amended). A code is minted in exactly two ways: for the WhatsApp sender
 * who texted "কোড" ({@code issueCodeFor}), and from the web form for a number that has an account, is allowed by the
 * allowlist, and wrote to Medioo inside the service window ({@code requestCode}). Everything else mints nothing.
 */
class WebAuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final String NUMBER = "+8801700000100";
    private static final String TYPED = "01700000100";
    private static final String STRANGER = "+8801700000200";
    private static final UUID FAMILY = UUID.randomUUID();

    private final OtpStorePort otps = mock(OtpStorePort.class);
    private final SessionStorePort sessions = mock(SessionStorePort.class);
    private final OtpDeliveryPort delivery = mock(OtpDeliveryPort.class);
    private final AuditPort audit = mock(AuditPort.class);
    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final InboundWindowPort window = mock(InboundWindowPort.class);
    private final BaymaxProperties properties = new BaymaxProperties();
    private final AtomicReference<OtpCode> row = new AtomicReference<>();
    private final AtomicReference<String> deliveredCode = new AtomicReference<>();
    private WebAuthService service;

    @BeforeEach
    void wire() {
        properties.getAuth().setHmacSecret("test-secret");
        service = serviceAt(NOW);
        when(audit.record(any())).thenReturn(Mono.empty());
        when(otps.countCreatedSince(anyString(), any())).thenReturn(Mono.just(0L));
        when(otps.create(any())).thenAnswer(i -> {
            OtpCode c = i.getArgument(0);
            row.set(new OtpCode(UUID.randomUUID(), c.phoneHash(), c.codeHash(), c.familyId(), c.expiresAt(), 0, null, null, c.createdAt()));
            return Mono.just(row.get());
        });
        when(delivery.deliver(anyString(), anyString(), anyString())).thenAnswer(i -> {
            deliveredCode.set(i.getArgument(2));
            return Mono.just(true);
        });
        when(otps.latestLive(anyString(), any())).thenAnswer(i -> Mono.justOrEmpty(row.get())
                .filter(c -> c.phoneHash().equals(i.getArgument(0)) && c.isLive(i.getArgument(1))));
        when(otps.latestSince(anyString(), any())).thenAnswer(i -> Mono.justOrEmpty(row.get())
                .filter(c -> c.phoneHash().equals(i.getArgument(0)) && !c.createdAt().isBefore(i.getArgument(1))));
        when(otps.recordAttempt(any(), anyInt(), any())).thenAnswer(i -> {
            OtpCode c = row.get();
            row.set(new OtpCode(c.id(), c.phoneHash(), c.codeHash(), c.familyId(), c.expiresAt(), i.getArgument(1), null, i.getArgument(2), c.createdAt()));
            return Mono.empty();
        });
        when(otps.consume(any(), any())).thenAnswer(i -> {
            OtpCode c = row.get();
            row.set(new OtpCode(c.id(), c.phoneHash(), c.codeHash(), c.familyId(), c.expiresAt(), c.attempts(), i.getArgument(1), null, c.createdAt()));
            return Mono.empty();
        });
        when(sessions.create(anyString(), any(), any(), any())).thenAnswer(i ->
                Mono.just(new WebSession(UUID.randomUUID(), i.getArgument(1), NOW, NOW, i.getArgument(3))));
        when(records.findFamilyByWhatsapp(anyString())).thenReturn(Mono.empty());
        when(records.findFamilyByWhatsapp(NUMBER)).thenReturn(Mono.just(
                new FamilyAccount(FAMILY, NUMBER, "Owner", FamilyAccount.Plan.FREE, NOW, NOW)));
        when(window.lastInbound(anyString())).thenReturn(Mono.empty());
        when(window.recordInbound(anyString(), any(), any())).thenReturn(Mono.empty());
    }

    /** The same mocks, a different clock. */
    private WebAuthService serviceAt(Instant t) {
        return new WebAuthService(otps, sessions, delivery, audit, records, new PhoneHasher(properties), properties,
                Clock.fixed(t, ZoneOffset.UTC), window);
    }

    private void lastWrote(String number, Duration ago) {
        when(window.lastInbound(service.hash(number))).thenReturn(Mono.just(NOW.minus(ago)));
    }

    private String wrong() {
        return deliveredCode.get().equals("000000") ? "111111" : "000000";
    }

    private void nothingMintedOrSent() {
        verify(otps, never()).create(any());
        verify(delivery, never()).deliver(anyString(), anyString(), anyString());
    }

    // ---- the web form: sent automatically inside the window, and only there (PO 2026-10-18) ----------------------

    /** Direction 1: an account number that wrote 22 h 59 min ago, inside the 23 h margin, gets the code on WhatsApp. */
    @Test
    void insideTheWindowTheWebRequestSendsTheCodeToWhatsappAndReportsItSent() {
        lastWrote(NUMBER, Duration.ofHours(22).plusMinutes(59));

        StepVerifier.create(service.requestCode(TYPED))
                .expectNext(new CodeRequest(service.hash(NUMBER), CodeIssue.SENT)).verifyComplete();

        verify(delivery).deliver(eq(NUMBER), eq(service.hash(NUMBER)), anyString());
        StepVerifier.create(service.verify(service.hash(NUMBER), deliveredCode.get()))
                .assertNext(issued -> assertThat(issued.session().familyId()).isEqualTo(FAMILY))
                .verifyComplete();
    }

    /** Direction 2: one second past the margin is outside it. Nothing is minted, nothing is sent, "sent" is never reported. */
    @Test
    void outsideTheWindowTheWebRequestMintsAndSendsNothing() {
        lastWrote(NUMBER, Duration.ofHours(23).plusSeconds(1));
        StepVerifier.create(service.requestCode(TYPED))
                .expectNext(new CodeRequest(service.hash(NUMBER), CodeIssue.OUTSIDE_WINDOW)).verifyComplete();
        nothingMintedOrSent();
    }

    @Test
    void aNumberThatNeverWroteIsOutsideTheWindow() {
        StepVerifier.create(service.requestCode(TYPED))
                .expectNext(new CodeRequest(service.hash(NUMBER), CodeIssue.OUTSIDE_WINDOW)).verifyComplete();
        nothingMintedOrSent();
    }

    /** The margin is configuration, and it is the rule that ran: a 1 h window refuses what a 23 h one would send. */
    @Test
    void theMarginIsConfigurable() {
        properties.getWa().setServiceWindow(Duration.ofHours(1));
        lastWrote(NUMBER, Duration.ofHours(2));
        StepVerifier.create(service.requestCode(TYPED)).assertNext(r -> assertThat(r.issue()).isEqualTo(CodeIssue.OUTSIDE_WINDOW))
                .verifyComplete();
        nothingMintedOrSent();
    }

    /** (d) never an open sender: a number with no account gets nothing, whatever its window says. */
    @Test
    void aNumberWithNoAccountIsSentNothingEvenInsideAWindow() {
        when(window.lastInbound(anyString())).thenReturn(Mono.just(NOW.minusSeconds(60)));
        StepVerifier.create(service.requestCode(STRANGER))
                .expectNext(new CodeRequest(service.hash(STRANGER), CodeIssue.NO_ACCOUNT)).verifyComplete();
        nothingMintedOrSent();
    }

    @Test
    void aNumberOffTheAllowlistIsSentNothingEvenInsideTheWindow() {
        properties.getWa().setAllowlist(List.of("+8801700000999"));
        lastWrote(NUMBER, Duration.ofMinutes(5));
        StepVerifier.create(service.requestCode(TYPED)).assertNext(r -> assertThat(r.issue()).isEqualTo(CodeIssue.NOT_PERMITTED))
                .verifyComplete();
        nothingMintedOrSent();
    }

    /** Graph refused the send: the code row exists but nothing reached WhatsApp, so the result must not say sent. */
    @Test
    void whenWhatsappRefusesTheSendTheRequestIsNotReportedSent() {
        lastWrote(NUMBER, Duration.ofMinutes(5));
        when(delivery.deliver(anyString(), anyString(), anyString())).thenReturn(Mono.just(false));
        StepVerifier.create(service.requestCode(TYPED)).assertNext(r -> assertThat(r.issue()).isEqualTo(CodeIssue.NOT_DELIVERED))
                .verifyComplete();
    }

    /** The 5-per-hour limit holds on the web path too. */
    @Test
    void theWebPathIsRateLimitedLikeTheWhatsappPath() {
        lastWrote(NUMBER, Duration.ofMinutes(5));
        when(otps.countCreatedSince(anyString(), any())).thenReturn(Mono.just(5L));
        StepVerifier.create(service.requestCode(TYPED)).assertNext(r -> assertThat(r.issue()).isEqualTo(CodeIssue.RATE_LIMITED))
                .verifyComplete();
        nothingMintedOrSent();
    }

    @Test
    void aMalformedNumberStillCompletesTheRequestAndSendsNothing() {
        StepVerifier.create(service.requestCode("not a number"))
                .assertNext(r -> assertThat(r.issue()).isEqualTo(CodeIssue.NO_ACCOUNT)).verifyComplete();
        nothingMintedOrSent();
    }

    /** Local development only (WhatsApp off, startup refuses it with WhatsApp on): the window is not consulted. */
    @Test
    void outsideTheWindowTheWebMintsNothingUnlessADeveloperSwitchesItOn() {
        service.requestCode(TYPED).block();
        nothingMintedOrSent();

        properties.getAuth().setWebMintsCode(true);
        StepVerifier.create(service.requestCode(TYPED)).assertNext(r -> assertThat(r.issue()).isEqualTo(CodeIssue.SENT))
                .verifyComplete();
        verify(otps).create(any());
        StepVerifier.create(service.requestCode(STRANGER)).assertNext(r -> assertThat(r.issue()).isEqualTo(CodeIssue.NO_ACCOUNT))
                .verifyComplete();
        verify(otps, times(1)).create(any());
    }

    // ---- the window is recorded for account numbers, under their hash ---------------------------------------------

    @Test
    void anInboundMessageFromAnAccountNumberOpensItsWindowUnderTheHash() {
        StepVerifier.create(service.noteInbound(NUMBER, null)).verifyComplete();
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(window).recordInbound(key.capture(), eq(FAMILY), eq(NOW));
        assertThat(key.getValue()).isEqualTo(service.hash(NUMBER)).matches("[0-9a-f]{64}").doesNotContain("8801700000100");
    }

    /** WhatsApp's window runs from the send: Meta's timestamp, never later than our now, never the receipt time. */
    @Test
    void theWindowIsRecordedAtTheSendTimeClampedToNow() {
        Instant sent = NOW.minus(Duration.ofHours(30));
        service.noteInbound(NUMBER, sent).block();
        verify(window).recordInbound(anyString(), eq(FAMILY), eq(sent));
        service.noteInbound(NUMBER, NOW.plusSeconds(3600)).block();
        verify(window).recordInbound(anyString(), eq(FAMILY), eq(NOW));
    }

    @Test
    void anInboundMessageFromANumberWithNoAccountRecordsNothing() {
        StepVerifier.create(service.noteInbound(STRANGER, NOW)).verifyComplete();
        verify(window, never()).recordInbound(anyString(), any(), any());
    }

    // ---- "কোড" on WhatsApp: issueCodeFor(sender) ---------------------------------------------------------------

    /** D2: the code is minted for the sender WhatsApp has proved, and opens that sender's family. No window needed. */
    @Test
    void aCodeAskedForOnWhatsappOpensASessionForTheSendersFamily() {
        StepVerifier.create(service.issueCodeFor(NUMBER)).expectNext(CodeIssue.SENT).verifyComplete();
        verify(delivery).deliver(eq(NUMBER), anyString(), anyString());
        verify(window, never()).lastInbound(anyString());   // the sender's own message is the window
        StepVerifier.create(service.verify(service.hash(TYPED), deliveredCode.get()))
                .assertNext(issued -> assertThat(issued.session().familyId()).isEqualTo(FAMILY))
                .verifyComplete();
    }

    /** The join between the channels: Meta's sender id and every typed spelling hash alike — and no other number does. */
    @Test
    void theSenderAndEveryTypedFormOfTheNumberShareOneHashAndANeighbourDoesNot() {
        String fromWhatsapp = service.hash("+8801700000100");
        for (String typed : List.of("01700000100", "+880 1700-000100", "০১৭০০০০০১০০", "008801700000100")) {
            assertThat(service.hash(typed)).as(typed).isEqualTo(fromWhatsapp);
        }
        assertThat(service.hash("01700000101")).isNotEqualTo(fromWhatsapp);
    }

    @Test
    void onlyTheHashOfTheCodeIsStoredAndItNamesTheFamily() {
        service.issueCodeFor(NUMBER).block();
        ArgumentCaptor<OtpCode> stored = ArgumentCaptor.forClass(OtpCode.class);
        verify(otps).create(stored.capture());
        assertThat(stored.getValue().codeHash()).isEqualTo(PhoneHasher.sha256(deliveredCode.get()))
                .isNotEqualTo(deliveredCode.get());
        assertThat(stored.getValue().phoneHash()).doesNotContain("8801").matches("[0-9a-f]{64}");
        assertThat(stored.getValue().familyId()).isEqualTo(FAMILY);
    }

    @Test
    void aSenderWithNoAccountMintsNothing() {
        StepVerifier.create(service.issueCodeFor(STRANGER)).expectNext(CodeIssue.NO_ACCOUNT).verifyComplete();
        nothingMintedOrSent();
    }

    @Test
    void aSenderOffTheAllowlistMintsNothing() {
        properties.getWa().setAllowlist(List.of("+8801700000999"));
        StepVerifier.create(service.issueCodeFor(NUMBER)).expectNext(CodeIssue.NOT_PERMITTED).verifyComplete();
        nothingMintedOrSent();
    }

    @Test
    void theFifthLiveCodeIsTheLastAndTheSixthIsRefusedAsRateLimited() {
        when(otps.countCreatedSince(anyString(), any())).thenReturn(Mono.just(4L));
        StepVerifier.create(service.issueCodeFor(NUMBER)).expectNext(CodeIssue.SENT).verifyComplete();
        when(otps.countCreatedSince(anyString(), any())).thenReturn(Mono.just(5L));
        StepVerifier.create(service.issueCodeFor(NUMBER)).expectNext(CodeIssue.RATE_LIMITED).verifyComplete();
        verify(otps, times(1)).create(any());
    }

    @Test
    void aWhatsappSendThatFailsIsNotReportedSent() {
        when(delivery.deliver(anyString(), anyString(), anyString())).thenReturn(Mono.just(false));
        StepVerifier.create(service.issueCodeFor(NUMBER)).expectNext(CodeIssue.NOT_DELIVERED).verifyComplete();
    }

    @Test
    void withoutTheHmacSecretIssuingIsAnErrorSignalNotAThrow() {
        properties.getAuth().setHmacSecret("");
        Mono<CodeIssue> issue = service.issueCodeFor(NUMBER);   // assembling it must not throw
        StepVerifier.create(issue).expectErrorMatches(e -> ((BaymaxException) e).status().value() == 503).verify();
    }

    // ---- verify: typed refusals, one API shape ---------------------------------------------------------------

    /** Acceptance: given a wrong code five times, the code is dead and a new request is required. */
    @Test
    void fourWrongGuessesAreWrongTheFifthBurnsAndABurnedCodeStaysBurned() {
        service.issueCodeFor(NUMBER).block();
        String hash = service.hash(NUMBER);
        for (int i = 1; i <= 4; i++) {
            StepVerifier.create(service.verify(hash, wrong())).expectErrorSatisfies(e -> rejected(e, VerifyFailure.WRONG)).verify();
        }
        StepVerifier.create(service.verify(hash, wrong())).expectErrorSatisfies(e -> rejected(e, VerifyFailure.BURNED)).verify();
        StepVerifier.create(service.verify(hash, deliveredCode.get())).expectErrorSatisfies(e -> rejected(e, VerifyFailure.BURNED)).verify();
        verify(sessions, never()).create(anyString(), any(), any(), any());

        // a fresh request issues a fresh code, and that one opens a session
        service.issueCodeFor(NUMBER).block();
        StepVerifier.create(service.verify(hash, deliveredCode.get())).expectNextCount(1).verifyComplete();
    }

    @Test
    void fourWrongGuessesThenTheRightOneStillOpensASession() {
        service.issueCodeFor(NUMBER).block();
        String hash = service.hash(NUMBER);
        for (int i = 1; i <= 4; i++) {
            StepVerifier.create(service.verify(hash, wrong())).expectError().verify();
        }
        StepVerifier.create(service.verify(hash, deliveredCode.get())).expectNextCount(1).verifyComplete();
    }

    @Test
    void aCodeIsSingleUseAndAUsedCodeIsNoCodeNotExpired() {
        service.issueCodeFor(NUMBER).block();
        String hash = service.hash(NUMBER);
        StepVerifier.create(service.verify(hash, deliveredCode.get())).expectNextCount(1).verifyComplete();
        StepVerifier.create(service.verify(hash, deliveredCode.get())).expectErrorSatisfies(e -> rejected(e, VerifyFailure.NO_CODE)).verify();
    }

    @Test
    void aCodeTypedAfterItsTenMinutesIsExpiredNotWrong() {
        service.issueCodeFor(NUMBER).block();
        StepVerifier.create(serviceAt(NOW.plus(Duration.ofMinutes(11))).verify(service.hash(NUMBER), deliveredCode.get()))
                .expectErrorSatisfies(e -> rejected(e, VerifyFailure.EXPIRED)).verify();
    }

    /** The other direction: "expired" is never said about a number that has no recent code. */
    @Test
    void aNumberWithNoCodeInTheLastHourIsNoCodeNeverExpired() {
        StepVerifier.create(service.verify(service.hash("+8801700000300"), "123456"))
                .expectErrorSatisfies(e -> rejected(e, VerifyFailure.NO_CODE)).verify();
        service.issueCodeFor(NUMBER).block();
        StepVerifier.create(serviceAt(NOW.plus(Duration.ofHours(2))).verify(service.hash(NUMBER), deliveredCode.get()))
                .expectErrorSatisfies(e -> rejected(e, VerifyFailure.NO_CODE)).verify();
    }

    @Test
    void aCodeOfTheWrongShapeIsFormat() {
        StepVerifier.create(service.verify(service.hash(NUMBER), "12345"))
                .expectErrorSatisfies(e -> rejected(e, VerifyFailure.FORMAT)).verify();
    }

    /** The web may say which; the API sees one shape for every failure (WebAuthService's identical-response contract). */
    private static void rejected(Throwable e, VerifyFailure expected) {
        assertThat(e).isInstanceOf(CodeRejectedException.class);
        assertThat(((CodeRejectedException) e).failure()).isEqualTo(expected);
        assertThat(((BaymaxException) e).status().value()).isEqualTo(401);
        assertThat(((BaymaxException) e).reason()).isEqualTo("invalid_code");
        assertThat(e.getMessage()).isEqualTo("the code is wrong, expired or used");
    }

    // ---- sessions and the rest, unchanged ---------------------------------------------------------------------

    @Test
    void sessionsSlideOnlyAfterTheTouchInterval() {
        WebSession fresh = new WebSession(UUID.randomUUID(), FAMILY, NOW.minusSeconds(60), NOW.minusSeconds(60), NOW.plusSeconds(3600));
        when(sessions.findLive(anyString(), any())).thenReturn(Mono.just(fresh));
        StepVerifier.create(service.authenticate("tok")).assertNext(s -> assertThat(s.expiresAt()).isEqualTo(fresh.expiresAt())).verifyComplete();
        verify(sessions, never()).touch(any(), any(), any());

        WebSession stale = new WebSession(UUID.randomUUID(), FAMILY, NOW.minusSeconds(7200), NOW.minusSeconds(7200), NOW.plusSeconds(3600));
        when(sessions.findLive(anyString(), any())).thenReturn(Mono.just(stale));
        when(sessions.touch(any(), any(), any())).thenReturn(Mono.empty());
        StepVerifier.create(service.authenticate("tok"))
                .assertNext(s -> assertThat(s.expiresAt()).isEqualTo(NOW.plus(properties.getAuth().getSessionTtl()))).verifyComplete();
        verify(sessions).touch(eq(stale.id()), eq(NOW), any());
    }

    @Test
    void withoutTheHmacSecretLoginIsUnavailableNotOpen() {
        properties.getAuth().setHmacSecret("");
        StepVerifier.create(service.verifyNumber(NUMBER, "123456"))
                .expectErrorMatches(e -> ((BaymaxException) e).status().value() == 503).verify();
    }

    /** Regression guard: Java's \d is ASCII-only, so a code typed on a Bangla keyboard was refused as wrong. */
    @Test
    void aCodeTypedInBanglaDigitsOpensTheSession() {
        service.issueCodeFor(NUMBER).block();
        String bangla = com.elioo.baymax.outbound.application.service.BanglaDate.digits(deliveredCode.get());
        assertThat(bangla).doesNotContainPattern("[0-9\\s]");   // the red can only be \d, never whitespace
        StepVerifier.create(service.verify(service.hash(NUMBER), bangla)).expectNextCount(1).verifyComplete();
    }

    /** The other direction: folding must not turn a wrong code right. */
    @Test
    void aBanglaCodeThatFoldsToTheWrongDigitsIsStillWrong() {
        service.issueCodeFor(NUMBER).block();
        StepVerifier.create(service.verify(service.hash(NUMBER), com.elioo.baymax.outbound.application.service.BanglaDate.digits(wrong())))
                .expectError(BaymaxException.class).verify();
        verify(sessions, never()).create(anyString(), any(), any(), any());
    }

    /** New behaviour (not a replay): spaces from a paste are folded away. ASCII digits, so it says nothing about \d. */
    @Test
    void aCodeWithSurroundingSpacesOpensTheSession() {
        service.issueCodeFor(NUMBER).block();
        StepVerifier.create(service.verify(service.hash(NUMBER), " " + deliveredCode.get() + " ")).expectNextCount(1).verifyComplete();
    }

    /**
     * Folding only removes spaces and maps digits: anything else in the code is still refused. The right code with a
     * letter inside or after it is the case that fails if folding ever starts dropping letters; the others are
     * refused for their length whatever folding does.
     */
    @Test
    void aCodeWithLettersOrTooFewDigitsIsStillRefused() {
        service.issueCodeFor(NUMBER).block();
        String hash = service.hash(NUMBER);
        String right = deliveredCode.get();
        for (String typed : List.of(right.substring(0, 3) + "x" + right.substring(3), right + "x", "-" + right,
                right.substring(0, 5), right + "0", right.substring(0, 5) + "x", "")) {
            StepVerifier.create(service.verify(hash, typed)).expectError(BaymaxException.class).verify();
        }
        verify(sessions, never()).create(anyString(), any(), any(), any());
    }
}

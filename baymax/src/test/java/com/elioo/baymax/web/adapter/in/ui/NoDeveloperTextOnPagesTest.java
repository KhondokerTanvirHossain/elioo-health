package com.elioo.baymax.web.adapter.in.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.testsupport.LoggedText;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * No exception message reaches a page or an ERROR log: the copy has no slot for one (UiCopyTest), no call site passes
 * one (the source scan), and the router's error page shows copy chosen by status and reason, never by message (the
 * behavioural tests below, which do not depend on how a call site is spelled).
 */
class NoDeveloperTextOnPagesTest {

    private static final Path WEB = Path.of("src/main/java/com/elioo/baymax/web/adapter/in");
    /** Over the whole file, not a line: {@code [^;]*} spans newlines, so a call split across lines is still one call. */
    private static final Pattern PASSES_A_MESSAGE = Pattern.compile("\\b(t|plain|text|esc|errorPage)\\([^;]*\\bget(Localized)?Message\\(\\)");
    private static final Pattern ERR = Pattern.compile("<div class=\"err\">(.*?)</div>", Pattern.DOTALL);
    private static final String MEDICINE = "Tab. Demoprol 5 mg 1+0+1";

    private final UiCopy copy = new UiCopy();

    @Test
    void noCallSiteHandsAnExceptionMessageToTheCopyOrThePage() throws IOException {
        List<String> found = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(WEB)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(files).hasSizeGreaterThan(8);
        for (Path p : files) {
            String src = Files.readString(p);
            for (Matcher m = PASSES_A_MESSAGE.matcher(src); m.find(); ) {
                int line = (int) src.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
                found.add(p.getFileName() + ":" + line + " " + m.group().replaceAll("\\s+", " "));
            }
        }
        assertThat(found).isEmpty();
    }

    /** The scan itself: a call split over two lines is found, so the empty result above is not a blind spot. */
    @Test
    void theScanFindsACallSplitAcrossLines() {
        String split = "return chrome.text(request,\n        \"app.err.freetier\",\n        be.getMessage());";
        assertThat(PASSES_A_MESSAGE.matcher(split).find()).isTrue();
    }

    private String errorText(WebUiTestKit kit, HttpStatus status) {
        String html = kit.client.get().uri("/app/home").cookie("baymax_session", "tok").cookie("baymax_lang", "bn").exchange()
                .expectStatus().isEqualTo(status).expectBody(String.class).returnResult().getResponseBody();
        assertThat(html).doesNotContain("Demoprol").doesNotContain("free plan allows");
        Matcher m = ERR.matcher(html);
        assertThat(m.find()).as("the error page's text element").isTrue();
        return m.group(1);
    }

    /** A BaymaxException whose message is a medicine: the page shows generic copy, never the message. */
    @Test
    void aRefusalWhoseMessageIsAMedicineShowsTheGenericCopy() {
        WebUiTestKit kit = new WebUiTestKit();
        when(kit.timeline.me(WebUiTestKit.FAMILY)).thenReturn(Mono.error(BaymaxException.conflict("x_y", MEDICINE)));
        assertThat(errorText(kit, HttpStatus.CONFLICT)).isEqualTo(copy.t(Lang.BN, "app.err.generic"));
    }

    /** The documents cap: the family reads app.err.freetier, never "free plan allows 3 document(s)…". */
    @Test
    void theDocumentsCapShowsTheFreeTierCopyNotTheMessage() {
        WebUiTestKit kit = new WebUiTestKit();
        when(kit.timeline.me(WebUiTestKit.FAMILY)).thenReturn(Mono.error(new FreeTierExceededException(
                FreeTierExceededException.DOCUMENTS, "free plan allows 3 document(s) per calendar month")));
        assertThat(errorText(kit, HttpStatus.PAYMENT_REQUIRED)).isEqualTo(copy.t(Lang.BN, "app.err.freetier"));
    }

    /** Any other cap (patients: admin only) is not the family's to read about here: generic, never the documents text. */
    @Test
    void anotherCapShowsTheGenericCopy() {
        WebUiTestKit kit = new WebUiTestKit();
        when(kit.timeline.me(WebUiTestKit.FAMILY)).thenReturn(Mono.error(new FreeTierExceededException(
                FreeTierExceededException.PATIENTS, "free plan allows 1 patient(s)")));
        assertThat(errorText(kit, HttpStatus.PAYMENT_REQUIRED)).isEqualTo(copy.t(Lang.BN, "app.err.generic"));
    }

    /**
     * The reason never picks a copy key by name: a reason that happens to equal another key's suffix ("return" →
     * app.err.return, "ফিরে যান") must not turn a button label into the error message.
     */
    @Test
    void aReasonNeverPicksACopyKeyByItsName() {
        WebUiTestKit kit = new WebUiTestKit();
        when(kit.timeline.me(WebUiTestKit.FAMILY)).thenReturn(Mono.error(BaymaxException.conflict("return", "x")));
        assertThat(errorText(kit, HttpStatus.CONFLICT)).isEqualTo(copy.t(Lang.BN, "app.err.generic"));
    }

    /**
     * The router's last-resort error line: the class at ERROR, never the message, and the line itself exists. Read
     * with the attached throwable's chain too: {@code log.error("…", …, e)} prints e's message at ERROR.
     */
    @Test
    void anUnhandledErrorIsLoggedByClassWithoutItsMessage() {
        Logger logger = (Logger) LoggerFactory.getLogger(BaymaxWebUiRouter.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        Level before = logger.getLevel();
        logger.setLevel(Level.INFO);   // INFO and above must reach the appender, or the absence below proves nothing
        try {
            WebUiTestKit kit = new WebUiTestKit();
            when(kit.timeline.me(WebUiTestKit.FAMILY))
                    .thenReturn(Mono.error(new IllegalStateException("wrapped", new IllegalArgumentException(MEDICINE))));
            String html = kit.client.get().uri("/app/home").cookie("baymax_session", "tok").exchange()
                    .expectStatus().is5xxServerError().expectBody(String.class).returnResult().getResponseBody();
            assertThat(html).doesNotContain("Demoprol");
            List<String> lines = LoggedText.atInfoOrAbove(logged.list);
            assertThat(lines).anyMatch(l -> l.contains("unhandled error on GET /app/home error=IllegalStateException"));
            assertThat(lines).noneMatch(l -> l.contains("Demoprol"));
        } finally {
            logger.detachAppender(logged);
            logger.setLevel(before);
        }
    }
}

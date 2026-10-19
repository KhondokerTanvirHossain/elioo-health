package com.elioo.baymax.web.adapter.in.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** No exception message reaches a page or an ERROR log: the copy has no slot for one (UiCopyTest), and no call site passes one. */
class NoDeveloperTextOnPagesTest {

    private static final Path WEB = Path.of("src/main/java/com/elioo/baymax/web/adapter/in");
    private static final Pattern PASSES_A_MESSAGE = Pattern.compile("\\b(t|plain|text|esc|errorPage)\\([^;]*\\bget(Localized)?Message\\(\\)");

    @Test
    void noCallSiteHandsAnExceptionMessageToTheCopyOrThePage() throws IOException {
        List<String> found = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(WEB)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(files).hasSizeGreaterThan(8);
        for (Path p : files) {
            List<String> lines = Files.readAllLines(p);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = PASSES_A_MESSAGE.matcher(lines.get(i));
                if (m.find()) {
                    found.add(p.getFileName() + ":" + (i + 1) + " " + lines.get(i).trim());
                }
            }
        }
        assertThat(found).isEmpty();
    }

    /** The router's last-resort error line: the class at ERROR, never the message, and the line itself exists. */
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
            org.mockito.Mockito.when(kit.timeline.me(WebUiTestKit.FAMILY))
                    .thenReturn(reactor.core.publisher.Mono.error(new IllegalStateException("Tab. Demoprol 5 mg 1+0+1")));
            String html = kit.client.get().uri("/app/home").cookie("baymax_session", "tok").exchange()
                    .expectStatus().is5xxServerError().expectBody(String.class).returnResult().getResponseBody();
            assertThat(html).doesNotContain("Demoprol");
            List<String> lines = logged.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO))
                    .map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(lines).anyMatch(l -> l.contains("unhandled error on GET /app/home error=IllegalStateException"));
            assertThat(lines).noneMatch(l -> l.contains("Demoprol"));
        } finally {
            logger.detachAppender(logged);
            logger.setLevel(before);
        }
    }
}

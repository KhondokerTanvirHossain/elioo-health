package com.elioo.baymax.web.adapter.in.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.config.BaymaxProperties;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two {@code /app/api} settings an operator edits by hand on the box. Neither may refuse boot — {@code deploy.sh}
 * has already removed the running container when the new one starts, so a refused start is MedScribe and Medioo both
 * down — and neither may fail open. The preview list takes bare UUIDs and forgives what {@code docker --env-file}
 * leaves (quotes, spaces); anything else is dropped with one WARN that gives only a count. A blank public origin
 * refuses every browser mutation, so it is reported at startup instead of found by a family.
 */
class AppApiSettingsTest {

    private static final UUID A = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4300001");
    private static final UUID B = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4300002");

    @Test
    void blankIsNobodyAndBareIdsBindOneEach() {
        assertThat(web("").previewFamilies()).isEmpty();
        assertThat(web(A + "," + B).previewFamilies()).containsExactlyInAnyOrder(A, B);
        assertThat(web(A + " , " + B + " ").previewFamilies()).containsExactlyInAnyOrder(A, B);
    }

    /** {@code BAYMAX_WEB_PREVIEW_FAMILIES="<a>,<b>"}: docker keeps the quotes, so they land on the first and last entry. */
    @Test
    void quotesTheEnvFileKeptAreStrippedNotFatal() {
        assertThat(web("\"" + A + "\"").previewFamilies()).containsExactly(A);
        assertThat(web("\"" + A + "," + B + "\"").previewFamilies()).containsExactlyInAnyOrder(A, B);
        assertThat(web("'" + A + "'").previewFamilies()).containsExactly(A);
        assertThat(web("\"" + A + "\"").rejectedPreviewEntries()).isZero();
    }

    /** Fail closed: a typo or a truncated id previews nobody, and is counted for the WARN. */
    @Test
    void aMalformedEntryIsDroppedAndCountedAndTheOthersStillBind() {
        BaymaxProperties.Web web = web("not-a-uuid," + A + "," + A.toString().substring(0, 20));
        assertThat(web.previewFamilies()).containsExactly(A);
        assertThat(web.rejectedPreviewEntries()).isEqualTo(2);
    }

    /**
     * The production failure, replayed: a quoted and a malformed value in the environment. The context starts (a
     * {@code List<UUID>} binding refused it), the bad entry previews nobody, and one WARN carries only the count.
     */
    @Test
    void theContextStartsWithAQuotedAndAMalformedEntryAndWarnsWithACountOnly() {
        List<ILoggingEvent> warns = warnsDuring(() -> runner()
                .withPropertyValues("baymax.web.preview-family-ids=\"" + A + "\",nope-not-an-id")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(BaymaxProperties.class).getWeb().previewFamilies()).containsExactly(A);
                }));
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).getFormattedMessage()).contains("1").doesNotContain("nope-not-an-id").doesNotContain(A.toString());
    }

    @Test
    void theContextStartsWithOnlyMalformedEntriesAndPreviewsNobody() {
        runner().withPropertyValues("baymax.web.preview-family-ids=1234,abc").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(BaymaxProperties.class).getWeb().previewFamilies()).isEmpty();
        });
    }

    /** A present-but-blank {@code BAYMAX_WEB_PUBLIC_ORIGIN=} resolves to "" (not the default): said at startup. */
    @Test
    void aBlankPublicOriginIsReportedAtStartupAndTheDefaultIsNot() {
        List<ILoggingEvent> blank = warnsDuring(() -> runner().withPropertyValues("baymax.web.public-origin=")
                .run(context -> assertThat(context).hasNotFailed()));
        assertThat(blank).hasSize(1);
        assertThat(blank.get(0).getFormattedMessage()).contains("public-origin").contains("BAYMAX_WEB_PUBLIC_ORIGIN");
        assertThat(warnsDuring(() -> runner().run(context -> assertThat(context).hasNotFailed()))).isEmpty();
        assertThat(warnsDuring(() -> runner().withPropertyValues("baymax.web.preview-family-ids=")
                .run(context -> assertThat(context).hasNotFailed()))).as("a blank preview list is the normal case").isEmpty();
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(Settings.class);
    }

    /** Not a {@code @Configuration}: BaymaxAutoConfiguration component-scans com.elioo.baymax, test classes included. */
    @EnableConfigurationProperties(BaymaxProperties.class)
    @Import(AppApiStartupCheck.class)
    static class Settings {
    }

    private static BaymaxProperties.Web web(String value) {
        return new Binder(new MapConfigurationPropertySource(Map.of("baymax.web.preview-family-ids", value)))
                .bindOrCreate("baymax", BaymaxProperties.class).getWeb();
    }

    private static List<ILoggingEvent> warnsDuring(Runnable run) {
        Logger logger = (Logger) LoggerFactory.getLogger(AppApiStartupCheck.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            run.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }
}

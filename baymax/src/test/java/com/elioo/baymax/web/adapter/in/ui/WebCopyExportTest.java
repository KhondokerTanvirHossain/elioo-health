package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.outbound.application.service.BanglaDate;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.testsupport.Snapshot;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The copy medioo-web renders is generated from the bundles Java renders, never hand-copied (master plan Global
 * Constraints: all copy comes from {@code ui-*_{bn,en}.properties} and {@code messages_{bn,en}.properties}). Each file is
 * written here and byte-compared with the committed one under {@code medioo-web/src/copy/generated/}, so a bundle change
 * that is not regenerated, or a hand edit of a generated file, fails the build.
 *
 * <p>The exported set is exactly the set React may read: every {@link UiCopy#keys} key, verbatim ({@link UiCopy#raw}
 * with no arguments keeps the {@code {0}} placeholders for {@code t()} to fill), plus every urgency line as
 * {@link UiCopy#urgency} itself renders it (unescaped), so WhatsApp and the web word a level from one source.
 *
 * <p>The directory holds nothing else: {@link #everyCommittedGeneratedFileIsOneJavaWrites} lists it and compares it
 * with what {@link #GENERATORS} write, so a stale or hand-made file there (which the Bangla-literal lint would exempt
 * on its {@code _meta} alone) fails the build.
 */
class WebCopyExportTest {

    static final Path GENERATED = Path.of("../medioo-web/src/copy/generated");
    static final Path WRITTEN = Path.of("build/web-copy");

    /**
     * Every generator of {@link #GENERATED}, as file name to content. A new generator that writes there is added here,
     * or its file is reported as an orphan; a generator that writes elsewhere (B2's {@code fixtures/api/}) needs its
     * own directory check of this shape.
     */
    static final List<Supplier<Map<String, String>>> GENERATORS = List.of(
            WebCopyExportTest::copyFiles,
            WebCopyExportTest::calendarFiles,
            BanglaGoldenVectorsTest::files);

    @Test
    void theCopyTheWebImportsIsTheBundlesVerbatim() {
        copyFiles().forEach(WebCopyExportTest::matches);
    }

    @Test
    void theCalendarIsBanglaDatesOwn() {
        calendarFiles().forEach(WebCopyExportTest::matches);
    }

    @Test
    void everyCommittedGeneratedFileIsOneJavaWrites() throws IOException {
        Set<String> written = new TreeSet<>();
        for (Supplier<Map<String, String>> generator : GENERATORS) {
            Map<String, String> files = generator.get();
            assertThat(files).as("a generator that writes nothing").isNotEmpty();
            for (String name : files.keySet()) {
                assertThat(written.add(name)).as(name + " is written by two generators").isTrue();
            }
        }
        Set<String> committed;
        try (Stream<Path> listing = Files.list(GENERATED)) {
            committed = listing.map(p -> p.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
        assertThat(committed).as("the listing of " + GENERATED + " (an empty listing would pass vacuously)").isNotEmpty();

        Set<String> orphans = new TreeSet<>(committed);
        orphans.removeAll(written);
        assertThat(orphans)
                .as("files in " + GENERATED + " that no Java generator writes (stale, or made by hand); delete them")
                .isEmpty();
        Set<String> missing = new TreeSet<>(written);
        missing.removeAll(committed);
        assertThat(missing).as("generated files not committed under " + GENERATED).isEmpty();
    }

    /** copy.bn.json and copy.en.json, with their guards. */
    static Map<String, String> copyFiles() {
        UiCopy copy = new UiCopy();
        Map<Lang, Map<String, String>> byLang = new LinkedHashMap<>();
        for (Lang lang : Lang.values()) {
            Map<String, String> strings = new TreeMap<>();
            for (String key : copy.keys(lang)) {
                strings.put(key, copy.raw(lang, key));
            }
            for (Map.Entry<String, String> line : urgencyLines(copy, lang).entrySet()) {
                assertThat(strings.put(line.getKey(), line.getValue()))
                        .as("urgency key " + line.getKey() + " is also a UI key: one source per string").isNull();
            }
            for (Map.Entry<String, String> line : messageLines(copy, lang).entrySet()) {
                assertThat(strings.put(line.getKey(), line.getValue()))
                        .as("message line " + line.getKey() + " is also a UI key: one source per string").isNull();
            }
            byLang.put(lang, strings);
        }

        Map<String, String> bn = byLang.get(Lang.BN);
        Map<String, String> en = byLang.get(Lang.EN);
        assertThat(bn.keySet()).as("bn and en export the same keys").isEqualTo(en.keySet());
        for (Map<String, String> strings : byLang.values()) {
            assertThat(strings.size()).as("the whole bundle set, not a slice of it").isGreaterThan(300);
            assertThat(strings).containsKeys("tab.chat", "now.notdoctor", "urgency.now", "value.chip.unknown");
            // Plan W5: the composer's reply is chat.ask.reply followed by the red-flag line WhatsApp appends to
            // wa.fallback, so the web needs both, each from its one source.
            assertThat(strings).containsKeys("chat.ask.reply", "safety.red_flag_line");
        }

        Map<String, String> files = new TreeMap<>();
        for (Lang lang : Lang.values()) {
            Map<String, Object> file = new TreeMap<>(byLang.get(lang));
            file.put("_meta", Snapshot.meta(WebCopyExportTest.class, UiCopy.PARTS.stream()
                    .map(part -> "baymax/src/main/resources/baymax/" + part + "_" + lang.code + ".properties")
                    .collect(Collectors.joining(", "))
                    + "; urgency.* and " + String.join(", ", UiCopy.MESSAGE_LINES)
                    + " of baymax/src/main/resources/baymax/messages_" + lang.code + ".properties"));
            files.put("copy." + lang.code + ".json", Snapshot.json(file));
        }
        return files;
    }

    /** calendar.bn.json: BanglaDate's month names and digits. */
    static Map<String, String> calendarFiles() {
        assertThat(BanglaDate.months()).hasSize(12);
        String digits = BanglaDate.digits("0123456789");
        assertThat(digits).hasSize(10).doesNotContainPattern("[0-9]");

        Map<String, Object> file = new TreeMap<>();
        file.put("_meta", Snapshot.meta(WebCopyExportTest.class, "BanglaDate.months() and BanglaDate.digits(\"0123456789\")"));
        file.put("months", BanglaDate.months());
        file.put("digits", digits);
        return Map.of("calendar.bn.json", Snapshot.json(file));
    }

    /**
     * Every urgency line as {@link UiCopy#urgency} renders it, unescaped, keyed as the {@code /app/api} JSON names it
     * ({@code urgency.<level>[.short]}): read from UiCopy itself, so a change to how UiCopy loads its messages cannot
     * leave the export behind. The bundle's own {@code urgency.*} keys must be exactly these, and each line must
     * round-trip the escape unchanged, or the unescape would have altered the text.
     */
    static Map<String, String> urgencyLines(UiCopy copy, Lang lang) {
        Map<String, String> lines = new TreeMap<>();
        for (Urgency level : Urgency.values()) {
            if (level == Urgency.ROUTINE) {
                continue;
            }
            for (boolean shortForm : new boolean[] {false, true}) {
                String key = "urgency." + level.dbValue() + (shortForm ? ".short" : "");
                lines.put(key, HtmlUtils.htmlUnescape(copy.urgency(lang, level, shortForm)));
            }
        }
        assertThat(lines).as("urgency lines").isNotEmpty();

        Properties messages = messages(lang);
        Map<String, String> inBundle = new TreeMap<>();
        for (String key : messages.stringPropertyNames()) {
            if (key.startsWith("urgency.")) {
                inBundle.put(key, messages.getProperty(key));
            }
        }
        assertThat(lines).as("UiCopy.urgency renders exactly the urgency.* lines of messages_" + lang.code)
                .isEqualTo(inBundle);
        return lines;
    }

    /**
     * Every line of {@link UiCopy#MESSAGE_LINES} as {@link UiCopy#messageLine} returns it, each held byte for byte to
     * the messages bundle it comes from, so the web reads the line WhatsApp sends and not a copy that could drift.
     */
    static Map<String, String> messageLines(UiCopy copy, Lang lang) {
        Properties messages = messages(lang);
        Map<String, String> lines = new TreeMap<>();
        for (String key : UiCopy.MESSAGE_LINES) {
            String line = copy.messageLine(lang, key);
            assertThat(line).as(key + " is messages_" + lang.code + "'s line verbatim").isEqualTo(messages.getProperty(key));
            lines.put(key, line);
        }
        assertThat(lines).as("message lines").isNotEmpty();
        return lines;
    }

    /** Snapshot-compares one generated file with its committed twin under medioo-web/src/copy/generated/. */
    static void matches(String name, String content) {
        Snapshot.matches(GENERATED.resolve(name), WRITTEN.resolve(name), content,
                "cp baymax/" + WRITTEN.resolve(name) + " medioo-web/src/copy/generated/" + name);
    }

    /** {@code messages_<lang>.properties} off the classpath: only to check that no urgency line is left out. */
    private static Properties messages(Lang lang) {
        String path = "/baymax/messages_" + lang.code + ".properties";
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(Objects.requireNonNull(UiCopy.class.getResourceAsStream(path), path),
                StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }
}

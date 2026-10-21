package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.outbound.application.service.BanglaDate;
import com.elioo.baymax.testsupport.Snapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The copy medioo-web renders is generated from the bundles Java renders, never hand-copied (master plan Global
 * Constraints: all copy comes from {@code ui-*_{bn,en}.properties} and {@code messages_{bn,en}.properties}). Each file is
 * written here and byte-compared with the committed one under {@code medioo-web/src/copy/generated/}, so a bundle change
 * that is not regenerated, or a hand edit of a generated file, fails the build.
 *
 * <p>The exported set is exactly the set React may read: every {@link UiCopy#keys} key, verbatim ({@link UiCopy#raw}
 * with no arguments keeps the {@code {0}} placeholders for {@code t()} to fill), plus the {@code urgency.*} lines of
 * {@code messages_<lang>.properties}, read as {@link UiCopy}'s constructor reads them, so WhatsApp and the web word a
 * level from one source.
 */
class WebCopyExportTest {

    static final Path GENERATED = Path.of("../medioo-web/src/copy/generated");
    static final Path WRITTEN = Path.of("build/web-copy");

    private final UiCopy copy = new UiCopy();

    @Test
    void theCopyTheWebImportsIsTheBundlesVerbatim() {
        Map<Lang, Map<String, String>> byLang = new LinkedHashMap<>();
        for (Lang lang : Lang.values()) {
            Map<String, String> strings = new TreeMap<>();
            for (String key : copy.keys(lang)) {
                strings.put(key, copy.raw(lang, key));
            }
            Properties messages = messages(lang);
            for (String key : messages.stringPropertyNames()) {
                if (key.startsWith("urgency.")) {
                    assertThat(strings.put(key, messages.getProperty(key)))
                            .as("urgency key " + key + " is also a UI key: one source per string").isNull();
                }
            }
            byLang.put(lang, strings);
        }

        Map<String, String> bn = byLang.get(Lang.BN);
        Map<String, String> en = byLang.get(Lang.EN);
        assertThat(bn.keySet()).as("bn and en export the same keys").isEqualTo(en.keySet());
        for (Map<String, String> strings : byLang.values()) {
            assertThat(strings.size()).as("the whole bundle set, not a slice of it").isGreaterThan(300);
            assertThat(strings).containsKeys("tab.chat", "now.notdoctor", "urgency.now", "value.chip.unknown");
        }

        for (Lang lang : Lang.values()) {
            Map<String, Object> file = new TreeMap<>(byLang.get(lang));
            file.put("_meta", meta(getClass(), UiCopy.PARTS.stream()
                    .map(part -> "baymax/src/main/resources/baymax/" + part + "_" + lang.code + ".properties")
                    .collect(Collectors.joining(", "))
                    + "; urgency.* of baymax/src/main/resources/baymax/messages_" + lang.code + ".properties"));
            matches("copy." + lang.code + ".json", json(file));
        }
    }

    @Test
    void theCalendarIsBanglaDatesOwn() {
        assertThat(BanglaDate.months()).hasSize(12);
        String digits = BanglaDate.digits("0123456789");
        assertThat(digits).hasSize(10).doesNotContainPattern("[0-9]");

        Map<String, Object> file = new TreeMap<>();
        file.put("_meta", meta(getClass(), "BanglaDate.months() and BanglaDate.digits(\"0123456789\")"));
        file.put("months", BanglaDate.months());
        file.put("digits", digits);
        matches("calendar.bn.json", json(file));
    }

    /** Snapshot-compares one generated file with its committed twin under medioo-web/src/copy/generated/. */
    static void matches(String name, String content) {
        Snapshot.matches(GENERATED.resolve(name), WRITTEN.resolve(name), content,
                "cp baymax/" + WRITTEN.resolve(name) + " medioo-web/src/copy/generated/" + name);
    }

    /** The envelope every Java-written medioo-web file carries; the Bangla-literal lint checks the test exists. */
    static Map<String, String> meta(Class<?> generator, String source) {
        Map<String, String> meta = new TreeMap<>();
        meta.put("generated_by", "baymax " + generator.getSimpleName());
        meta.put("source", source);
        return meta;
    }

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build();

    /**
     * Keys sorted, two-space indent, {@code "key": value}, a trailing newline: the shape {@code JSON.stringify(x, null, 2)}
     * writes, so the byte comparison is stable and a reviewer's diff is readable.
     */
    static String json(Object tree) {
        DefaultIndenter indent = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter(
                Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER))
                .withObjectIndenter(indent)
                .withArrayIndenter(indent);
        try {
            return JSON.writer(printer).writeValueAsString(tree) + "\n";
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code messages_<lang>.properties}, loaded from the classpath exactly as {@link UiCopy}'s constructor loads it. */
    static Properties messages(Lang lang) {
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

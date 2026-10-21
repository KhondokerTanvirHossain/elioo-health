package com.elioo.baymax.testsupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A file Java generates for another part of the repo (medioo-web's copy, calendar, golden vectors, fixtures) is
 * committed and byte-compared on every build: the test writes what it would generate to {@code written}, and the
 * committed file must equal it. Vitest reads only the committed file, so CI step order never matters, and a hand edit
 * of a generated file fails here.
 *
 * <p>Every such file has one envelope, written only here so no generator grows a second pretty-printer: a
 * {@link #meta} entry under {@code _meta}, keys sorted, two-space indent, {@code "key": value}, a trailing newline
 * ({@link #json}).
 *
 * <p>Tests run with the working directory at {@code baymax/}, so a committed path looks like {@code ../medioo-web/...}.
 */
public final class Snapshot {

    private Snapshot() {
    }

    /**
     * Writes {@code content} to {@code written} (creating directories), then asserts that {@code committed} exists and
     * equals it byte for byte. The failure message is {@code regenerate} (a ready-to-run {@code cp} command) plus the
     * path that was written.
     */
    public static void matches(Path committed, Path written, String content, String regenerate) {
        try {
            Path parent = written.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(written, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + written, e);
        }
        String how = regenerate + "   (generated: " + written.toAbsolutePath().normalize() + ")";
        assertThat(Files.exists(committed)).as("committed " + committed + " does not exist; review and run: " + how).isTrue();
        String onDisk;
        try {
            onDisk = Files.readString(committed, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + committed, e);
        }
        assertThat(onDisk).as(committed + " differs from what Java generates; review and run: " + how).isEqualTo(content);
    }

    /**
     * The {@code _meta} entry of a generated file: {@code generated_by} is {@code "baymax <TestSimpleName>"}, which the
     * Bangla-literal lint resolves to a Java test that exists; {@code source} says what the content was read from.
     */
    public static Map<String, String> meta(Class<?> generator, String source) {
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
    public static String json(Object tree) {
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
}

package com.elioo.baymax.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A file Java generates for another part of the repo (medioo-web's copy, calendar, golden vectors, fixtures) is
 * committed and byte-compared on every build: the test writes what it would generate to {@code written}, and the
 * committed file must equal it. Vitest reads only the committed file, so CI step order never matters, and a hand edit
 * of a generated file fails here.
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
}

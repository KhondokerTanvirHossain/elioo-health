package com.elioo.baymax.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every read of a ListAppender's {@code list}, in every module's tests, goes through {@code LogCapture}. The bare list
 * is filled under the appender's monitor by whichever thread logs, and a test that streams it while a reactor thread is
 * still appending dies on a ConcurrentModificationException that names no defect (main run 39834549827,
 * WaLogHygieneTest; #142, RetakeLinkAcceptanceTest). Fixed one test at a time it came back in the next one, and one test
 * locked the list instead of the appender, which guards nothing. Derived from the sources, not from a list of the
 * tests someone remembered: a log test added tomorrow is swept the day it lands.
 *
 * <p>The sweep does not tie a read to a declaration it can see. An appender reached through a helper's return value
 * ({@code var logged = attachTo(Foo.class)}, {@code capture(Foo.class).list}), a raw-typed one, or a field declared in
 * another file ({@code support.logged.list}) is declared nowhere this file could match it, and was missed when the sweep
 * looked only for {@code <declared name>.list}. So every field read spelled {@code .list} (not the {@code .list(...)}
 * call of {@code Files.list} or {@code Collections.list}) in a test source is a finding, LogCapture.java and
 * {@link #ALLOWED} apart.
 */
class ListAppenderReadsGoThroughLogCaptureTest {

    /**
     * A ListAppender declaration: field, local, parameter, fully qualified or not, raw or generic, and
     * {@code var x = new ListAppender}. Used only to prove the walk reached the log tests; reads are found without it.
     */
    private static final Pattern DECLARED = Pattern.compile(
            "ListAppender\\s*(?:<(?:[^<>]|<[^<>]*>)*>)?\\s+(\\w+)|\\bvar\\s+(\\w+)\\s*=\\s*new\\s+(?:[\\w.]+\\.)?ListAppender\\b");
    /** A field read spelled {@code .list}: after a name, after a call's {@code )}, or opening a continuation line. */
    private static final Pattern READ = Pattern.compile("(?:\\b\\w+|\\)|^)\\s*\\.list\\b(?!\\s*\\()");
    private static final String HELPER = "LogCapture.java";

    /**
     * The {@code .list} reads that are not an appender's, by file and the code they sit in, each with its reason. Every
     * entry must still match something, so a stale entry cannot quietly cover a later read.
     */
    private static final Map<String, String> ALLOWED = Map.of(   // # for the dot, as in the samples below
            dotted("UploadScriptDomTest.java|Cls.prototype#list = function"),
            "JavaScript in a text block: the fake browser's classList shim defines a method named list");

    @Test
    void noTestReadsAnAppendersListExceptThroughLogCapture() throws IOException {
        Path repo = Path.of("..").toAbsolutePath().normalize();
        List<Path> sources = testSources(repo);
        List<String> reads = new ArrayList<>();
        Set<String> declaring = new TreeSet<>();
        Set<String> helperReads = new TreeSet<>();
        Set<String> allowedUsed = new TreeSet<>();
        for (Path file : sources) {
            String text = Files.readString(file);
            String name = file.getFileName().toString();
            if (!declarations(text).isEmpty()) {
                declaring.add(repo.relativize(file).toString());
            }
            for (String read : bareReads(text)) {
                String allowed = ALLOWED.keySet().stream()
                        .filter(k -> k.startsWith(name + "|") && read.contains(k.substring(k.indexOf('|') + 1)))
                        .findFirst().orElse(null);
                if (name.equals(HELPER)) {
                    helperReads.add(read);
                } else if (allowed != null) {
                    allowedUsed.add(allowed);
                } else {
                    reads.add(repo.relativize(file) + ": " + read);
                }
            }
        }

        // vacuous if the walk missed the roots it exists for, or the pattern no longer sees a declaration or a read
        assertThat(declaring).as("test sources declaring a ListAppender, both modules")
                .hasSizeGreaterThan(30)
                .anyMatch(p -> p.startsWith("medscribe-ai/"))
                .anyMatch(p -> p.endsWith("WaLogHygieneTest.java"));
        assertThat(helperReads).as("the helper's own read of the list, which proves the pattern sees one").isNotEmpty();
        assertThat(allowedUsed).as("allowlist entries that still match a read (a stale one is removed, not kept)")
                .containsExactlyInAnyOrderElementsOf(ALLOWED.keySet());
        assertThat(reads).as("reads of an appender's list that bypass LogCapture.events (the CME race)").isEmpty();
    }

    /** The pattern itself, both directions: every spelling of a bare read is seen; the helper's call and other lists are not. */
    @Test
    void thePatternSeesEverySpellingOfABareReadAndNothingElse() {
        // written with # for the dot, or this file would be the sweep's own finding
        String bad = """
                private ListAppender<ILoggingEvent> logged;
                ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> guard = new ch.qos.logback.core.read.ListAppender<>();
                var codes = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
                private static String at(ListAppender<ILoggingEvent> chain, int x) {
                String a = logged#list.stream().map(f).collect(joining());
                assertThat(guard#list).isEmpty();
                for (ILoggingEvent e : List.copyOf(codes#list)) {
                synchronized (chain#list) {
                ListAppender raw = new ListAppender();
                raw#list.forEach(e -> seen.add(e));
                var attached = attachTo(UploadService.class);
                String b = attached#list.stream().map(f).collect(joining());
                assertThat(capture(UploadService.class)#list).isEmpty();
                assertThat(support.captured#list).hasSize(2);
                List<ILoggingEvent> chained = capture(UploadService.class)
                        #list;
                """;
        assertThat(bareReads(dotted(bad))).as("bare reads: typed, qualified, var-new, parameter, raw type, var bound to a "
                + "helper, through a call, through another file's field, a chained continuation line").hasSize(9);
        assertThat(declarations(dotted(bad))).as("declarations, the raw type included").contains("logged", "raw");

        String good = """
                private ListAppender<ILoggingEvent> logged;
                String a = LogCapture.events(logged).stream().map(f).collect(joining());
                try (Stream<Path> listing = Files.list(dir)) {
                for (ZipEntry entry : Collections.list(zip.entries())) {
                try (Stream<Path> files = java.nio.file.Files.list(COMMITTED)) {
                List<Document> page = repo.list (query);
                Function<Path, Stream<Path>> walk = Files::list;
                List<String> listed = other.listed;
                // the bare logged#list threw a CME
                 * streaming logged#list while a reactor thread appends
                """;
        assertThat(bareReads(dotted(good))).as("the helper's call, a list(...) call, a method reference, a longer name, comments")
                .isEmpty();
    }

    private static String dotted(String sample) {
        return sample.replace('#', '.');
    }

    private static List<Path> testSources(Path repo) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repo)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                for (String root : List.of("src/test/java", "src/testFixtures/java")) {
                    Path dir = module.resolve(root);
                    if (Files.isDirectory(dir)) {
                        try (Stream<Path> walk = Files.walk(dir)) {
                            walk.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static Set<String> declarations(String text) {
        Set<String> names = new TreeSet<>();
        Matcher m = DECLARED.matcher(text);
        while (m.find()) {
            names.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        return names;
    }

    /** Each code line (not a comment line) that reads a field spelled {@code .list}, whatever it was read from. */
    private static List<String> bareReads(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String t = line.strip();
            if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) {
                continue;
            }
            if (READ.matcher(line).find()) {
                out.add(t);
            }
        }
        return out;
    }
}

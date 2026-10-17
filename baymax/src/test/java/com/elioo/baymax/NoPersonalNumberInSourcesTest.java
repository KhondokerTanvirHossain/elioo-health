package com.elioo.baymax;

import com.elioo.baymax.wa.WaFixtures;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/HISTORY_REWRITE.md: a personal number was purged from history, and came back in six test files. Every file
 * in the tree is checked, not a list, and in every way the number can be written: with separators, in Bangla
 * digits, masked, and inside a wamid (base64 of the number) — the encoding this PR is about.
 *
 * <p>{@code docs/} is not walked: it still records the number on purpose (the filter-repo recipe in
 * HISTORY_REWRITE.md, the pilot number in MEDIOO.md) pending Tanvir's ruling, and the git-ignored test corpus under
 * {@code docs/testset} holds patient data this test must not read.</p>
 */
class NoPersonalNumberInSourcesTest {

    /** The ten digits after +880, spelled in pieces so this file does not itself contain them. */
    private static final String CORE = "17933" + "99171";
    private static final String MASKED = "****" + "9171";

    /** Characters people put between digits; removed before matching, so "+880 1793-…" is caught. */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-.()\\u00A0\\u2010-\\u2015]");
    private static final Pattern TEXT_FILE = Pattern.compile(
            ".*\\.(java|kt|properties|ya?ml|xml|sql|py|sh|json|html|css|js|md|csv|txt|toml|gradle|conf)$");
    private static final Set<String> TEXT_NAMES = Set.of("Dockerfile", ".env.example", ".test-fixtures-allowlist",
            ".gitignore", ".dockerignore", "Caddyfile");
    /** Never source, at any depth. */
    private static final Set<String> SKIPPED_ANYWHERE = Set.of(".git", ".gradle", ".idea", "node_modules", ".claude");
    /**
     * Build output, skipped only at the repo root or directly under a module: {@code out} is also a hexagonal
     * package ({@code adapter/out/}), and skipping it by name at any depth silently dropped every outbound adapter
     * and its tests from the walk (CLAUDE.md, the {@code log/} ignore rule).
     */
    private static final Set<String> SKIPPED_AT_TOP = Set.of("build", "bin", "out");

    /**
     * The number as base64 at each of the three byte alignments it can have inside a longer encoded stream: only
     * whole 3-byte groups encode to fixed characters, so the digits from the first group boundary on are what any
     * wamid carrying the number must contain, whatever precedes them.
     */
    private static List<String> base64Forms() {
        List<String> forms = new ArrayList<>();
        for (int skip = 0; skip < 3; skip++) {
            String aligned = CORE.substring(skip, skip + 3 * ((CORE.length() - skip) / 3));
            forms.add(Base64.getEncoder().encodeToString(aligned.getBytes(StandardCharsets.US_ASCII)));
        }
        return forms;
    }

    private static String foldBanglaDigits(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out.append(c >= '০' && c <= '৯' ? (char) ('0' + (c - '০')) : c);
        }
        return out.toString();
    }

    static boolean carriesThePurgedNumber(String text) {
        if (text.contains(MASKED)) {
            return true;
        }
        if (SEPARATORS.matcher(foldBanglaDigits(text)).replaceAll("").contains(CORE)) {
            return true;
        }
        return base64Forms().stream().anyMatch(text::contains);
    }

    /** The matcher in the dangerous direction: each way of writing the number is caught. */
    @Test
    void theMatcherCatchesEveryWayTheNumberIsWritten() {
        assertThat(carriesThePurgedNumber("+880" + CORE)).isTrue();
        assertThat(carriesThePurgedNumber("+880 " + CORE.substring(0, 4) + "-" + CORE.substring(4))).as("with separators").isTrue();
        assertThat(carriesThePurgedNumber("০" + "১৭৯৩৩" + "-" + "৯৯১৭১")).as("Bangla digits, with a separator").isTrue();
        assertThat(carriesThePurgedNumber(MASKED)).as("masked").isTrue();
        for (int prefix = 0; prefix < 3; prefix++) {
            // the number at each alignment inside a wamid, with and without the "wamid." prefix
            String wamid = WaFixtures.wamid("880".substring(prefix) + CORE, 7);
            assertThat(carriesThePurgedNumber(wamid)).as("inside a wamid, alignment %d", prefix).isTrue();
            assertThat(carriesThePurgedNumber(wamid.substring("wamid.".length()))).isTrue();
        }
    }

    /** And in the other: the fixture block and its wamids, which every test uses, are not the number. */
    @Test
    void theMatcherLeavesTheFixtureBlockAlone() {
        assertThat(carriesThePurgedNumber("+8801700000000 8801700000000 01700000000 ০১৭০০০০০০০০ ****0000")).isFalse();
        assertThat(carriesThePurgedNumber(WaFixtures.wamid("8801700000000", 1))).isFalse();
        assertThat(carriesThePurgedNumber(CORE.substring(0, 5))).isFalse();
    }

    @Test
    void noFileInTheTreeCarriesThePurgedNumber() throws IOException {
        Path repo = Path.of("..").toAbsolutePath().normalize();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(repo)) {
            files = walk.filter(p -> !skipped(repo, p))
                    .filter(Files::isRegularFile)
                    .filter(p -> TEXT_FILE.matcher(p.getFileName().toString()).matches()
                            || TEXT_NAMES.contains(p.getFileName().toString()))
                    .toList();
        }
        // vacuous if the walk found nothing, or missed the roots it exists for
        assertThat(files).hasSizeGreaterThan(500);
        assertThat(files).anyMatch(p -> p.endsWith("baymax/src/test/java/com/elioo/baymax/NoPersonalNumberInSourcesTest.java"))
                .anyMatch(p -> p.startsWith(repo.resolve("medscribe-ai/src/main")))
                .anyMatch(p -> p.startsWith(repo.resolve("scripts")))
                .anyMatch(p -> p.startsWith(repo.resolve(".github")))
                .as("a source package named like a build directory is still walked")
                .anyMatch(p -> p.endsWith("baymax/src/test/java/com/elioo/baymax/wa/adapter/out/WaGraphClientTest.java"))
                .anyMatch(p -> p.toString().contains("/adapter/out/") && p.toString().contains("/src/main/"));

        List<String> hits = files.stream().filter(p -> {
            try {
                // decoded leniently: a byte that is not UTF-8 becomes U+FFFD instead of hiding the whole file
                return carriesThePurgedNumber(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).map(p -> repo.relativize(p).toString()).toList();
        assertThat(hits).isEmpty();
    }

    private static boolean skipped(Path repo, Path p) {
        Path rel = repo.relativize(p);
        if (rel.getNameCount() > 0 && rel.getName(0).toString().equals("docs")) {
            return true;
        }
        for (int i = 0; i < rel.getNameCount(); i++) {
            String part = rel.getName(i).toString();
            if (SKIPPED_ANYWHERE.contains(part) || (i <= 1 && SKIPPED_AT_TOP.contains(part))) {
                return true;
            }
        }
        String name = p.getFileName().toString();
        // local secrets (.env.local and its backups) are never read; the tracked example is
        return name.startsWith(".env") && !name.equals(".env.example");
    }
}

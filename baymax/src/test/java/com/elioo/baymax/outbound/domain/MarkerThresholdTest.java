package com.elioo.baymax.outbound.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan B6: one unit fold for the module. The স্বাস্থ্য series key folds a unit with {@link MarkerThreshold#foldUnit}, the
 * thresholds and the value sheet's earlier readings compare with {@link MarkerThreshold#sameUnit}, and the nudge trend
 * groups its series with it too; there is no second copy that could drift.
 *
 * <p>Review of #122, finding 3: the fold is checked against equivalence classes written down here by hand, independently
 * of the code, never against {@code sameUnit} (which is defined as the same fold, so comparing the two asserts x == x).
 * Every pair inside a class folds equal, and every pair across classes folds different.</p>
 */
class MarkerThresholdTest {

    /** Hand-labelled: which spellings are one unit. MICRO SIGN and GREEK MU are written as escapes so no editor folds them. */
    private static final List<List<String>> CLASSES = List.of(
            List.of("µmol/L",      // MICRO SIGN, as labs print it
                    "μmol/L",      // GREEK SMALL LETTER MU, which NFKC makes of it
                    "umol/L",           // ASCII u, as a hand-edited properties file says it
                    "UMOL/L"),
            List.of("mmol/L", "mmol/l", " mmol / L ", "MMOL/L"),
            List.of("mg/dL", "mg/dl"),
            List.of("10^3/µL", "10^3/uL"),
            List.of("%"),
            List.of("mmol/mol"),
            List.of(""),
            List.of("mIU/L"));

    @Test
    void foldUnitJoinsExactlyTheHandLabelledClasses() {
        assertThat(CLASSES.get(0).get(0).codePointAt(0)).isEqualTo(0x00B5);
        assertThat(CLASSES.get(0).get(1).codePointAt(0)).isEqualTo(0x03BC);
        int inside = 0, across = 0;
        for (int i = 0; i < CLASSES.size(); i++) {
            for (int j = 0; j < CLASSES.size(); j++) {
                for (String a : CLASSES.get(i)) {
                    for (String b : CLASSES.get(j)) {
                        String fa = MarkerThreshold.foldUnit(a), fb = MarkerThreshold.foldUnit(b);
                        if (i == j) {
                            assertThat(fa).as("'%s' and '%s' are one unit", a, b).isEqualTo(fb);
                            inside++;
                        } else {
                            assertThat(fa).as("'%s' and '%s' are two units", a, b).isNotEqualTo(fb);
                            across++;
                        }
                    }
                }
            }
        }
        assertThat(inside).as("pairs inside a class").isGreaterThan(30);
        assertThat(across).as("pairs across classes").isGreaterThan(200);
        assertThat(MarkerThreshold.foldUnit(null)).isEqualTo(MarkerThreshold.foldUnit(""));
    }

    private static final Path MAIN = Path.of("src/main/java");
    /** A replace of MICRO SIGN or GREEK MU, as a literal or an escape: the step that makes a unit fold. */
    private static final Pattern MU_REPLACE = Pattern.compile(
            "replace(?:All)?\\(\\s*['\"](?:µ|μ|\\\\u00[bB]5|\\\\u03[bB][cC])");

    /**
     * Review of #122, finding 12: no copy of the unit fold outside {@code MarkerThreshold}. A file that NFKC-normalises and
     * replaces mu is a second fold; if it drifts, the nudge trend and the স্বাস্থ্য trend group units differently and
     * nothing else fails. Not vacuous: the scan must recognise MarkerThreshold's own fold.
     */
    @Test
    void theUnitFoldIsWrittenOnlyInMarkerThreshold() throws IOException {
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(MAIN)) {
            sources = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(sources).as("Java sources under " + MAIN.toAbsolutePath()).hasSizeGreaterThan(50);
        Path own = Path.of("outbound/domain/MarkerThreshold.java");
        List<String> folds = new ArrayList<>();
        boolean ownIsSeen = false;
        for (Path source : sources) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            if (text.contains("Normalizer.normalize") && MU_REPLACE.matcher(text).find()) {
                if (source.endsWith(own)) {
                    ownIsSeen = true;
                } else {
                    folds.add(MAIN.relativize(source).toString());
                }
            }
        }
        assertThat(ownIsSeen).as("the scan recognises MarkerThreshold's fold").isTrue();
        assertThat(folds).as("unit folds outside MarkerThreshold").isEmpty();
    }
}

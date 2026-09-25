package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An alias matches whole words, never letters sitting inside another word.
 *
 * <p>Five mis-mappings were found on batch 2, and they are one defect, not five. The matcher fell back to a
 * bare {@code String.contains}, so any marker whose letters happened to appear inside a longer name won:</p>
 *
 * <ul>
 *   <li>"appe<b>anc</b>e" matched the absolute-neutrophil-count alias</li>
 *   <li>"Hyaline C<b>ast</b>", "Granular Cast", "RBC Cast" matched the liver enzyme AST</li>
 *   <li>"athero<b>scl</b>erosis index" matched the chloride alias "cl-"</li>
 *   <li>"Non-<b>HDL</b> Cholesterol" matched HDL, whose threshold is direction-inverted</li>
 *   <li>"RBC <b>total</b> count" matched a bare "total" alias produced by a comma inside an alias list</li>
 * </ul>
 *
 * <p>Each was patched with an exclusion, which is treating symptoms: the next lab that prints a name nobody
 * anticipated collides again, silently, and the result is a threshold applied to the wrong test. Matching on
 * word boundaries removes the class. Exclusions remain for the genuinely different case — a name that
 * contains a marker as a real word but means something else, like "Non-HDL" or a urinary cast.</p>
 *
 * <p>Both directions in every test: the collision must fail AND the legitimate spelling must still match.
 * A boundary rule that is too strict silently stops recognising "S-POTASSIUM", and the potassium threshold
 * goes back to never firing — the exact defect this work exists to fix.</p>
 */
class MarkerMatchingIsWholeWordTest {

    /** The real shipped alias table, so this tests what production matches. */
    private static MarkerMatcher shippedMatcher() {
        BaymaxProperties properties = new BaymaxProperties();
        properties.setMarkers(loadShippedMarkers());
        return new MarkerMatcher(properties);
    }

    private static List<BaymaxProperties.Marker> loadShippedMarkers() {
        java.util.Map<Integer, java.util.Map<String, String>> rows = new java.util.TreeMap<>();
        try (var in = MarkerMatchingIsWholeWordTest.class.getResourceAsStream("/markers.properties")) {
            var properties = new java.util.Properties();
            properties.load(in);
            var pattern = java.util.regex.Pattern.compile("baymax\\.markers\\[(\\d+)]\\.([a-z\\-]+)");
            for (String key : properties.stringPropertyNames()) {
                var m = pattern.matcher(key);
                if (m.matches()) {
                    rows.computeIfAbsent(Integer.parseInt(m.group(1)), k -> new java.util.HashMap<>())
                            .put(m.group(2), properties.getProperty(key));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("markers.properties is missing", e);
        }
        List<BaymaxProperties.Marker> markers = new java.util.ArrayList<>();
        for (var row : rows.values()) {
            BaymaxProperties.Marker marker = new BaymaxProperties.Marker();
            marker.setCanonical(row.get("canonical"));
            marker.setAliases(split(row.get("aliases")));
            marker.setNotAliases(split(row.get("not-aliases")));
            markers.add(marker);
        }
        return markers;
    }

    private static List<String> split(String value) {
        return value == null ? List.of()
                : java.util.Arrays.stream(value.split(",")).map(String::trim)
                        .filter(s -> !s.isEmpty()).toList();
    }

    /** The five collisions, each named after the page that produced it. */
    @Test
    void lettersInsideALongerWordDoNotMatchAnAlias() {
        MarkerMatcher matcher = shippedMatcher();

        assertThat(matcher.canonicalFor("Appearance", null))
                .as("appeANCe does not contain the word anc").isEmpty();
        assertThat(matcher.canonicalFor("Atherosclerosis Index", null))
                .as("atheroSCLerosis does not contain the word cl").isEmpty();
        assertThat(matcher.canonicalFor("RBC total count", null))
                .as("a red cell count is not bilirubin")
                .isNotEqualTo(java.util.Optional.of("bilirubin_total"));
    }

    /**
     * The urinary casts. "Cast" is a whole word AND contains "ast" as letters — so word-boundary matching
     * alone fixes this one, and the exclusion becomes belt and braces rather than the only defence.
     */
    @Test
    void aUrinaryCastIsNotTheLiverEnzymeAst() {
        MarkerMatcher matcher = shippedMatcher();

        for (String cast : List.of("Hyaline Cast", "Granular Cast", "RBC Cast", "WBC Cast")) {
            assertThat(matcher.canonicalFor(cast, null))
                    .as("%s is urine microscopy, not a liver enzyme", cast)
                    .isEmpty();
        }
    }

    /**
     * NAMED REGRESSION: Non-HDL cholesterol resolving to HDL.
     *
     * <p>The worst of the five, because HDL's threshold is direction-inverted. A HIGH non-HDL cholesterol is
     * a bad result; read as HDL, where high is protective, it would never escalate — a dangerous value
     * silently reported as reassuring. "HDL" IS a whole word inside "Non-HDL Cholesterol", so the boundary
     * rule does not catch this one and the exclusion is load-bearing.</p>
     */
    @Test
    void nonHdlCholesterolNeverResolvesToHdl() {
        MarkerMatcher matcher = shippedMatcher();

        for (String name : List.of("Non-HDL - CHOLESTEROL", "Non HDL Cholesterol", "non-hdl cholesterol",
                "NON-HDL CHOLESTEROL")) {
            assertThat(matcher.canonicalFor(name, null))
                    .as("%s must never be judged against HDL's inverted direction", name)
                    .isNotEqualTo(java.util.Optional.of("hdl"));
        }

        // It resolves to nothing at all, which is the right answer and not merely an acceptable one: non-HDL
        // cholesterol is its own quantity with its own reference range, and mapping it onto TOTAL cholesterol
        // would judge it against the wrong numbers. Unresolved means the stopgap handles it and the coverage
        // report lists it as a marker awaiting a decision — visible, rather than silently wrong.
        assertThat(matcher.canonicalFor("Non-HDL - CHOLESTEROL", null))
                .as("no marker is better than the wrong marker; it appears in the coverage report instead")
                .isEmpty();
    }

    /** A model-proposed canonical_name of "hdl" on a non-HDL row must not sneak past either. */
    @Test
    void nonHdlIsNotRescuedByTheModelsOwnCanonicalName() {
        assertThat(shippedMatcher().canonicalFor("Non-HDL Cholesterol", "hdl"))
                .as("the name on the page overrides a model canonical_name that would invert the direction")
                .isNotEqualTo(java.util.Optional.of("hdl"));
    }

    /**
     * THE OTHER DIRECTION, and the one that would do the damage if the boundary rule were too strict: every
     * spelling batch 2 actually printed must still resolve. A rule that stops recognising "S-POTASSIUM"
     * returns the potassium threshold to never firing.
     */
    @Test
    void everyLegitimateSpellingStillMatches() {
        MarkerMatcher matcher = shippedMatcher();

        record Case(String printed, String expected) {
        }
        List<Case> cases = List.of(
                new Case("S-POTASSIUM", "potassium"),
                new Case("Potassium (K+)", "potassium"),
                new Case("S-SODIUM", "sodium"),
                new Case("Sodium (Na+)", "sodium"),
                new Case("S-CHLORIDE", "chloride"),
                new Case("Haemoglobin", "haemoglobin"),
                new Case("Hemoglobin", "haemoglobin"),
                new Case("Platelet", "platelet"),
                new Case("Platelets total count", "platelet"),
                new Case("Total WBC Count", "wbc"),
                new Case("Total Leucocyte Count", "wbc"),
                new Case("S.ALT ( SGPT )", "alt"),
                new Case("SGPT/ALT", "alt"),
                new Case("S-ALT (GPT)", "alt"),
                new Case("S.AST (SGOT)", "ast"),
                new Case("S-ALKALINE PHOSPHATASE", "alp"),
                new Case("S. Alkaline Phosphatase", "alp"),
                new Case("S-UREA", "urea"),
                new Case("Blood Urea", "urea"),
                new Case("ESR", "esr"),
                new Case("S-CREATININE", "creatinine"),
                new Case("eGFR-(CKD-EPI)", "egfr"),
                new Case("Specific gravity", "specific_gravity"),
                new Case("Blood Sugar Fasting", "fasting_glucose"),
                new Case("Random Plasma Glucose", "random_glucose"),
                new Case("S. HDL Cholesterol", "hdl"),
                new Case("HDL-CHOLESTEROL", "hdl"),
                new Case("S. LDL Cholesterol", "ldl"),
                new Case("LDL -CHOLESTEROL", "ldl"),
                new Case("TOTAL CHOLESTEROL", "total_cholesterol"),
                new Case("Cholesterol (Total)", "total_cholesterol"),
                new Case("Serum Triglyceride Cholesterol", "triglyceride"),
                new Case("TRIGLYCERIDES", "triglyceride"),
                new Case("Uric Acid", "uric_acid"),
                new Case("BILIRUBIN, TOTAL", "bilirubin_total"),
                new Case("S-BILIRUBIN,Total", "bilirubin_total"));

        for (Case c : cases) {
            assertThat(matcher.canonicalFor(c.printed(), null))
                    .as("'%s' must still resolve to %s", c.printed(), c.expected())
                    .contains(c.expected());
        }
    }

    /** Punctuation is a boundary, not part of a word: a lab writing "S-POTASSIUM" means potassium. */
    @Test
    void punctuationSeparatesWords() {
        MarkerMatcher matcher = shippedMatcher();

        assertThat(matcher.canonicalFor("S.POTASSIUM", null)).contains("potassium");
        assertThat(matcher.canonicalFor("POTASSIUM,SERUM", null)).contains("potassium");
        assertThat(matcher.canonicalFor("(Potassium)", null)).contains("potassium");
    }

    /** A multi-word alias matches only as a contiguous run of whole words. */
    @Test
    void aMultiWordAliasMatchesAsAPhrase() {
        MarkerMatcher matcher = shippedMatcher();

        assertThat(matcher.canonicalFor("Total Leucocyte Count (TLC)", null)).contains("wbc");
        assertThat(matcher.canonicalFor("Leucocyte", null))
                .as("half a phrase is not the phrase").isEmpty();
    }
}

package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A value measured in urine never resolves to a blood marker.
 *
 * <p>"Calcium-Oxalate" under a URINE R/E heading is a crystal seen down a microscope, reported as a count
 * per field or simply "Nil". Serum calcium is an mg/dL concentration with a 8.5–10.5 range and a threshold
 * either side. They share a word, and the matcher had no way to tell them apart, so the crystal resolved to
 * the serum marker and would have been judged against a serum threshold.</p>
 *
 * <p>Exclusions patched that one instance; specimen removes the class for every urine finding that shares a
 * word with a blood test — protein, glucose, bilirubin, urobilinogen, RBC, WBC, pus cells, casts, crystals.
 * A urine dipstick glucose of "+++" is not a plasma glucose, and neither is a urine protein a serum one.</p>
 *
 * <p>Both directions: a urine row must not reach a blood marker, and a blood row must still reach it. A rule
 * that rejected everything would be trivially safe and would take every threshold down with it.</p>
 */
class SpecimenAwareMatchingTest {

    private static MarkerMatcher shippedMatcher() {
        BaymaxProperties properties = new BaymaxProperties();
        properties.setMarkers(shippedMarkers());
        return new MarkerMatcher(properties);
    }

    private static List<BaymaxProperties.Marker> shippedMarkers() {
        var rows = new java.util.TreeMap<Integer, java.util.Map<String, String>>();
        try (var in = SpecimenAwareMatchingTest.class.getResourceAsStream("/markers.properties")) {
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
        var markers = new java.util.ArrayList<BaymaxProperties.Marker>();
        for (var row : rows.values()) {
            var marker = new BaymaxProperties.Marker();
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

    /** lab2's urine report: the row that started this. */
    @Test
    void aUrineCrystalIsNotSerumCalcium() {
        MarkerMatcher matcher = shippedMatcher();

        assertThat(matcher.canonicalFor("Calcium-Oxalate", null, "urine"))
                .as("a crystal counted down a microscope is not an mg/dL serum concentration")
                .isEmpty();
        assertThat(matcher.canonicalFor("Calcium-Oxalate Monohydrate", null, "urine")).isEmpty();
    }

    /**
     * The general rule, on the urine findings that share a word with a blood test. Every one of these would
     * otherwise be judged against a blood reference range.
     */
    @Test
    void noUrineFindingReachesABloodMarker() {
        MarkerMatcher matcher = shippedMatcher();

        for (String name : List.of("Glucose", "Protein", "Bilirubin", "Urobilinogen", "RBC",
                "Pus cells/WBC", "Calcium-Oxalate", "Hyaline Cast", "Specific gravity")) {
            java.util.Optional<String> resolved = matcher.canonicalFor(name, null, "urine");
            assertThat(resolved.isEmpty() || isUrineMarker(resolved.get()))
                    .as("urine '%s' resolved to blood marker '%s'", name, resolved.orElse(""))
                    .isTrue();
        }
    }

    /** Specific gravity IS a urine marker, so it must survive the rule rather than be swept up by it. */
    @Test
    void aUrineMarkerStillResolvesOnAUrineRow() {
        assertThat(shippedMatcher().canonicalFor("Specific gravity", null, "urine"))
                .as("urine specific gravity is a urine test and has a proposed threshold")
                .contains("specific_gravity");
    }

    /** THE OTHER DIRECTION: a blood row still reaches its marker, or every threshold stops firing. */
    @Test
    void aBloodRowStillResolves() {
        MarkerMatcher matcher = shippedMatcher();

        assertThat(matcher.canonicalFor("S-POTASSIUM", null, "blood")).contains("potassium");
        assertThat(matcher.canonicalFor("S-CREATININE", null, "blood")).contains("creatinine");
        assertThat(matcher.canonicalFor("Haemoglobin", null, "blood")).contains("haemoglobin");
        assertThat(matcher.canonicalFor("Blood Sugar Fasting", null, "blood")).contains("fasting_glucose");
        assertThat(matcher.canonicalFor("S. Alkaline Phosphatase", null, "blood")).contains("alp");
    }

    /**
     * A page that does not state a specimen must behave exactly as before. Most reports do not label their
     * sections, and refusing to match without a specimen would take every threshold offline on those pages —
     * a safety rule that silently disables the safety rules.
     */
    @Test
    void anUnknownSpecimenMatchesAsBefore() {
        MarkerMatcher matcher = shippedMatcher();

        assertThat(matcher.canonicalFor("S-POTASSIUM", null, null)).contains("potassium");
        assertThat(matcher.canonicalFor("S-POTASSIUM", null, "")).contains("potassium");
        assertThat(matcher.canonicalFor("Haemoglobin", null, "other")).contains("haemoglobin");
    }

    /** The two-argument form keeps working: it is the whole existing call site. */
    @Test
    void theExistingSignatureIsUnchanged() {
        assertThat(shippedMatcher().canonicalFor("S-POTASSIUM", null)).contains("potassium");
    }

    /** A model that proposes a blood canonical_name on a urine row must not win either. */
    @Test
    void aModelProposedBloodMarkerIsRejectedOnAUrineRow() {
        assertThat(shippedMatcher().canonicalFor("Calcium-Oxalate", "calcium", "urine"))
                .as("the section heading outranks the model's guess")
                .isEmpty();
    }

    private static boolean isUrineMarker(String canonical) {
        return "specific_gravity".equals(canonical);
    }
}

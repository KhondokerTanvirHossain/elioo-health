package com.elioo.baymax.config;

import com.elioo.baymax.extraction.application.service.MarkerMatcher;
import com.elioo.baymax.outbound.domain.MarkerThreshold;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every proposed threshold must be reachable by the code that would apply it.
 *
 * <p>A threshold is joined to a reading through {@code canonical_name}, which {@link MarkerMatcher} derives
 * from the alias table. A marker with a threshold but no alias entry can never be canonicalised, so its
 * threshold can never fire — <b>and a signed-off threshold that cannot fire is worse than no threshold at
 * all, because it looks fixed.</b> When this was first measured, 15 of 19 rows were unreachable, including
 * potassium: the one row that corrects the known under-escalation could have been approved and done nothing.</p>
 *
 * <p>The expectation is <b>derived from the threshold table itself</b>, never from a hand-written list of
 * markers someone remembered. Adding a threshold without an alias fails this test, which is the point: the
 * two tables are kept in step by the build rather than by discipline. Writing the list by hand is the exact
 * shape of the deletion-test failure recorded in CLAUDE.md — six tables asserted from memory, and the
 * seventh, the one that mattered, forgotten.</p>
 */
class EveryThresholdIsReachableTest {

    @Configuration
    @EnableConfigurationProperties(BaymaxProperties.class)
    static class Config {
    }

    /** The real shipped config: thresholds AND the alias table, exactly as the application loads them. */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .withInitializer(context -> {
                    for (String resource : List.of("marker-thresholds.properties", "markers.properties")) {
                        try {
                            new org.springframework.boot.env.PropertiesPropertySourceLoader()
                                    .load(resource, new org.springframework.core.io.ClassPathResource(resource))
                                    .forEach(source ->
                                            context.getEnvironment().getPropertySources().addFirst(source));
                        } catch (java.io.IOException e) {
                            throw new IllegalStateException(resource + " is missing", e);
                        }
                    }
                });
    }

    /** THE DERIVED CHECK: one assertion per threshold row, generated from the table. */
    @Test
    void everyThresholdMarkerCanBeReachedByTheCanonicaliser() {
        runner().run(context -> {
            BaymaxProperties properties = context.getBean(BaymaxProperties.class);
            List<MarkerThreshold> thresholds = properties.getOutbound().getMarkerThresholds();
            Set<String> canonical = new MarkerMatcher(properties).canonicalNames();

            assertThat(thresholds)
                    .as("an empty threshold table would make this pass vacuously")
                    .isNotEmpty();

            Set<String> unreachable = thresholds.stream()
                    .map(MarkerThreshold::canonicalName)
                    .filter(name -> !canonical.contains(name))
                    .collect(Collectors.toCollection(java.util.TreeSet::new));

            assertThat(unreachable)
                    .as("these markers have a threshold but no alias entry, so the threshold can never fire; "
                            + "add them to markers.properties")
                    .isEmpty();
        });
    }

    /**
     * The other direction of the same rule: an alias entry must actually resolve its own canonical name.
     * A marker declared with no usable alias would satisfy the check above and still never match a page.
     */
    @Test
    void everyThresholdMarkerResolvesFromItsOwnCanonicalName() {
        runner().run(context -> {
            BaymaxProperties properties = context.getBean(BaymaxProperties.class);
            MarkerMatcher matcher = new MarkerMatcher(properties);

            assertThat(properties.getOutbound().getMarkerThresholds())
                    .allSatisfy(t -> assertThat(matcher.canonicalFor(t.canonicalName(), null))
                            .as("threshold marker %s does not resolve to itself", t.canonicalName())
                            .contains(t.canonicalName()));
        });
    }

    /**
     * No alias may be a word so short or so common that the substring fallback drags unrelated tests onto a
     * marker. Every entry in this list was a REAL mis-mapping found by scripts/alias_coverage.py:
     *
     * <ul>
     *   <li>"cl-" normalised to "cl" and matched "atherosCLerosis index"</li>
     *   <li>"anc" matched "appeARANCe", a urine finding</li>
     *   <li>"bilirubin, total" was split on its comma into two aliases, one of them the bare word "total",
     *       which matched "RBC total count" and "Platelets total count"</li>
     * </ul>
     *
     * <p>The last is the config trap worth naming: Spring splits an alias list on commas, so a comma INSIDE
     * an alias silently creates a bare-word alias nobody wrote. Normalisation already turns the page's
     * "BILIRUBIN, TOTAL" into "bilirubin total", so the comma was never needed.</p>
     */
    @Test
    void noAliasIsABareCommonWord() {
        runner().run(context -> {
            var markers = context.getBean(BaymaxProperties.class).getMarkers();

            assertThat(markers).isNotEmpty();
            for (var marker : markers) {
                for (String alias : marker.getAliases()) {
                    assertThat(MarkerMatcher.normalise(alias))
                            .as("alias '%s' of marker %s is a bare common word — it will match inside "
                                    + "unrelated test names. A comma INSIDE an alias creates one silently, "
                                    + "because Spring splits the list on commas.",
                                    alias, marker.getCanonical())
                            .isNotIn("total", "count", "serum", "blood", "urine", "level", "test",
                                    "cell", "cells", "index", "value", "plasma", "random");
                }
            }
        });
    }

    /**
     * The spellings batch 2 actually printed. These are the names on real Bangladeshi and regional lab
     * reports, and each one is a way the potassium row could silently fail to fire.
     */
    @Test
    void theSpellingsSeenOnRealReportsCanonicaliseCorrectly() {
        runner().run(context -> {
            MarkerMatcher matcher = new MarkerMatcher(context.getBean(BaymaxProperties.class));

            assertThat(matcher.canonicalFor("S-POTASSIUM", null)).contains("potassium");
            assertThat(matcher.canonicalFor("Potassium (K+)", null)).contains("potassium");
            assertThat(matcher.canonicalFor("S-SODIUM", null)).contains("sodium");
            assertThat(matcher.canonicalFor("Sodium (Na+)", null)).contains("sodium");
            assertThat(matcher.canonicalFor("Haemoglobin", null)).contains("haemoglobin");
            assertThat(matcher.canonicalFor("Hemoglobin", null)).contains("haemoglobin");
            assertThat(matcher.canonicalFor("Platelets total count", null)).contains("platelet");
            assertThat(matcher.canonicalFor("Total WBC Count", null)).contains("wbc");
            assertThat(matcher.canonicalFor("Total Leucocyte Count", null)).contains("wbc");
            assertThat(matcher.canonicalFor("S.ALT ( SGPT )", null)).contains("alt");
            assertThat(matcher.canonicalFor("SGPT/ALT", null)).contains("alt");
            assertThat(matcher.canonicalFor("S.AST (SGOT)", null)).contains("ast");
            assertThat(matcher.canonicalFor("S-ALKALINE PHOSPHATASE", null)).contains("alp");
            assertThat(matcher.canonicalFor("S. Alkaline Phosphatase", null)).contains("alp");
            assertThat(matcher.canonicalFor("S-UREA", null)).contains("urea");
            assertThat(matcher.canonicalFor("Blood Urea", null)).contains("urea");
            assertThat(matcher.canonicalFor("ESR", null)).contains("esr");
            assertThat(matcher.canonicalFor("S-CREATININE", null)).contains("creatinine");
            assertThat(matcher.canonicalFor("eGFR-(CKD-EPI)", null)).contains("egfr");
            assertThat(matcher.canonicalFor("Specific gravity", null)).contains("specific_gravity");
            assertThat(matcher.canonicalFor("Blood Sugar Fasting", null)).contains("fasting_glucose");
            assertThat(matcher.canonicalFor("Random Plasma Glucose", null)).contains("random_glucose");
            assertThat(matcher.canonicalFor("S. HDL Cholesterol", null)).contains("hdl");
            assertThat(matcher.canonicalFor("HDL-CHOLESTEROL", null)).contains("hdl");
            assertThat(matcher.canonicalFor("S. LDL Cholesterol", null)).contains("ldl");
            assertThat(matcher.canonicalFor("TOTAL CHOLESTEROL", null)).contains("total_cholesterol");
            assertThat(matcher.canonicalFor("Serum Triglyceride Cholesterol", null)).contains("triglyceride");
            assertThat(matcher.canonicalFor("BILIRUBIN, TOTAL", null)).contains("bilirubin_total");
        });
    }

    /**
     * The dangerous direction. A canonicaliser generous enough to catch every spelling is generous enough to
     * map the wrong test onto a threshold — and a threshold applied to the wrong marker is a wrong urgency
     * for a real family.
     */
    @Test
    void aDifferentTestDoesNotCanonicaliseOntoAThresholdMarker() {
        runner().run(context -> {
            MarkerMatcher matcher = new MarkerMatcher(context.getBean(BaymaxProperties.class));

            assertThat(matcher.canonicalFor("Non-HDL - CHOLESTEROL", null))
                    .as("non-HDL cholesterol is a different quantity from HDL, and HDL is direction-inverted")
                    .isNotEqualTo(java.util.Optional.of("hdl"));
            assertThat(matcher.canonicalFor("Bilirubin conjugated", null))
                    .as("conjugated bilirubin is not total bilirubin")
                    .isNotEqualTo(java.util.Optional.of("bilirubin_total"));
            assertThat(matcher.canonicalFor("Corresponding Urine Sugar", null))
                    .as("a urine sugar is not a fasting plasma glucose")
                    .isNotEqualTo(java.util.Optional.of("fasting_glucose"));
            assertThat(matcher.canonicalFor("Mean Platelet Volume", null))
                    .as("MPV is a red-cell index, not a platelet count — the platelet threshold must not fire")
                    .isNotEqualTo(java.util.Optional.of("platelet"));
            assertThat(matcher.canonicalFor("Atherosclerosis Index", null))
                    .as("a computed ratio is not a marker with a threshold")
                    .isEmpty();

            // Found by the coverage report, not by imagination: urine microscopy findings whose names end
            // in "Cast" contain the letters of AST, and a cast count judged against a liver-enzyme
            // threshold is a wrong urgency on a urine report.
            assertThat(matcher.canonicalFor("Granular Cast", null))
                    .as("a urinary cast is not the liver enzyme AST").isEmpty();
            assertThat(matcher.canonicalFor("Hyaline Cast", null))
                    .as("a urinary cast is not the liver enzyme AST").isEmpty();
            assertThat(matcher.canonicalFor("RBC Cast", null))
                    .as("a urinary cast is not the liver enzyme AST").isEmpty();
            assertThat(matcher.canonicalFor("WBC Cast", null))
                    .as("a urinary cast is neither AST nor a white cell count").isEmpty();
            // A urine crystal is not serum calcium.
            assertThat(matcher.canonicalFor("Calcium-Oxalate", null)).isEmpty();
            assertThat(matcher.canonicalFor("Calcium-Oxalate Monohydrate", null)).isEmpty();
            // A urine dipstick protein is not a serum marker either.
            assertThat(matcher.canonicalFor("Pus cells/WBC", null))
                    .as("a urine pus-cell count is not a blood white cell count").isEmpty();
            // Also surfaced by the coverage report: "appeARANCe" contains "anc".
            assertThat(matcher.canonicalFor("Appearance", null))
                    .as("a urine appearance is not an absolute neutrophil count").isEmpty();
            assertThat(matcher.canonicalFor("RBC total count", null))
                    .as("a red cell count is not bilirubin and not a platelet count")
                    .isNotEqualTo(java.util.Optional.of("bilirubin_total"));
        });
    }
}

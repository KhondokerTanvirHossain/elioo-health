package com.elioo.baymax.config;

import com.elioo.baymax.outbound.domain.MarkerThreshold;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped threshold table: it must load, and every row in it must be inert.
 *
 * <p>Two distinct failures are guarded here, and they fail in opposite directions.</p>
 *
 * <p><b>Silently not loading.</b> A misspelled key, a missing {@code spring.config.import}, or a binding
 * error would leave the table empty, and every test about inertness would still pass — vacuously. The doctor
 * would then review a table that the running system does not have. So the count is asserted to be non-empty
 * and specific markers are looked for by name.</p>
 *
 * <p><b>Silently going live.</b> A row that ships as {@code active} would change what families are told
 * before anyone approved it. Nothing in the file may be active, and that is asserted row by row so the
 * failure message names the offending marker.</p>
 */
class ShippedThresholdsAreAllProposedTest {

    @Configuration
    @EnableConfigurationProperties(BaymaxProperties.class)
    static class Config {
    }

    /**
     * The table is loaded from the classpath resource the application ships, not retyped here: a test that
     * restated the numbers would pass while the real file was empty, misspelled, or never imported — which
     * is exactly what happened on the first run of this test.
     */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .withInitializer(context -> {
                    var resource = new org.springframework.core.io.ClassPathResource("marker-thresholds.properties");
                    try {
                        var loaded = new org.springframework.boot.env.PropertiesPropertySourceLoader()
                                .load("marker-thresholds", resource);
                        loaded.forEach(source -> context.getEnvironment().getPropertySources().addFirst(source));
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException("marker-thresholds.properties is missing", e);
                    }
                });
    }

    @Test
    void theShippedTableLoadsAndIsNotEmpty() {
        runner().run(context -> {
            var thresholds = context.getBean(BaymaxProperties.class).getOutbound().getMarkerThresholds();

            assertThat(thresholds)
                    .as("an empty table would make every inertness assertion pass vacuously")
                    .isNotEmpty();
            assertThat(thresholds).extracting(MarkerThreshold::canonicalName)
                    .as("the markers batch 2 found wrong in both directions must be in the table")
                    .contains("potassium", "esr", "hdl", "creatinine");
        });
    }

    /** THE ONE THAT MATTERS: nothing ships signed off. */
    @Test
    void everyShippedRowIsProposedAndThereforeInert() {
        runner().run(context -> {
            var thresholds = context.getBean(BaymaxProperties.class).getOutbound().getMarkerThresholds();

            assertThat(thresholds)
                    .as("no threshold may be active until a clinician signs it off")
                    .allSatisfy(t -> assertThat(t.status())
                            .as("marker %s (%s) is %s", t.canonicalName(), t.unit(), t.status())
                            .isEqualTo(MarkerThreshold.Status.PROPOSED));

            assertThat(thresholds)
                    .as("and therefore none of them is live")
                    .noneMatch(MarkerThreshold::isLive);
        });
    }

    /**
     * Creatinine is listed twice, once per unit, because 65 µmol/L is normal and 65 mg/dL is not survivable.
     * If a future edit ever "tidies" these into one row, this fails.
     */
    @Test
    void creatinineIsStatedSeparatelyInEachUnitRatherThanConverted() {
        runner().run(context -> {
            var creatinine = context.getBean(BaymaxProperties.class).getOutbound().getMarkerThresholds()
                    .stream().filter(t -> "creatinine".equals(t.canonicalName())).toList();

            assertThat(creatinine).as("one row per unit, never a conversion").hasSize(2);
            assertThat(creatinine).extracting(MarkerThreshold::unit)
                    .containsExactlyInAnyOrder("mg/dL", "umol/L");
            assertThat(creatinine)
                    .as("and the two must carry different numbers, or one of them is wrong")
                    .extracting(MarkerThreshold::nowHigh)
                    .doesNotHaveDuplicates();
        });
    }

    /** HDL must carry its direction, or a healthy HDL escalates the moment the table goes live. */
    @Test
    void hdlIsRecordedAsHighIsGood() {
        runner().run(context -> assertThat(context.getBean(BaymaxProperties.class).getOutbound()
                .getMarkerThresholds())
                .filteredOn(t -> "hdl".equals(t.canonicalName()))
                .singleElement()
                .satisfies(t -> assertThat(t.direction()).isEqualTo(MarkerThreshold.Direction.HIGH_IS_GOOD)));
    }

    /** Every row carries a citation: the doctor reviews the source as well as the number. */
    @Test
    void everyRowCarriesASource() {
        runner().run(context -> assertThat(context.getBean(BaymaxProperties.class).getOutbound()
                .getMarkerThresholds())
                .allSatisfy(t -> assertThat(t.source())
                        .as("marker %s has no source to review", t.canonicalName())
                        .isNotBlank()));
    }
}

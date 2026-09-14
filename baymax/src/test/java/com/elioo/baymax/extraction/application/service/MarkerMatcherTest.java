package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkerMatcherTest {

    private static MarkerMatcher matcher() {
        BaymaxProperties props = new BaymaxProperties();
        props.setMarkers(List.of(
                marker("hba1c", "hba1c", "hb a1c", "glycated haemoglobin", "a1c"),
                marker("fasting_glucose", "fasting glucose", "fbs", "fasting blood sugar"),
                marker("creatinine", "creatinine", "s. creatinine", "serum creatinine"),
                marker("ldl", "ldl", "ldl cholesterol")));
        return new MarkerMatcher(props);
    }

    private static BaymaxProperties.Marker marker(String canonical, String... aliases) {
        BaymaxProperties.Marker m = new BaymaxProperties.Marker();
        m.setCanonical(canonical);
        m.setAliases(List.of(aliases));
        return m;
    }

    @Test
    void matchesTheCanonicalNameItself() {
        assertThat(matcher().canonicalFor("HbA1c", null)).contains("hba1c");
    }

    @Test
    void matchesLocalSpellingsAndPunctuation() {
        MarkerMatcher m = matcher();
        assertThat(m.canonicalFor("S. Creatinine", null)).contains("creatinine");
        assertThat(m.canonicalFor("serum  creatinine", null)).contains("creatinine");
        assertThat(m.canonicalFor("Hb A1C", null)).contains("hba1c");
        assertThat(m.canonicalFor("Fasting Blood Sugar (FBS)", null)).contains("fasting_glucose");
    }

    @Test
    void prefersWhatTheModelProposedWhenItIsAKnownMarker() {
        assertThat(matcher().canonicalFor("Glycohaemoglobin", "hba1c")).contains("hba1c");
    }

    @Test
    void ignoresAModelProposalThatIsNotAConfiguredMarker() {
        // the model invents a canonical name: fall back to matching the printed name, else nothing
        assertThat(matcher().canonicalFor("S. Creatinine", "kidney_marker")).contains("creatinine");
        assertThat(matcher().canonicalFor("Bilirubin", "liver_marker")).isEmpty();
    }

    @Test
    void unknownValuesStayUnmapped() {
        MarkerMatcher m = matcher();
        assertThat(m.canonicalFor("Haemoglobin", null)).isEmpty();
        assertThat(m.canonicalFor(null, null)).isEmpty();
        assertThat(m.canonicalFor("", "")).isEmpty();
    }

    @Test
    void theLongestMatchingAliasWins() {
        // "ldl cholesterol" must not be beaten by the shorter "ldl" hiding inside it
        assertThat(matcher().canonicalFor("LDL Cholesterol, direct", null)).contains("ldl");
    }

    @Test
    void normaliseFoldsCaseAndPunctuation() {
        assertThat(MarkerMatcher.normalise("S. Creatinine  (serum)")).isEqualTo("s creatinine serum");
        assertThat(MarkerMatcher.normalise("HbA1c %")).isEqualTo("hba1c %");
        assertThat(MarkerMatcher.normalise(null)).isEmpty();
    }
}

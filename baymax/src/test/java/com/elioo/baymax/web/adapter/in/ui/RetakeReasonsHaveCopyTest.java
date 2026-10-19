package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.extraction.domain.RetakeReason;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F5: every reason a retake can store has its line, in both languages — derived from the enum, so a reason added later
 * without copy fails here rather than as a "no UI copy" error on a family's page.
 */
class RetakeReasonsHaveCopyTest {

    private final UiCopy copy = new UiCopy();

    @Test
    void everyRetakeReasonHasItsLineInBothLanguages() {
        List<RetakeReason> reasons = Arrays.asList(RetakeReason.values());
        assertThat(reasons).as("the reasons, from the enum").hasSizeGreaterThanOrEqualTo(5);
        for (Lang lang : Lang.values()) {
            Set<String> keys = copy.keys(lang);
            assertThat(keys).as(lang + ": the retake head").contains("retake.head");
            for (RetakeReason r : reasons) {
                String key = "retake.reason." + r.prefix();
                assertThat(keys).as(lang + ": " + key).contains(key);
                assertThat(copy.raw(lang, key)).as(lang + ": " + key).isNotBlank();
            }
        }
    }

    /** A section-specific line is optional, but one that exists in one language exists in the other, for a known reason. */
    @Test
    void aSectionLineExistsInBothLanguagesAndNamesAReasonThatTakesASection() {
        for (Lang lang : Lang.values()) {
            Lang other = lang == Lang.BN ? Lang.EN : Lang.BN;
            List<String> sectioned = copy.keys(lang).stream().filter(k -> k.startsWith("retake.reason.") && k.chars().filter(c -> c == '.').count() == 3).toList();
            assertThat(sectioned).as(lang + ": the section lines the spec names (values, medicines)").hasSizeGreaterThanOrEqualTo(2);
            for (String key : sectioned) {
                assertThat(copy.keys(other)).as(other + ": " + key).contains(key);
                String prefix = key.split("\\.")[2];
                RetakeReason r = RetakeReason.ofCode(prefix).orElseThrow(() -> new AssertionError(key + " names no RetakeReason"));
                String section = key.split("\\.")[3];
                assertThat(r.code(section)).as(key + " is a code the reason can store").isEqualTo(prefix + ":" + section);
            }
        }
    }
}

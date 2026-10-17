package com.elioo.baymax.outbound.application.service;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * messages_en.properties says "same keys, same placeholders"; nothing asserted it (UiCopyTest covers ui_* only).
 * Phase 0 adds twenty-odd keys to both files, so the claim becomes a test before the keys arrive.
 */
class MessagesBundleTest {

    private static Properties load(String path) throws Exception {
        Properties p = new Properties();
        try (var in = MessagesBundleTest.class.getClassLoader().getResourceAsStream(path)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void bothLanguagesCarryExactlyTheSameKeys() throws Exception {
        Properties bn = load("baymax/messages_bn.properties");
        Properties en = load("baymax/messages_en.properties");
        assertThat(bn.stringPropertyNames()).as("vacuous if the bundle did not load").hasSizeGreaterThanOrEqualTo(34);
        assertThat(en.stringPropertyNames()).containsExactlyInAnyOrderElementsOf(bn.stringPropertyNames());
    }

    @Test
    void placeholdersMatchAcrossLanguages() throws Exception {
        Properties bn = load("baymax/messages_bn.properties");
        Properties en = load("baymax/messages_en.properties");
        Pattern hole = Pattern.compile("\\{[a-z_]+}");
        for (String key : bn.stringPropertyNames()) {
            assertThat(holes(hole, en.getProperty(key))).as(key).isEqualTo(holes(hole, bn.getProperty(key)));
        }
    }

    /**
     * The approved red-flag sentence exists once and is reused by every Phase 0 fallback. explanation.now must
     * still carry it verbatim, so an edit to either copy without the other fails here instead of drifting.
     */
    @Test
    void theRedFlagLineIsTheApprovedSentenceInsideTheNowExplanation() {
        Copy copy = new Copy();
        assertThat(copy.bn("explanation.now", Map.of())).contains(copy.bn("safety.red_flag_line", Map.of()));
        assertThat(copy.en("explanation.now", Map.of())).contains(copy.en("safety.red_flag_line", Map.of()));
    }

    private static List<String> holes(Pattern p, String s) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(s == null ? "" : s);
        while (m.find()) {
            out.add(m.group());
        }
        Collections.sort(out);
        return out;
    }
}

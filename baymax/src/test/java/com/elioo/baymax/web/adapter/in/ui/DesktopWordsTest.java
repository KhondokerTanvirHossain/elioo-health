package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The desktop's Bangla is the approved prototype's own text, character for character (plan
 * 2026-11-07-medioo-desktop-parity, Global Constraints). Every {@code desk.*} value in {@code ui-shell_bn.properties}
 * is matched against {@code docs/design/2026-10-21-approved/desktop.html} itself, never against a list typed here, so a
 * word retyped by hand — a য় written precomposed where the design writes য + nukta, a changed hyphen — fails.
 *
 * <p>A {@code {n}} placeholder stands for whatever the prototype puts there: a template expression
 * ({@code ${gen(st.patient)}}) or a short literal ({@code ২}). The only words not in the prototype are the ones the PO
 * worded in ruling P19, which the plan records; each is held to that record instead.
 */
class DesktopWordsTest {

    static final Path DESIGN = Path.of("../docs/design/2026-10-21-approved/desktop.html");
    static final Path PLAN = Path.of("../docs/superpowers/plans/2026-11-07-medioo-desktop-parity.md");

    /** Keys whose Bangla the PO gave in ruling P19 rather than the prototype ("সব দেখুন" opens the side panel). */
    static final Set<String> WORDED_IN_P19 = Set.of("desk.panel.open");

    /** What a {@code {n}} stands for in the prototype: a {@code ${...}} expression, or a short literal on one line. */
    private static final String PLACEHOLDER = "(?:\\$\\{[^}]*\\}|[^<>\"`\\n]{1,40}?)";

    private final UiCopy copy = new UiCopy();

    @Test
    void everyDesktopWordIsThePrototypesOwnText() throws IOException {
        String design = Files.readString(DESIGN, StandardCharsets.UTF_8);
        Map<String, String> notInDesign = new TreeMap<>();
        for (Map.Entry<String, String> word : deskWords(Lang.BN).entrySet()) {
            if (WORDED_IN_P19.contains(word.getKey())) {
                continue;
            }
            if (!asPrototypeText(word.getValue()).matcher(design).find()) {
                notInDesign.put(word.getKey(), word.getValue());
            }
        }
        assertThat(notInDesign).as("desk.* Bangla that desktop.html does not contain character for character").isEmpty();
    }

    @Test
    void theP19WordsAreTheRulingsOwn() throws IOException {
        String plan = Files.readString(PLAN, StandardCharsets.UTF_8);
        String ruling = plan.substring(plan.indexOf("## P19"), plan.indexOf("## Phase 1"));
        Map<String, String> bn = deskWords(Lang.BN);
        for (String key : WORDED_IN_P19) {
            assertThat(bn).as("an exemption for a key that does not exist").containsKey(key);
            assertThat(ruling).as(key + " is the PO's P19 wording").contains("\"" + bn.get(key) + "\"");
        }
    }

    @Test
    void theCheckIsNotVacuous() {
        Map<String, String> bn = deskWords(Lang.BN);
        assertThat(bn).as("desk.* words in ui-shell_bn (an empty set would pass the check above vacuously)")
                .hasSizeGreaterThanOrEqualTo(30);
        assertThat(deskWords(Lang.EN).keySet()).as("bn and en hold the same desk.* keys").isEqualTo(bn.keySet());
        Set<String> checked = new TreeSet<>(bn.keySet());
        checked.removeAll(WORDED_IN_P19);
        assertThat(checked).as("the P19 exemption may not swallow the prototype's words").hasSizeGreaterThan(WORDED_IN_P19.size());
    }

    private Map<String, String> deskWords(Lang lang) {
        return copy.keys(lang).stream()
                .filter(k -> k.startsWith("desk."))
                .collect(Collectors.toMap(k -> k, k -> copy.raw(lang, k), (a, b) -> a, TreeMap::new));
    }

    /** The value as a pattern over the prototype: its text quoted exactly, each {@code {n}} as {@link #PLACEHOLDER}. */
    static Pattern asPrototypeText(String value) {
        String[] parts = value.split("\\{\\d+\\}", -1);
        return Pattern.compile(java.util.Arrays.stream(parts).map(Pattern::quote).collect(Collectors.joining(PLACEHOLDER)));
    }
}

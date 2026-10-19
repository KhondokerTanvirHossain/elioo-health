package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.extraction.domain.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status chip (PageChrome.status, on every কাগজপত্র row) is styled by the {@code .tag} rules and nothing else. B5's
 * retake card was first written as an unscoped {@code .retake}, which also matched {@code <span class="tag retake">}:
 * every NEEDS_RETAKE and FAILED row on the papers tab lost its margin and gained the card's border and vertical margins.
 *
 * <p>Derived, not listed: the chips are rendered for every document status, and every rule of the served stylesheet
 * whose subject could select one of them must name {@code .tag}. A card class added later that collides fails here.
 */
class StatusChipCssTest {

    private static final WebUiTestKit KIT = new WebUiTestKit();
    /** What the browser receives, never a file on disk. */
    private static final String CSS = KIT.servedText("/assets/baymax.css");
    private static final Pattern RULE = Pattern.compile("([^{}]+)\\{[^{}]*}");
    private static final Pattern CHIP = Pattern.compile("<(\\w+) class=\"([^\"]+)\"");

    @Test
    void onlyTheTagRulesStyleTheStatusChip() {
        PageChrome chrome = new PageChrome(KIT.copy, KIT.sessions);
        Set<Chip> chips = new LinkedHashSet<>();
        for (Document.Status s : Document.Status.values()) {
            Matcher m = CHIP.matcher(chrome.status(Lang.BN, s.name()));
            assertThat(m.find()).as("the chip for " + s).isTrue();
            chips.add(new Chip(m.group(1), Set.of(m.group(2).split(" "))));
        }
        assertThat(chips).as("a retake chip is drawn, so the sweep covers it").contains(new Chip("span", Set.of("tag", "retake")));

        List<String> matching = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        String css = CSS.replaceAll("(?s)/\\*.*?\\*/", "");
        Matcher r = RULE.matcher(css);
        while (r.find()) {
            for (String selector : r.group(1).split(",")) {
                String sel = selector.trim();
                String[] parts = sel.split("\\s*[ >+~]\\s*");
                String subject = parts[parts.length - 1];
                for (Chip chip : chips) {
                    if (chip.selectedBy(subject)) {
                        matching.add(sel);
                        if (!classesOf(subject).contains("tag")) {
                            offenders.add(sel + " selects <" + chip.element + " class=\"" + String.join(" ", chip.classes) + "\">");
                        }
                    }
                }
            }
        }
        assertThat(matching).as("the sweep finds the chip's own rules, so an empty match cannot pass").contains(".tag.retake");
        assertThat(offenders).as("a rule that is not a .tag rule styles the status chip").isEmpty();
    }

    private static Set<String> classesOf(String compound) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\.([A-Za-z0-9_-]+)").matcher(compound);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private record Chip(String element, Set<String> classes) {
        /**
         * Whether a class selector could select this chip: every class it names is on the chip, and its type (if any)
         * is the chip's. Pseudo-classes are ignored, so the test errs towards a match. Type-only rules ({@code *},
         * {@code span}) are the global base styles, not a collision, and are not counted.
         */
        boolean selectedBy(String compound) {
            String c = compound.replaceAll("::?[A-Za-z-]+(\\([^)]*\\))?", "");
            Set<String> wanted = classesOf(c);
            if (wanted.isEmpty() || c.contains("#") || c.contains("[")) {
                return false;
            }
            Matcher type = Pattern.compile("^([A-Za-z][A-Za-z0-9]*)").matcher(c);
            return (!type.find() || type.group(1).equalsIgnoreCase(element)) && classes.containsAll(wanted);
        }
    }

    @Test
    void theSelectorParserSeesTheSubjectOfACompoundSelector() {
        Chip chip = new Chip("span", Set.of("tag", "retake"));
        assertThat(Arrays.asList(chip.selectedBy(".retake"), chip.selectedBy("span.tag"), chip.selectedBy(".tag.retake:hover"),
                chip.selectedBy("section.retake"), chip.selectedBy(".retake-card"), chip.selectedBy("#retake"), chip.selectedBy("p"),
                chip.selectedBy(".tag.now")))
                .containsExactly(true, true, true, false, false, false, false, false);
    }
}

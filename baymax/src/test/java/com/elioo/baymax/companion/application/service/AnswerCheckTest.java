package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.domain.ChatContext;
import com.elioo.baymax.outbound.domain.Urgency;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan CHAT-api Task 3: the content checks on the model's own text (DR-50 envelope 2) and the appendix code adds after it
 * (envelope 3). Numbers are compared as numbers, a decimal being one number; a stored prescription line written as its
 * {@code {{rx:n}}} token is exempt from every content check; the log form carries codes only.
 */
class AnswerCheckTest {

    private static ChatContext numbers(String... canonical) {
        return new ChatContext(List.of(), Set.of(canonical), List.of(), List.of(), false);
    }

    /**
     * Review of #166, finding 5: the lines a text quotes, each once, in the order first quoted, with the paper and the crop
     * of that position; a reference past the last line quotes nothing, and a context with no crop known quotes nothing.
     */
    @Test
    void citedIsEachQuotedLineOnceWithItsCrop() {
        java.util.UUID rx = java.util.UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100005");
        ChatContext ctx = new ChatContext(List.of(), Set.of(), List.of("Tab. A · 1+0+0", "Tab. B · 0+0+1"), List.of(rx), false, List.of(),
                Set.of(), Set.of(), rx, List.of("crop-a.jpg", "crop-b.jpg"));

        assertThat(AnswerCheck.cited("{{rx:2}} আর {{rx:1}}, আবার {{rx:2}}; {{rx:3}}", ctx)).containsExactly(
                new com.elioo.baymax.companion.domain.CitedMedicine(rx, "crop-b.jpg"),
                new com.elioo.baymax.companion.domain.CitedMedicine(rx, "crop-a.jpg"));
        assertThat(AnswerCheck.cited("Ma-র ক্রিয়েটিনিন 1.8।", ctx)).isEmpty();
        assertThat(AnswerCheck.cited("{{rx:1}}", new ChatContext(List.of(), Set.of(), List.of("Tab. A"), List.of(rx), false))).isEmpty();
    }

    @Test
    void aDecimalIsOneNumberNotTwo() {
        ChatContext ctx = numbers("1.8", "0.7", "1.3");
        assertThat(AnswerCheck.violations("Ma-র ক্রিয়েটিনিন 1.8", ctx)).isEmpty();
        assertThat(AnswerCheck.violations("Ma-র ক্রিয়েটিনিন 8", ctx)).isEqualTo(List.of("number_not_on_paper:8"));
    }

    @Test
    void banglaDigitsAreTheSameNumber() {
        assertThat(AnswerCheck.violations("Ma-র ক্রিয়েটিনিন ১.৮", numbers("1.8"))).isEmpty();
    }

    @Test
    void trailingZerosAreTheSameNumber() {
        assertThat(AnswerCheck.violations("Ma-র ক্রিয়েটিনিন 1.80", numbers("1.8"))).isEmpty();
    }

    @Test
    void aDateOnThePaperIsAllowed() {
        java.time.LocalDate paper = java.time.LocalDate.parse("2026-09-12");
        ChatContext ctx = new ChatContext(List.of(), AnswerCheck.yearOf(paper), List.of(), List.of(), false, List.of(), Set.of(paper), Set.of());
        assertThat(AnswerCheck.violations("কাগজটা ১২ সেপ্টেম্বর ২০২৬ তারিখের", ctx)).isEmpty();
        assertThat(AnswerCheck.violations("কাগজটা ১৩ সেপ্টেম্বর ২০২৬ তারিখের", ctx)).isEqualTo(List.of("number_not_on_paper:13"));
        assertThat(AnswerCheck.violations("Ma-র ক্রিয়েটিনিন ১২", ctx)).as("the day alone is not a value")
                .isEqualTo(List.of("number_not_on_paper:12"));
    }

    @Test
    void numberWordsAreRefusedButOneDoctorIsNot() {
        assertThat(AnswerCheck.violations("দেড় গুণ বেশি", ChatContext.empty())).contains("number_word:দেড়");
        assertThat(AnswerCheck.violations("একজন ডাক্তার দেখান।", ChatContext.empty())).isEmpty();
        assertThat(AnswerCheck.violations("এটা চিকিৎসা পরামর্শ নয়।", ChatContext.empty()))
                .as("নয় is also the Bangla \"is not\"; it is not a number word here").isEmpty();
    }

    @Test
    void aVerbatimLineIsExemptFromEveryContentCheck() {
        ChatContext ctx = new ChatContext(List.of(), Set.of(), List.of("Tab. Demoprol 5 mg · 1+0+0 · খাবারের পরে খাবেন"), List.of(), false);
        assertThat(AnswerCheck.violations("শেষ প্রেসক্রিপশনে লেখা: {{rx:1}}", ctx)).isEmpty();
        assertThat(AnswerCheck.violations("শেষ প্রেসক্রিপশনে লেখা: Tab. Demoprol 5 mg · 1+0+0 · খাবারের পরে খাবেন", ctx))
                .as("the same line retyped by the model is the model's text")
                .contains("number_not_on_paper:5", "dose_change:amount", "dose_change:খাবেন");
    }

    @Test
    void anUnknownReferenceIsAViolation() {
        ChatContext ctx = new ChatContext(List.of(), Set.of(), List.of("Tab. Demoprol 5 mg"), List.of(), false);
        assertThat(AnswerCheck.violations("{{rx:9}}", ctx)).contains("unresolved_reference:{{rx:9}}");
        assertThat(AnswerCheck.violations("{{rx:1}} and {{name}}", ctx)).anyMatch(v -> v.startsWith("unresolved_reference:"));
    }

    @Test
    void anEmptyBodyIsAViolation() {
        assertThat(AnswerCheck.violations("  ", ChatContext.empty())).isEqualTo(List.of("empty_body"));
    }

    @Test
    void reassuranceIsRefused() {
        assertThat(AnswerCheck.violations("চিন্তার কিছু নেই", ChatContext.empty())).contains("reassurance:চিন্তার কিছু নেই");
        assertThat(AnswerCheck.violations("everything is fine", ChatContext.empty())).contains("reassurance:fine");
    }

    @Test
    void theLengthIsTheWholeText() {
        assertThat(AnswerCheck.length("x".repeat(1200), 1200)).isEmpty();
        assertThat(AnswerCheck.length("x".repeat(1201), 1200)).isEqualTo(List.of("too_long:1201"));
    }

    @Test
    void theLogFormCarriesNoPatientText() {
        assertThat(AnswerCheck.forLog(List.of("number_not_on_paper:8", "dose_change:অর্ধেক", "too_long:1300")))
                .isEqualTo(List.of("number_not_on_paper", "dose_change", "too_long:1300"));
    }

    /** Derived from the bundles: every fixed চ্যাট body Medioo may show in place of a model's passes the same checks. */
    @Test
    void everyFixedChatBodyPassesTheContentChecks() throws Exception {
        int seen = 0;
        List<String> failing = new ArrayList<>();
        for (String lang : List.of("bn", "en")) {
            Properties p = new Properties();
            try (InputStream in = AnswerCheckTest.class.getResourceAsStream("/baymax/ui-chat_" + lang + ".properties")) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            int here = 0;
            for (String key : p.stringPropertyNames()) {
                if (key.startsWith("chat.ask.")) {
                    here++;
                    List<String> v = AnswerCheck.violations(p.getProperty(key), ChatContext.empty());
                    if (!v.isEmpty()) {
                        failing.add(lang + " " + key + ": " + v);
                    }
                }
            }
            assertThat(here).as("chat.ask.* keys in ui-chat_" + lang).isGreaterThanOrEqualTo(1);
            seen += here;
        }
        assertThat(seen).isGreaterThanOrEqualTo(2);
        assertThat(failing).isEmpty();
    }

    @Test
    void theAppendedKeysAreExactlyTheAppendix() {
        Set<String> read = new HashSet<>();
        Function<String, String> line = key -> {
            read.add(key);
            return "<" + key + ">";
        };
        for (boolean dose : List.of(true, false)) {
            for (Optional<Urgency> open : List.of(Optional.<Urgency>empty(), Optional.of(Urgency.NOW), Optional.of(Urgency.THIS_WEEK),
                    Optional.of(Urgency.ROUTINE))) {
                ChatAppendix.append("body", dose, open, line);
            }
        }
        ChatAppendix.emergency(line);
        assertThat(read).isEqualTo(Set.copyOf(ChatAppendix.KEYS));
    }

    @Test
    void theAppendixIsInTheAgreedOrder() {
        Function<String, String> line = key -> "<" + key + ">";
        assertThat(ChatAppendix.append("body", true, Optional.of(Urgency.NOW), line))
                .isEqualTo("body\n\n<chat.dose.ask_doctor>\n<urgency.now>\n<disclaimer>\n<safety.red_flag_line>");
        assertThat(ChatAppendix.append("body", false, Optional.empty(), line)).isEqualTo("body\n\n<disclaimer>\n<safety.red_flag_line>");
        assertThat(ChatAppendix.emergency(line))
                .isEqualTo("<safety.red_flag_line>\n<now.notdoctor>\n\n<disclaimer>\n<safety.red_flag_line>");
    }
}

package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.application.port.out.ChatCopyPort;
import com.elioo.baymax.companion.domain.ChatContext;
import com.elioo.baymax.companion.domain.TextNormaliser;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.outbound.domain.ValueVerdict;
import com.elioo.baymax.web.adapter.in.ui.UiChatCopyAdapter;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.web.application.port.in.HealthUseCase;
import com.elioo.baymax.web.domain.LabSeries;
import com.elioo.baymax.web.domain.LatestPrescription;
import com.elioo.baymax.web.domain.PrintedBand;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan CHATCOPY, the follow-up to the live Sonnet test of #164: three findings, all safe, all bad on a phone. Every context
 * here is the live test's patient as the builder makes it ({@link ChatContextBuilder#assemble}), so the closed set, the
 * dates and the labels are production's, never written by hand: "Ma", creatinine 1.8 mg/dL against 0.7–1.3 on
 * 12 September 2026, and Tab. Demoprol 5 mg, 1+0+0, after food.
 */
class ChatAnswerWordingTest {

    static final UiChatCopyAdapter COPY = new UiChatCopyAdapter(new UiCopy(), new Copy());
    static final ChatLabels BN = ChatLabels.of("bn", COPY);
    static final ChatLabels EN = ChatLabels.of("en", COPY);

    static ChatContext live(ChatLabels labels) {
        Map<String, Object> med = new LinkedHashMap<>();
        med.put("name", "Tab. Demoprol");
        med.put("dose_text", "5 mg");
        med.put("frequency_text", "1+0+0");
        med.put("timing_text", "খাবারের পরে");
        LabSeries creatinine = new LabSeries("creatinine", "mg/dL", "Creatinine", "mg/dL", List.of(new LabSeries.Point(UUID.randomUUID(),
                UUID.randomUUID(), "1.8", LocalDate.parse("2026-09-12"), false, false, ValueVerdict.Kind.OUTSIDE, "high", "k",
                PrintedBand.of("0.7", "1.3"))));
        LatestPrescription rx = new LatestPrescription(UUID.randomUUID(), LocalDate.parse("2026-09-20"), false, false, List.of(med),
                Optional.empty());
        return ChatContextBuilder.assemble("Ma", new HealthUseCase.HealthLabs(List.of(creatinine), new LabSeries.BpSeries(List.of())), rx,
                6000, labels);
    }

    @Test
    void theLabelsAreReadFromExactlyTheirKeysInBothLanguages() {
        Set<String> read = new HashSet<>();
        ChatCopyPort recording = (lang, key, args) -> {
            read.add(lang + ":" + key);
            return COPY.line(lang, key, args);
        };
        ChatLabels.of("bn", recording);
        Set<String> expected = new HashSet<>();
        for (String key : ChatLabels.KEYS) {
            expected.add("bn:" + key);
            expected.add("en:" + key);
        }
        assertThat(read).as("a Bangla ask reads its own labels, and the English ones it may not hold").isEqualTo(expected);
    }

    // ---- finding 1: a Bangla answer that copies an English label is a repair, never shown and never rewritten ----

    @Test
    void aBanglaAnswerCopyingAnEnglishLabelIsRefused() {
        ChatContext bn = live(BN);
        // the two live answers' shape: the context's English words in a Bangla sentence
        assertThat(AnswerCheck.violations("Ma-র Creatinine 1.8 mg/dL, printed range 0.7–1.3, OUTSIDE high।", bn))
                .contains("foreign_label:printed range", "foreign_label:OUTSIDE");
        assertThat(AnswerCheck.violations("Ma-র Creatinine 1.8 mg/dL, " + EN.verdict(ValueVerdict.Kind.OUTSIDE, "high") + "।", bn))
                .contains("foreign_label:" + EN.verdict(ValueVerdict.Kind.OUTSIDE, "high"));
        assertThat(AnswerCheck.violations("VALUE Creatinine: 1.8 mg/dL", bn)).as("a tag copied from the context")
                .contains("foreign_label:VALUE");
    }

    @Test
    void aBanglaAnswerInTheContextsOwnLabelsAndAnEnglishNameIsNotRefused() {
        ChatContext bn = live(BN);
        // the Bangla labels the context gives, around the value's printed English name and unit: no violation at all
        String answer = "Ma-র Creatinine 1.8 mg/dL, " + BN.range("0.7–1.3") + ", " + BN.verdict(ValueVerdict.Kind.OUTSIDE, "high") + "।";
        assertThat(AnswerCheck.violations(answer, bn)).isEmpty();
        assertThat(AnswerCheck.violations("Ma-র শেষ প্রেসক্রিপশনে লেখা আছে: {{rx:1}}", bn)).as("Demoprol's line, in English, is the paper")
                .isEmpty();
        assertThat(AnswerCheck.violations("Demoprol সাধারণত রক্তচাপের জন্য দেওয়া হয়।", bn)).as("a stored medicine's name is not a label")
                .isEmpty();
    }

    @Test
    void anEnglishAnswerMayUseEnglishWords() {
        ChatContext en = live(EN);
        assertThat(AnswerCheck.violations("Ma's Creatinine is 1.8 mg/dL, outside the printed range 0.7–1.3.", en)).isEmpty();
        assertThat(AnswerCheck.violations("Ma's Creatinine 1.8 mg/dL is " + EN.verdict(ValueVerdict.Kind.OUTSIDE, "high").toLowerCase()
                + ", " + EN.range("0.7–1.3") + ".", en)).isEmpty();
    }

    // ---- finding 3, investigated: which safe drafts about a dose the guard refuses, and why (it is not loosened) ----

    /**
     * The drafts a plain "ওষুধটা অর্ধেক করব?" is likely to produce, through the checks exactly as the pipeline runs them. The
     * guard refuses the dose's noun and the question's own change word wherever they stand, a sentence that sends the
     * family to the doctor included: by design ("the refusal is code's job", corpus line 7), since code appends
     * {@code chat.dose.ask_doctor} to every dose question. A draft that restates the line through its token, says what the
     * medicine is for, or points to the doctor without those words passes. The prompt now steers to that shape; the guard
     * is unchanged (PO ruling P7); a proposal for the PO is in the PR body.
     */
    @Test
    void whatTheGuardRefusesInASafeDoseDraft() {
        ChatContext bn = live(BN);
        assertThat(AnswerCheck.violations("ডোজ বদলানোর আগে ডাক্তারকে জিজ্ঞেস করুন।", bn)).as("the doctor pointer with the word dose")
                .containsExactly("dose_change:ডোজ");
        assertThat(AnswerCheck.violations("Ma-র প্রেসক্রিপশনে ওষুধটা অর্ধেক করার কথা লেখা নেই।", bn)).as("the question's own word, negated")
                .containsExactly("number_word:অর্ধেক", "dose_change:অর্ধেক");
        assertThat(AnswerCheck.violations("Ask the doctor before changing the dose.", live(EN))).containsExactly("dose_change:dose");
        assertThat(AnswerCheck.violations("প্রেসক্রিপশনে সকালে একটা করে লেখা আছে।", bn)).as("the written dose retold in words")
                .contains("number_word:একটা");

        assertThat(AnswerCheck.violations("এই বিষয়ে ডাক্তারকে জিজ্ঞেস করুন।", bn)).as("the doctor pointer without the word").isEmpty();
        assertThat(AnswerCheck.violations("Ask the doctor who wrote the prescription.", live(EN))).isEmpty();
        assertThat(AnswerCheck.violations("Ma-র প্রেসক্রিপশনে লেখা আছে: {{rx:1}}", bn)).as("the written dose, as its token").isEmpty();
    }

    /** The answer the prompt gives as the shape of a dose answer passes every check, in both languages: it is never a trap. */
    @Test
    void thePromptsDoseAnswerPassesEveryCheck() {
        for (ChatLabels labels : List.of(BN, EN)) {
            ChatContext ctx = live(labels);
            String example = ChatPrompt.doseAnswer(labels, "Ma");
            assertThat(ChatPrompt.of(ctx, labels, "Ma", "q", 700, List.of(), 2048).systemPrompt()).contains(example);
            assertThat(AnswerCheck.violations(example, ctx)).as(labels.lang() + ": " + example).isEmpty();
            // the date the prompt shows is the live paper's own, so a sentence written that way passes too
            String date = ChatPrompt.dateExample(labels);
            assertThat(ChatPrompt.of(ctx, labels, "Ma", "q", 700, List.of(), 2048).systemPrompt()).contains("\"" + date + "\"");
            assertThat(AnswerCheck.violations("Ma: Creatinine 1.8 mg/dL, " + date + ".", ctx)).as(labels.lang() + ": " + date).isEmpty();
        }
    }

    // ---- finding 2: number words; the prompt's list is the check's own, never a rule the check does not hold ----

    @Test
    void everyNumberWordThePromptNamesIsOneTheCheckRefuses() {
        ChatContext bn = live(BN);
        assertThat(ChatPrompt.NUMBER_WORDS_NAMED).as("the prompt names some").isNotEmpty();
        // in the check's normal form: a Bangla letter typed with its nukta apart is the same word
        List<String> checked = AnswerCheck.NUMBER_WORDS.stream().map(TextNormaliser::normalise).toList();
        for (String w : ChatPrompt.NUMBER_WORDS_NAMED) {
            assertThat(checked).as(w + " is one of the check's number words").contains(TextNormaliser.normalise(w));
            assertThat(AnswerCheck.violations("Ma-র কাগজে " + w + " লেখা।", bn)).as(w).anyMatch(v -> v.startsWith("number_word:"));
        }
        // and every word the prompt says a dose answer never writes is one the checks refuse anywhere
        for (String w : ChatPrompt.DOSE_WORDS_NAMED) {
            assertThat(AnswerCheck.violations("Ma-র কাগজে " + w + " লেখা।", bn)).as(w).anyMatch(v -> v.startsWith("dose_change:"));
        }
    }
}

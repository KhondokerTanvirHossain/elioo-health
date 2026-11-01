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
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

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
        return live(labels, "Ma", List.of());
    }

    /** The live test's context for a patient of this name, with further lab series beside the creatinine. */
    static ChatContext live(ChatLabels labels, String name, List<LabSeries> more) {
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
        List<LabSeries> series = new ArrayList<>(List.of(creatinine));
        series.addAll(more);
        return ChatContextBuilder.assemble(name, new HealthUseCase.HealthLabs(series, new LabSeries.BpSeries(List.of())), rx, 6000, labels);
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

    /**
     * Review of #170, finding 2: the stored names are taken out before the foreign labels are matched, and only they. A lab
     * series stored as "Blood Pressure" collides with the English {@code health.bp} label ("Blood pressure"), which a Bangla
     * context names "রক্তচাপ": its own printed name is the paper's word, never a copied label. The same context still
     * refuses an English verdict or rule word copied beside it. "Demoprol" and "Creatinine" collide with no label, so
     * without this case deleting the stripping left every test green.
     */
    @Test
    void aStoredNameThatIsAlsoAnEnglishLabelIsThePapersWordAndALabelBesideItIsStillRefused() {
        LabSeries bp = new LabSeries("blood pressure", "mmHg", "Blood Pressure", "mmHg", List.of(new LabSeries.Point(UUID.randomUUID(),
                UUID.randomUUID(), "150", LocalDate.parse("2026-09-12"), false, false, ValueVerdict.Kind.OUTSIDE, "high", "k",
                PrintedBand.of("90", "140"))));
        ChatContext bn = live(BN, "Ma", List.of(bp));
        assertThat(bn.foreignLabels()).as("the collision is real: the English label is a foreign one")
                .contains(EN.bloodPressure());
        assertThat(EN.bloodPressure()).isEqualToIgnoringCase("Blood Pressure");

        assertThat(AnswerCheck.violations("Ma-র Blood Pressure 150 mmHg, " + BN.verdict(ValueVerdict.Kind.OUTSIDE, "high") + "।", bn))
                .as("the stored series name").isEmpty();
        assertThat(AnswerCheck.violations("Ma-র Blood Pressure 150 mmHg, " + EN.verdict(ValueVerdict.Kind.OUTSIDE, "high") + "।", bn))
                .as("an English verdict beside it").containsExactly("foreign_label:" + EN.verdict(ValueVerdict.Kind.OUTSIDE, "high"));
        assertThat(AnswerCheck.violations("Ma-র Blood Pressure 150 mmHg, printed range 90–140।", bn)).as("a rule word beside it")
                .containsExactly("foreign_label:printed range");
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
     * medicine is for, or points to the doctor without those words passes. The prompt now steers to that shape. Since PO
     * ruling P12 (proposal A of #170) a change word beside the dose noun is refused as well; the doctor pointer is not
     * loosened (proposal B, not approved).
     */
    @Test
    void whatTheGuardRefusesInASafeDoseDraft() {
        ChatContext bn = live(BN);
        assertThat(AnswerCheck.violations("ডোজ বদলানোর আগে ডাক্তারকে জিজ্ঞেস করুন।", bn)).as("the doctor pointer with the word dose")
                .containsExactly("dose_change:ডোজ", "dose_change:বদল+medicine");
        assertThat(AnswerCheck.violations("Ma-র প্রেসক্রিপশনে ওষুধটা অর্ধেক করার কথা লেখা নেই।", bn)).as("the question's own word, negated")
                .containsExactly("number_word:অর্ধেক", "dose_change:অর্ধেক");
        assertThat(AnswerCheck.violations("Ask the doctor before changing the dose.", live(EN)))
                .containsExactly("dose_change:dose", "dose_change:changing+medicine");
        assertThat(AnswerCheck.violations("প্রেসক্রিপশনে সকালে একটা করে লেখা আছে।", bn)).as("the written dose retold in words")
                .contains("number_word:একটা");
        // review of #170, finding 4: the written line copied in digits, the draft an unscoped "copy every number" invites
        assertThat(AnswerCheck.violations("Ma-র প্রেসক্রিপশনে Demoprol 5 mg, 1+0+0 লেখা আছে।", bn)).as("the written dose retyped in digits")
                .containsExactly(DIGIT_RESTATEMENT);

        assertThat(AnswerCheck.violations("এই বিষয়ে ডাক্তারকে জিজ্ঞেস করুন।", bn)).as("the doctor pointer without the word").isEmpty();
        assertThat(AnswerCheck.violations("Ask the doctor who wrote the prescription.", live(EN))).isEmpty();
        assertThat(AnswerCheck.violations("Ma-র প্রেসক্রিপশনে লেখা আছে: {{rx:1}}", bn)).as("the written dose, as its token").isEmpty();
    }

    /** What the guard and the closed set refuse in the written line retyped in digits (finding 4 of the review of #170). */
    static final String[] DIGIT_RESTATEMENT = {"number_not_on_paper:5", "number_not_on_paper:1", "number_not_on_paper:0",
            "dose_change:amount"};

    /** A glued "-এর" after a name that ends in a Bangla letter: what NameGrammar exists to prevent (spec §2 F8, D2 #89). */
    static final Pattern GLUED_AFTER_BANGLA = Pattern.compile("[\\u0980-\\u09FF]-এর");

    /**
     * The answer the prompt gives as the shape of a dose answer, for the names a family enters, is built by NameGrammar's
     * possessive (review of #170, findings 1, 8, 13: never a glued "-এর" or "'s"), and its line-1 instance passes every check
     * in both languages: it is never a trap. The prompt shows the line as {{rx:n}} (finding 7), so the check runs on n = 1.
     */
    @Test
    void thePromptsDoseAnswerPassesEveryCheck() {
        record Case(ChatLabels labels, String name, String shown) {
        }
        for (Case c : List.of(
                new Case(BN, "আম্মা", "আম্মার শেষ প্রেসক্রিপশনে লেখা আছে: {{rx:n}}"),
                new Case(BN, "রহিম", "রহিমের শেষ প্রেসক্রিপশনে লেখা আছে: {{rx:n}}"),
                new Case(BN, "Ma", "Ma-র শেষ প্রেসক্রিপশনে লেখা আছে: {{rx:n}}"),
                new Case(EN, "Ma", "Ma's latest prescription says: {{rx:n}}"),
                new Case(EN, "Chris", "Chris' latest prescription says: {{rx:n}}"))) {
            ChatContext ctx = live(c.labels(), c.name(), List.of());
            String system = ChatPrompt.of(ctx, c.labels(), c.name(), "ওষুধটা অর্ধেক করব?", true, 700, List.of(), 2048).systemPrompt();
            assertThat(system).as(c.name()).contains(c.shown());
            assertThat(GLUED_AFTER_BANGLA.matcher(system).find()).as(c.name() + ": no glued -এর after a Bangla letter").isFalse();
            String lineOne = c.shown().replace("{{rx:n}}", "{{rx:1}}");
            assertThat(AnswerCheck.violations(lineOne, ctx)).as(c.labels().lang() + ": " + lineOne).isEmpty();
        }
        for (ChatLabels labels : List.of(BN, EN)) {
            ChatContext ctx = live(labels);
            // the date the prompt shows is the live paper's own, so a sentence written that way passes too
            String date = ChatPrompt.dateExample(labels);
            assertThat(ChatPrompt.of(ctx, labels, "Ma", "q", false, 700, List.of(), 2048).systemPrompt()).contains("\"" + date + "\"");
            assertThat(AnswerCheck.violations("Ma: Creatinine 1.8 mg/dL, " + date + ".", ctx)).as(labels.lang() + ": " + date).isEmpty();
        }
    }

    /**
     * Proposal (A) of #170, approved by the PO (ruling P12, 2026-11-01; review of #170, findings 6, 9, 14): these drafts told
     * the family to change a medicine and passed every check, because "change", বদল- and পাল্ট- were on neither of
     * {@link DoseChangeGuard}'s lists. The rule in both directions: refused beside a medicine, or as an instruction anywhere;
     * a lab value that changed is not refused. Red on the guard before P12, every flag row with {@code []}.
     */
    @Test
    void proposalAChangeBesideAMedicineIsRefusedAndAChangedValueIsNot() {
        ChatContext bn = live(BN);
        ChatContext en = live(EN);
        record Draft(String text, ChatContext ctx) {
        }
        // soft, so a replay reports every row, not the first that fails
        SoftAssertions.assertSoftly(soft -> {
            for (Draft d : List.of(new Draft("Change Demoprol.", en), new Draft("Ma can change {{rx:1}}.", en),
                    new Draft("ওষুধটা বদলে ফেলুন।", bn), new Draft("Demoprol বদলান।", bn),
                    new Draft("ওষুধটা বদলানোর বিষয়ে ডাক্তারকে জিজ্ঞেস করুন।", bn))) {
                soft.assertThat(AnswerCheck.violations(d.text(), d.ctx())).as("flag: " + d.text())
                        .anyMatch(v -> v.startsWith("dose_change:"));
            }
            for (Draft d : List.of(new Draft("Ma-র ক্রিয়েটিনিনের মান আগের কাগজের তুলনায় বদলেছে।", bn),
                    new Draft("The creatinine changed between the two papers.", en))) {
                soft.assertThat(AnswerCheck.violations(d.text(), d.ctx())).as("pass: " + d.text())
                        .noneMatch(v -> v.startsWith("dose_change:"));
            }
        });
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

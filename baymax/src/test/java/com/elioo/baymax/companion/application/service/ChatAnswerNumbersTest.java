package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.domain.ChatContext;
import com.elioo.baymax.outbound.domain.ValueVerdict;
import com.elioo.baymax.web.application.port.in.HealthUseCase;
import com.elioo.baymax.web.domain.LabSeries;
import com.elioo.baymax.web.domain.LatestPrescription;
import com.elioo.baymax.web.domain.PrintedBand;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review of #164 (findings 4, 8, 11, 12, 17, 18): the answer checks over a context built the way production builds it
 * ({@link ChatContextBuilder#assemble}), so the closed set, the dates and the verdicts are the builder's, never written
 * by hand. Each rule in both directions: the dangerous text is refused, the honest one still passes.
 */
class ChatAnswerNumbersTest {

    static final UUID PAPER = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4200001");
    static final UUID RX = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4200002");

    static LabSeries.Point point(String value, String date, ValueVerdict.Kind kind, String side, PrintedBand range) {
        return new LabSeries.Point(PAPER, UUID.randomUUID(), value, LocalDate.parse(date), false, false, kind, side, "k", range);
    }

    static LabSeries series(String name, String unit, LabSeries.Point... points) {
        return new LabSeries(name.toLowerCase(), unit, name, unit, List.of(points));
    }

    /** Creatinine 1.8 (0.6–1.2, outside high) on 2 March 2026; HbA1c 7.2 % (one-sided "<6.5", unknown) on 14 October 2026. */
    static ChatContext ctx() {
        LabSeries creatinine = series("Creatinine", "mg/dL",
                point("1.8", "2026-03-02", ValueVerdict.Kind.OUTSIDE, "high", PrintedBand.of("0.6", "1.2")));
        LabSeries hba1c = series("HbA1c", "%", point("7.2", "2026-10-14", ValueVerdict.Kind.UNKNOWN, null, new PrintedBand(null, 6.5, "<6.5")));
        Map<String, Object> med = new LinkedHashMap<>();
        med.put("name", "Tab. Demoprol");
        med.put("dose_text", "5 mg");
        med.put("frequency_text", "1+0+0");
        LatestPrescription rx = new LatestPrescription(RX, LocalDate.parse("2026-09-20"), false, false, List.of(med), Optional.empty());
        return ChatContextBuilder.assemble("Ma", new HealthUseCase.HealthLabs(List.of(creatinine, hba1c), new LabSeries.BpSeries(List.of())), rx,
                6000);
    }

    /** A context with one INSIDE fact, two-sided: Potassium 4.1 (3.5–5.1). */
    static ChatContext insideCtx() {
        LabSeries potassium = series("Potassium", "mmol/L",
                point("4.1", "2026-09-12", ValueVerdict.Kind.INSIDE, null, PrintedBand.of("3.5", "5.1")));
        return ChatContextBuilder.assemble("Ma", new HealthUseCase.HealthLabs(List.of(potassium), new LabSeries.BpSeries(List.of())),
                LatestPrescription.none(Optional.empty()), 6000);
    }

    // finding 4b, 11, 17: a paper's day or month is not a value or a dose

    @Test
    void aDayOrAMonthOfAPaperIsNotAValue() {
        assertThat(AnswerCheck.violations("Ma-র creatinine 3, সীমার বাইরে।", ctx())).as("3 is only a paper's month")
                .contains("number_not_on_paper:3");
        assertThat(AnswerCheck.violations("Creatinine 2 is high.", ctx())).as("2 is only a paper's day").contains("number_not_on_paper:2");
        assertThat(AnswerCheck.violations("Ma can have 10 units.", ctx())).as("10 is only a paper's month")
                .contains("number_not_on_paper:10");
        assertThat(AnswerCheck.violations("Your creatinine of 14 is high.", ctx())).as("14 is only a paper's day")
                .contains("number_not_on_paper:14");
    }

    @Test
    void aStoredDateWrittenWholeIsStillAllowed() {
        assertThat(AnswerCheck.violations("Ma-র creatinine 1.8 mg/dL ছিল 2 March 2026-এর কাগজে।", ctx())).isEmpty();
        assertThat(AnswerCheck.violations("Ma-র creatinine 1.8 mg/dL, কাগজটা ২ মার্চ ২০২৬ তারিখের।", ctx())).isEmpty();
        assertThat(AnswerCheck.violations("Ma's HbA1c paper is dated 2026-10-14.", ctx())).isEmpty();
        assertThat(AnswerCheck.violations("Ma's HbA1c paper is dated 14/10/2026.", ctx())).isEmpty();
        assertThat(AnswerCheck.violations("Ma's latest prescription, of 20 September, says: {{rx:1}}", ctx())).isEmpty();
        assertThat(AnswerCheck.violations("কাগজটা ১৩ সেপ্টেম্বর ২০২৬ তারিখের।", ctx())).as("a date on no paper")
                .contains("number_not_on_paper:13");
    }

    // finding 11.2: a value is bound to the fact its sentence names

    @Test
    void aNumberIsBoundToTheFactItsSentenceNames() {
        assertThat(AnswerCheck.violations("Ma's Creatinine is 7.2 mg/dL.", ctx())).as("7.2 is HbA1c's, not creatinine's")
                .contains("number_unbound:7.2");
        assertThat(AnswerCheck.violations("Ma's Creatinine is 1.8 mg/dL, outside the printed range 0.6 to 1.2.", ctx())).isEmpty();
        assertThat(AnswerCheck.violations("Ma-র ক্রিয়েটিনিন ১.৮, কাগজে ছাপা সীমার বাইরে।", ctx()))
                .as("a sentence naming no fact in FACTS' words falls back to the closed set").isEmpty();
    }

    // finding 4a, 9, 18: numbers written in words, fractions and superscripts

    @Test
    void numberWordsFractionsAndSuperscriptsAreRefused() {
        assertThat(AnswerCheck.violations("Ma-র sugar is twenty, outside the range.", ctx())).contains("number_word:twenty");
        assertThat(AnswerCheck.violations("Ma-র sugar twelve, a bit above the range.", ctx())).contains("number_word:twelve");
        assertThat(AnswerCheck.violations("Ma-র চাপ একশো চল্লিশ, সীমার বাইরে।", ctx())).contains("number_word:একশো", "number_word:চল্লিশ");
        assertThat(AnswerCheck.violations("½ of {{rx:1}} is enough.", ctx())).anyMatch(v -> v.startsWith("number_"));
        assertThat(AnswerCheck.violations("Creatinine was ²⁵", ctx())).anyMatch(v -> v.startsWith("number_"));
        assertThat(AnswerCheck.violations("দুটো {{rx:1}} একসাথে।", ctx())).contains("number_word:দুটো");
        assertThat(AnswerCheck.violations("Ma-র ক্রিয়েটিনিন ১.৮।", ctx())).as("a stored value in Bangla digits").isEmpty();
    }

    // finding 9.3 and 16: a dose amount, and a number or a duration beside a prescription line

    @Test
    void aDoseAmountOrANumberBesideAPrescriptionLineIsRefused() {
        assertThat(AnswerCheck.violations("Ma-র ডোজ 1.8 mg", ctx())).as("1.8 is on a paper, but as a value, not a dose")
                .anyMatch(v -> v.startsWith("dose_change:"));
        assertThat(AnswerCheck.violations("Give 2 tablets of {{rx:1}} at night.", ctx())).anyMatch(v -> v.startsWith("dose_change:"));
        assertThat(AnswerCheck.violations("{{rx:1}} for 1.2 weeks.", ctx())).as("1.2 is on a paper, beside the line it is a duration")
                .anyMatch(v -> v.startsWith("dose_change:"));
        assertThat(AnswerCheck.violations("Ma-র creatinine 1.8 mg/dL", ctx())).as("a lab unit is not a dose").isEmpty();
    }

    // finding 12: one-sided ranges are never inside

    @Test
    void insideIsRefusedWhereTheFactsDoNotSayInside() {
        assertThat(AnswerCheck.violations("Ma's HbA1c 7.2 is inside the printed range.", ctx())).as("an UNKNOWN one-sided fact")
                .anyMatch(v -> v.startsWith("inside_unverified"));
        assertThat(AnswerCheck.violations("Ma-র HbA1c 7.2, সীমার মধ্যে।", ctx())).anyMatch(v -> v.startsWith("inside_unverified"));
        assertThat(AnswerCheck.violations("Ma's Potassium 4.1 mmol/L is inside the printed range 3.5 to 5.1.", insideCtx())).isEmpty();
    }

    // finding 8: reassurance in its common forms, and a sentence that only looks like one

    @Test
    void theCommonReassurancesAreRefused() {
        assertThat(AnswerCheck.violations("Ma's value is OK.", ctx())).anyMatch(v -> v.startsWith("reassurance:"));
        assertThat(AnswerCheck.violations("Ma's result is within range, so all good.", insideCtx())).anyMatch(v -> v.startsWith("reassurance:"));
        assertThat(AnswerCheck.violations("Ma-র রিপোর্ট ঠিক আছে।", ctx())).anyMatch(v -> v.startsWith("reassurance:"));
        assertThat(AnswerCheck.violations("গুরুতর কিছু না।", ctx())).anyMatch(v -> v.startsWith("reassurance:"));
        assertThat(AnswerCheck.violations("nothing serious, no problem", ctx())).anyMatch(v -> v.startsWith("reassurance:"));
        assertThat(AnswerCheck.violations("Ma-r report thik ache", ctx())).anyMatch(v -> v.startsWith("reassurance:"));
        assertThat(AnswerCheck.violations("এর মানে কী, তা ডাক্তার ঠিক করবেন।", ctx())).isEmpty();
    }
}

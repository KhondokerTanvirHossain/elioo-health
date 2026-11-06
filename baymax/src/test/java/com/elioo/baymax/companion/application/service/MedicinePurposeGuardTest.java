package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.common.text.NameGrammar;
import com.elioo.baymax.companion.domain.ChatContext;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.adapter.in.ui.UiChatCopyAdapter;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.web.application.port.in.HealthUseCase;
import com.elioo.baymax.web.domain.LabSeries;
import com.elioo.baymax.web.domain.LatestPrescription;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PO ruling P14 ("Only what the papers say"), the guard's own rules: the question rows of the corpus ({@link PurposeCorpus},
 * written before the guard) each take their route, a code-built reply with no model call, the model with the purpose line
 * appended, or neither; code's own lines are never refused; a claim is reported by its term. The answer rows run through
 * {@link AnswerCheck} in {@link MedicinePurposeAnswerCorpusTest}.
 */
class MedicinePurposeGuardTest {

    static final UiChatCopyAdapter COPY = new UiChatCopyAdapter(new UiCopy(), new Copy());

    @Test
    void theQuestionRowsTakeTheirRoute() throws IOException {
        ChatContext ctx = PurposeCorpus.context();
        Map<String, Integer> groups = new LinkedHashMap<>();
        List<String> wrong = new ArrayList<>();
        for (PurposeCorpus.Row r : PurposeCorpus.rows()) {
            if (!"question".equals(r.side())) {
                continue;
            }
            groups.merge(r.expect(), 1, Integer::sum);
            MedicinePurposeGuard.Route route = MedicinePurposeGuard.route(r.text(), ctx.medicineNames(), PurposeCorpus.labNames(ctx), "Ma");
            if (!route.name().equalsIgnoreCase(r.expect())) {
                wrong.add("row " + r.line() + ": expected " + r.expect() + " but was " + route + ": " + r.text());
            }
        }
        assertThat(groups.keySet()).containsExactlyInAnyOrder("code", "mixed", "none");
        assertThat(groups.get("code")).isGreaterThanOrEqualTo(20);
        assertThat(groups.get("none")).isGreaterThanOrEqualTo(15);
        assertThat(wrong).isEmpty();
    }

    /**
     * The lines code adds, read from the bundles themselves in both languages (never copied into the corpus, where they
     * would drift): were the model to write any of them, the purpose rule would not refuse it. Every appended line, the
     * purpose reply's own lines with a name filled in, and the fixed replies.
     */
    @Test
    void codesOwnLinesAreNeverAPurposeClaim() {
        ChatContext ctx = PurposeCorpus.context();
        List<String> keys = new ArrayList<>(ChatAppendix.KEYS);
        keys.addAll(MedicinePurposeReply.KEYS);
        keys.addAll(List.of("chat.ask.failed", "chat.ask.reply", "chat.ask.limit", "chat.check.refused",
                "chat.check.refused.lab"));
        int checked = 0;
        for (String lang : List.of("bn", "en")) {
            for (String key : keys) {
                String line = COPY.line(lang, key, NameGrammar.possessiveIn(lang, "Ma"));
                assertThat(MedicinePurposeGuard.states(line, ctx.medicineNames(), PurposeCorpus.labNames(ctx), "Ma"))
                        .as(lang + ":" + key + " = " + line).isEmpty();
                checked++;
            }
            String tail = ChatAppendix.tail(true, true, Optional.of(Urgency.NOW), k -> COPY.line(lang, k));
            assertThat(MedicinePurposeGuard.states(tail, ctx.medicineNames(), PurposeCorpus.labNames(ctx), "Ma"))
                    .as(lang + ": the whole tail").isEmpty();
        }
        assertThat(checked).as("lines checked").isGreaterThanOrEqualTo(2 * 10);
    }

    /** The code-built reply: which lines, the possessive NameGrammar builds, the line that is not there, the room. */
    @Test
    void theCodeReplyShowsOnlyWhatThePapersSay() {
        ChatContext ctx = PurposeCorpus.context();
        MedicinePurposeReply.Reply calmora = MedicinePurposeReply.of("what's Calmora for?", ctx, "Ma", "en", COPY, 1000);
        assertThat(calmora.text()).isEqualTo("Ma's latest prescription says:\n" + PurposeCorpus.RX2);
        assertThat(calmora.documents()).containsExactly(PurposeCorpus.PAPER);
        assertThat(MedicinePurposeReply.of("এটা কিসের ওষুধ?", ctx, "আম্মা", "bn", COPY, 1000).text())
                .as("no medicine named: every line, under the possessive NameGrammar builds, never a glued ending")
                .isEqualTo("আম্মার শেষ প্রেসক্রিপশনে লেখা আছে:\n" + PurposeCorpus.RX1 + "\n" + PurposeCorpus.RX2);
        assertThat(MedicinePurposeReply.of("Napa কীসের?", ctx, "রহিম", "bn", COPY, 1000).text())
                .as("a name no line holds: every line, under the possessive NameGrammar builds, never that it is missing")
                .startsWith(COPY.line("bn", "chat.purpose.unmatched", "রহিমের") + "\n" + PurposeCorpus.RX1);
        // the room: the first line and the pointer to the rest fit, the long second line does not
        String longLine = "Cap. Examplazole 20 mg · 0+0+1 · রাতে খাবারের আগে, পানি দিয়ে, প্রতিদিন একই সময়ে, ১৪ দিন, তারপর ডাক্তার দেখাবেন";
        ChatContext two = new ChatContext(List.of("PATIENT Ma"), java.util.Set.of(), List.of(PurposeCorpus.RX1, longLine),
                List.of(PurposeCorpus.PAPER), false);
        String frame = "Ma's latest prescription says:";
        String more = COPY.line("en", "chat.purpose.more");
        assertThat(longLine.length()).as("the second line is longer than the pointer").isGreaterThan(more.length());
        int room = frame.length() + 1 + PurposeCorpus.RX1.length() + 1 + more.length();
        assertThat(MedicinePurposeReply.of("what is this medicine for?", two, "Ma", "en", COPY, room).text())
                .isEqualTo(frame + "\n" + PurposeCorpus.RX1 + "\n" + more).hasSizeLessThanOrEqualTo(room);
        assertThat(MedicinePurposeReply.of("what is this medicine for?", two, "Ma", "en", COPY, 1000).text())
                .isEqualTo(frame + "\n" + PurposeCorpus.RX1 + "\n" + longLine);
    }

    /**
     * Review of #171, finding 6, the dangerous direction: code never tells a family that a medicine on the prescription is
     * not there. A stored name typed in Bangla script, a typo of a stored name, "which one is the X medicine", and a
     * prescription whose only line is elided (DR-16) each show what is stored, never "এই ওষুধের নাম নেই" and never that
     * the papers hold no line. Uses only the reply's text, so it replays unchanged against the code before the fix.
     */
    @Test
    void aMedicineOnThePrescriptionIsNeverCalledMissing() {
        ChatContext ctx = PurposeCorpus.context();
        for (String q : List.of("ডেমোপ্রল কীসের ওষুধ?", "Demopral কী কাজ করে?", "আম্মার প্রেশারের ওষুধ কোনটা?")) {
            String text = MedicinePurposeReply.of(q, ctx, "Ma", "bn", COPY, 1000).text();
            assertThat(text).as(q).contains(PurposeCorpus.RX1).doesNotContain("এই ওষুধের নাম নেই");
        }
    }

    /** Finding 6 again: a prescription whose only line is elided holds a medicine; code never says it holds none. */
    @Test
    void anElidedLineIsNeverCalledMissing() {
        ChatContext elided = elidedOnly();
        String none = COPY.line("bn", "chat.purpose.none", NameGrammar.possessiveIn("bn", "Ma"));
        for (String q : List.of("Xyzolam কীসের?", "এটা কিসের ওষুধ?")) {
            String text = MedicinePurposeReply.of(q, elided, "Ma", "bn", COPY, 1000).text();
            assertThat(text).as(q + " with only an elided line").isNotEqualTo(none).doesNotContain("এই ওষুধের নাম নেই")
                    .doesNotContain("কোনো ওষুধের লাইন নেই");
        }
    }

    /**
     * Review of #171, findings 6, 9 and 16: what the code-built reply says when no stored line is named (every line, after
     * "I could not match that name"), what it says about an elided line (it is on the paper: see the photo), and that it
     * returns the lines it quoted, in order, and only the prescription's paper, so each quoted line can be shown beside its
     * crop (DR-12) once #166 lands.
     */
    @Test
    void theCodeReplyNamesTheLinesItQuotedAndOnlyThePrescription() {
        ChatContext ctx = PurposeCorpus.context();
        String poss = NameGrammar.possessiveIn("bn", "Ma");
        MedicinePurposeReply.Reply napa = MedicinePurposeReply.of("Napa কীসের?", ctx, "Ma", "bn", COPY, 1000);
        assertThat(napa.text()).isEqualTo(COPY.line("bn", "chat.purpose.unmatched", poss) + "\n" + PurposeCorpus.RX1 + "\n"
                + PurposeCorpus.RX2);
        assertThat(napa.quoted()).containsExactly(1, 2);
        assertThat(napa.documents()).containsExactly(PurposeCorpus.PAPER);
        MedicinePurposeReply.Reply bangla = MedicinePurposeReply.of("ডেমোপ্রল কীসের ওষুধ?", ctx, "Ma", "bn", COPY, 1000);
        assertThat(bangla.text()).isEqualTo(COPY.line("bn", "chat.purpose.written", poss) + "\n" + PurposeCorpus.RX1);
        assertThat(bangla.quoted()).containsExactly(1);
        assertThat(MedicinePurposeReply.of("Demopral কী কাজ করে?", ctx, "Ma", "bn", COPY, 1000).quoted()).containsExactly(1);
        MedicinePurposeReply.Reply which = MedicinePurposeReply.of("আম্মার প্রেশারের ওষুধ কোনটা?", ctx, "Ma", "bn", COPY, 1000);
        assertThat(which.text()).as("no name typed, only \"which one\": every line under the written frame")
                .startsWith(COPY.line("bn", "chat.purpose.written", poss) + "\n");
        assertThat(which.quoted()).containsExactly(1, 2);
        // every reply's body lines are exactly its quoted lines, rendered, in order
        for (MedicinePurposeReply.Reply r : List.of(napa, bangla, which)) {
            List<String> body = List.of(r.text().split("\n"));
            List<String> expected = r.quoted().stream().map(n -> AnswerCheck.resolve("{{rx:" + n + "}}", ctx)).toList();
            assertThat(body.subList(1, body.size())).isEqualTo(expected);
        }
        ChatContext elided = elidedOnly();
        assertThat(elided.unshownNames()).containsExactly("xyzolam");
        MedicinePurposeReply.Reply unshown = MedicinePurposeReply.of("Xyzolam কীসের?", elided, "Ma", "bn", COPY, 1000);
        assertThat(unshown.text()).isEqualTo(COPY.line("bn", "chat.purpose.unshown", poss));
        assertThat(unshown.quoted()).isEmpty();
        assertThat(unshown.documents()).containsExactly(ELIDED_PAPER);
        // a shown line and an elided one: the elided one asked about is on the paper, never "not there"
        ChatContext both = withMedicines(List.of(med("Tab. Demoprol", "5 mg", "1+0+0"), med("Tab. Xyzolam", "2 mg", "1+0+…")));
        assertThat(MedicinePurposeReply.of("Xyzolam কীসের?", both, "Ma", "bn", COPY, 1000).text())
                .isEqualTo(COPY.line("bn", "chat.purpose.unshown", poss));
        assertThat(MedicinePurposeReply.of("Demoprol কীসের?", both, "Ma", "bn", COPY, 1000).quoted()).containsExactly(1);
        ChatContext none = new ChatContext(List.of("PATIENT Ma"), java.util.Set.of(), List.of(), List.of(), false);
        MedicinePurposeReply.Reply nothing = MedicinePurposeReply.of("Napa কীসের?", none, "Ma", "en", COPY, 1000);
        assertThat(nothing.text()).isEqualTo(COPY.line("en", "chat.purpose.none", "Ma's")).contains("latest prescription");
        assertThat(nothing.quoted()).isEmpty();
        assertThat(nothing.documents()).isEmpty();
    }

    static final UUID ELIDED_PAPER = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100015");

    /** A prescription whose one medicine line is elided ("…"), so it is never shown (DR-16), built as production builds it. */
    static ChatContext elidedOnly() {
        return withMedicines(List.of(med("Tab. Xyzolam", "2 mg", "1+0+…")));
    }

    static ChatContext withMedicines(List<Map<String, Object>> medicines) {
        LatestPrescription rx = new LatestPrescription(ELIDED_PAPER, LocalDate.parse("2026-09-20"), false, false, medicines,
                Optional.empty());
        return ChatContextBuilder.assemble("Ma", new HealthUseCase.HealthLabs(List.of(), new LabSeries.BpSeries(List.of())), rx, 6000,
                ChatLabels.of("bn", COPY));
    }

    static Map<String, Object> med(String name, String dose, String frequency) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("dose_text", dose);
        m.put("frequency_text", frequency);
        return m;
    }

    @Test
    void aClaimIsReportedByItsTerm() {
        ChatContext ctx = PurposeCorpus.context();
        assertThat(MedicinePurposeGuard.states("Demoprol is generally used for blood pressure.", ctx.medicineNames(),
                PurposeCorpus.labNames(ctx), "Ma")).contains("medicine_purpose:used for");
        assertThat(MedicinePurposeGuard.states("এটা ব্যথার ওষুধ।", ctx.medicineNames(), PurposeCorpus.labNames(ctx), "Ma"))
                .anySatisfy(c -> assertThat(c).startsWith("medicine_purpose:"));
        assertThat(AnswerCheck.forLog(List.of("medicine_purpose:used for"))).containsExactly("medicine_purpose");
    }
}

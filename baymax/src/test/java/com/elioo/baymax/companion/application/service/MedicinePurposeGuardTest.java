package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.common.text.NameGrammar;
import com.elioo.baymax.companion.domain.ChatContext;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.adapter.in.ui.UiChatCopyAdapter;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        keys.addAll(List.of("chat.ask.failed", "chat.ask.reply", "chat.ask.limit"));
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
        MedicinePurposeReply.Reply napa = MedicinePurposeReply.of("Napa কীসের?", ctx, "রহিম", "bn", COPY, 1000);
        assertThat(napa.text()).isEqualTo("রহিমের শেষ প্রেসক্রিপশনে এই ওষুধের নাম নেই।");
        assertThat(napa.documents()).isEmpty();
        ChatContext none = new ChatContext(List.of("PATIENT Ma"), java.util.Set.of(), List.of(), List.of(), false);
        assertThat(MedicinePurposeReply.of("Napa কীসের?", none, "Ma", "bn", COPY, 1000).text()).isEqualTo("কাগজপত্রে Ma-র কোনো ওষুধের লাইন নেই।");
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

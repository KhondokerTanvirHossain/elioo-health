package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.common.text.NameGrammar;
import com.elioo.baymax.companion.application.port.out.ChatCopyPort;
import com.elioo.baymax.companion.domain.ChatContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PO ruling P14's code-built answer to a question about what a medicine is for ({@link MedicinePurposeGuard.Route#CODE}),
 * with no model call: only what the papers say. Code appends {@code chat.purpose.ask}, which sends what it is for to the
 * doctor or the pharmacist, then the usual lines ({@link ChatAppendix}). The body is one of:
 * <ul>
 *   <li>the medicine's written line(s) under {@code chat.purpose.written}, each through the existing {@code {{rx:n}}}
 *       rendering ({@link AnswerCheck#resolve}), one per line: the lines whose medicine the question names (a stored name as
 *       a stem and across a hyphen), or every line when it names none ("এটা কিসের ওষুধ?");</li>
 *   <li>{@code chat.purpose.not_written} when it names a medicine the latest prescription does not hold ("Napa কীসের?" with
 *       only Demoprol stored): a word is left once every word that carries no name is gone;</li>
 *   <li>{@code chat.purpose.none} when no medicine line is stored at all.</li>
 * </ul>
 * The patient's possessive is NameGrammar's ({@code Ma-র}, {@code আম্মার}, {@code Ma's}), never an ending glued to the name.
 * Lines are kept in order while the whole text fits {@code room}; when one does not, {@code chat.purpose.more} says where
 * the rest are. When the question names a stored medicine and also another word, only the named lines are shown: a leftover
 * word may be a filler no list holds, and a line saying a medicine is missing must not stand beside the one asked about.
 */
public final class MedicinePurposeReply {

    /** Every bundle key this class reads ({@code chat.purpose.ask} is {@link ChatAppendix}'s). */
    public static final List<String> KEYS = List.of("chat.purpose.written", "chat.purpose.not_written", "chat.purpose.none",
            "chat.purpose.more");

    /** The body and the papers its lines came from. */
    public record Reply(String text, List<UUID> documents) {
        public Reply {
            documents = List.copyOf(documents);
        }
    }

    private MedicinePurposeReply() {
    }

    /**
     * @param room the characters the body may take: the answer cap less the appended lines
     */
    public static Reply of(String question, ChatContext ctx, String patientName, String lang, ChatCopyPort copy, int room) {
        String l = "en".equals(lang) ? "en" : "bn";
        String possessive = NameGrammar.possessiveIn(l, patientName);
        if (ctx.verbatimLines().isEmpty()) {
            return new Reply(copy.line(l, "chat.purpose.none", possessive), List.of());
        }
        List<Integer> named = MedicinePurposeGuard.linesNamed(question, ctx);
        if (named.isEmpty() && !MedicinePurposeGuard.leftover(DoseChangeGuard.hay(question), ctx.medicineNames(), patientName).isEmpty()) {
            return new Reply(copy.line(l, "chat.purpose.not_written", possessive), List.of());
        }
        List<Integer> lines = new ArrayList<>(named);
        if (lines.isEmpty()) {
            for (int i = 1; i <= ctx.verbatimLines().size(); i++) {
                lines.add(i);
            }
        }
        String more = copy.line(l, "chat.purpose.more");
        StringBuilder body = new StringBuilder(copy.line(l, "chat.purpose.written", possessive));
        int shown = 0;
        for (int i = 0; i < lines.size(); i++) {
            String next = "\n" + AnswerCheck.resolve("{{rx:" + lines.get(i) + "}}", ctx);
            boolean last = i == lines.size() - 1;
            int needed = body.length() + next.length() + (last ? 0 : 1 + more.length());
            if (needed > room) {
                break;
            }
            body.append(next);
            shown++;
        }
        if (shown == 0) {
            return new Reply(more, List.of());
        }
        if (shown < lines.size()) {
            body.append("\n").append(more);
        }
        return new Reply(body.toString(), ctx.documentIds());
    }
}

package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.common.text.NameGrammar;
import com.elioo.baymax.companion.application.port.out.ChatCopyPort;
import com.elioo.baymax.companion.domain.ChatContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * PO ruling P14's code-built answer to a question about what a medicine is for ({@link MedicinePurposeGuard.Route#CODE}),
 * with no model call: only what the papers say. Code appends {@code chat.purpose.ask}, which sends what it is for to the
 * doctor or the pharmacist, then the usual lines ({@link ChatAppendix}). The body is one of:
 * <ul>
 *   <li>the medicine's written line(s), each through the existing {@code {{rx:n}}} rendering ({@link AnswerCheck#resolve}),
 *       one per line: the lines whose medicine the question names (a stored name as a stem, across a hyphen, or by
 *       {@link NameSkeleton} in Bangla script or a vowel off), under {@code chat.purpose.written}; or every line when it
 *       names none, under {@code chat.purpose.written} when no other word is left ("এটা কিসের ওষুধ?") and under
 *       {@code chat.purpose.unmatched} ("I could not match that name") when one is ("Napa কীসের?");</li>
 *   <li>{@code chat.purpose.unshown} when it names a medicine whose line is never shown (an abbreviated one, DR-16), or when
 *       every line of the latest prescription is such a line: the medicine is on the paper, and the photo is under Papers;</li>
 *   <li>{@code chat.purpose.none} when Medioo keeps no medicine line from the latest prescription at all.</li>
 * </ul>
 * Code never concludes that a medicine is not on the prescription (review of #171, finding 6): a word no stored line holds
 * may be a stored name typed otherwise, so every line is shown and the family sees what is written.
 *
 * <p>The patient's possessive is NameGrammar's ({@code Ma-র}, {@code আম্মার}, {@code Ma's}), never an ending glued to the name.
 * Lines are kept in order while the whole text fits {@code room}; when one does not, {@code chat.purpose.more} says where
 * the rest are. The reply names the lines it quoted, in order, and cites only the prescription's paper, so each quoted line
 * can be shown beside its crop (DR-12; review of #171, findings 9 and 16).
 */
public final class MedicinePurposeReply {

    /** Every bundle key this class reads ({@code chat.purpose.ask} is {@link ChatAppendix}'s). */
    public static final List<String> KEYS = List.of("chat.purpose.written", "chat.purpose.unmatched", "chat.purpose.unshown",
            "chat.purpose.none", "chat.purpose.more");

    /**
     * The body, the papers its lines came from (only the prescription's), and the stored lines it quoted, from 1, in the order
     * shown: what a turn shows beside each line's crop.
     */
    public record Reply(String text, List<UUID> documents, List<Integer> quoted) {
        public Reply {
            documents = List.copyOf(documents);
            quoted = List.copyOf(quoted);
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
        List<UUID> paper = ctx.prescriptionId() == null ? List.of() : List.of(ctx.prescriptionId());
        List<Integer> named = MedicinePurposeGuard.linesNamed(question, ctx);
        boolean unshown = MedicinePurposeGuard.namesUnshown(question, ctx);
        if (ctx.verbatimLines().isEmpty() || (named.isEmpty() && unshown)) {
            return ctx.unshownNames().isEmpty()
                    ? new Reply(copy.line(l, "chat.purpose.none", possessive), List.of(), List.of())
                    : new Reply(copy.line(l, "chat.purpose.unshown", possessive), paper, List.of());
        }
        List<Integer> lines = new ArrayList<>(named);
        String head = "chat.purpose.written";
        if (lines.isEmpty()) {
            for (int i = 1; i <= ctx.verbatimLines().size(); i++) {
                lines.add(i);
            }
            Set<String> known = new LinkedHashSet<>(ctx.medicineNames());
            known.addAll(ctx.unshownNames());
            boolean typed = MedicinePurposeGuard.leftover(DoseChangeGuard.hay(question), known, patientName).stream()
                    .anyMatch(w -> !MedicinePurposeGuard.notAName(w));
            if (typed) {
                head = "chat.purpose.unmatched";
            }
        }
        String more = copy.line(l, "chat.purpose.more");
        StringBuilder body = new StringBuilder(copy.line(l, head, possessive));
        List<Integer> shown = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String next = "\n" + AnswerCheck.resolve("{{rx:" + lines.get(i) + "}}", ctx);
            boolean last = i == lines.size() - 1;
            int needed = body.length() + next.length() + (last ? 0 : 1 + more.length());
            if (needed > room) {
                break;
            }
            body.append(next);
            shown.add(lines.get(i));
        }
        if (shown.isEmpty()) {
            return new Reply(more, paper, List.of());
        }
        if (shown.size() < lines.size()) {
            body.append("\n").append(more);
        } else if (unshown) {
            // a shown line and an elided one both named: the elided one is on the paper too, never left out unsaid
            String also = "\n" + copy.line(l, "chat.purpose.unshown", possessive);
            if (body.length() + also.length() <= room) {
                body.append(also);
            }
        }
        return new Reply(body.toString(), paper, shown);
    }
}

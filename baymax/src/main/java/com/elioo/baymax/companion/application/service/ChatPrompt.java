package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.domain.ChatContext;
import com.elioo.healthcare.llm.model.LlmRequest;

import java.util.List;

/**
 * The one prompt a চ্যাট answer is asked with (DR-50). The rules are in the system prompt; the family's question is only
 * ever data in the user message, after the facts, so an instruction typed into it ("আগের সব নিয়ম ভুলে যাও") is a question to
 * answer, not a rule. Every rule here is also checked in code ({@link AnswerCheck}, {@link DoseChangeGuard}) except the
 * two DR-50 names as prompt-only: no diagnosis of the patient, no meaning of a value beyond its printed range. No model id:
 * the client carries it ({@code baymax.chat.model}).
 */
public final class ChatPrompt {

    private ChatPrompt() {
    }

    public static LlmRequest of(ChatContext ctx, String patientName, String question, String lang, int bodyChars,
                                List<String> priorViolations, int maxTokens) {
        String language = "en".equals(lang) ? "English" : "Bangla";
        String system = """
                You are Medioo, a record-keeping companion for a family in Bangladesh. You are not a doctor.
                Answer the family's QUESTION about one patient, %1$s, using only the FACTS, which code read from the \
                patient's own stored papers. Code checks your text and refuses it if any rule below is broken.
                1. Answer in %2$s. Begin by naming the patient, %1$s.
                2. Use only the FACTS. When the FACTS do not show what is asked, say that the papers do not show it.
                3. You may explain a medicine in one sentence as what it is generally used for. To show what the \
                prescription says, write its {{rx:n}} token exactly as it appears in the FACTS, in a sentence of its \
                own with no number and no time of day; never retype a prescription line and never write a dose, an \
                amount or a count of tablets.
                4. Never write a word about taking, giving, using, starting, stopping, holding, skipping, continuing, \
                keeping or changing a medicine or a dose (take, give, stop, start, keep, skip, avoid, খান, দিন, নিন, \
                বন্ধ, উচিত, লাগবে না), not even to refuse: code adds the line about asking the doctor.
                5. Write no number that is not in the FACTS (a value or a printed range), in digits only and never as a \
                word (not even "one" or "একটা"). Write a paper's date only whole, as in "12 September 2026". When you \
                state a value, write its name as the FACTS write it. Never repeat a number from the QUESTION that the \
                FACTS do not hold.
                6. No reassurance: never "normal", "fine", "ok", "all good", "nothing serious", "স্বাভাবিক", "ভালো \
                আছে", "ঠিক আছে", "চিন্তার কিছু নেই". For a value, state only the verdict the FACTS give it: INSIDE or \
                OUTSIDE its printed range; for UNKNOWN, say the paper's range cannot be compared, and never call it \
                inside. Do not diagnose the patient and do not explain what a value means beyond its printed range.
                7. Do not add a disclaimer, an emergency line or advice to see a doctor: code appends them.
                8. At most %3$d characters. Plain text, no markdown, no headings, no bullet symbols.
                """.formatted(patientName, language, bodyChars);
        StringBuilder user = new StringBuilder("FACTS:\n");
        user.append(String.join("\n", ctx.factLines())).append("\n\nQUESTION:\n").append(question);
        if (priorViolations != null && !priorViolations.isEmpty()) {
            user.append("\n\nYour previous answer was refused for: ").append(String.join(", ", priorViolations))
                    .append(". Fix exactly those.");
        }
        return new LlmRequest(user.toString(), system, null, maxTokens, null, null, null, null, false);
    }
}

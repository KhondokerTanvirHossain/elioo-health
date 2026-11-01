package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.domain.ChatContext;
import com.elioo.healthcare.llm.model.LlmRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The one prompt a চ্যাট answer is asked with (DR-50). The rules are in the system prompt; the family's question is only
 * ever data in the user message, after the facts, so an instruction typed into it ("আগের সব নিয়ম ভুলে যাও") is a question to
 * answer, not a rule. Every rule here is also checked in code ({@link AnswerCheck}, {@link DoseChangeGuard}) except the
 * two DR-50 names as prompt-only: no diagnosis of the patient, no meaning of a value beyond its printed range. No model id:
 * the client carries it ({@code baymax.chat.model}).
 *
 * <p>Plan CHATCOPY, from the live Sonnet test of #164: the verdicts are named in the ask's own labels ({@link ChatLabels}),
 * and a Bangla ask is told never to write an English label or tag; numbers are copied as digits exactly as printed, and
 * the number words the check refuses most often are named ({@link #NUMBER_WORDS_NAMED}, each one of
 * {@link AnswerCheck#NUMBER_WORDS}); a question about changing a medicine is steered to its written line
 * ({@link #doseAnswer}, an answer that passes every check) and away from the question's own change word. A repair names
 * each violation and, for those three findings, how to fix it. The checks are unchanged: the prompt only asks for what they
 * already require.
 */
public final class ChatPrompt {

    /**
     * Number words the prompt names, so the model does not write them: the Bangla article and the counts and fractions an
     * answer about a medicine or a value reaches for. Each is one of {@link AnswerCheck#NUMBER_WORDS}, never a rule the check
     * does not hold (ChatAnswerWordingTest).
     */
    static final List<String> NUMBER_WORDS_NAMED = List.of("এক", "একটা", "একটি", "একবার", "দুই", "দুটো", "দেড়", "আড়াই", "অর্ধেক",
            "দ্বিগুণ", "প্রায়", "ভাগ", "গুণ", "শতাংশ", "one", "once", "twice", "half", "double", "times", "percent");

    /** The words of a dose question the answer must not repeat: each is refused by {@link DoseChangeGuard} anywhere. */
    static final List<String> DOSE_WORDS_NAMED = List.of("ডোজ", "dose", "ট্যাবলেট", "tablet", "অর্ধেক", "half", "দ্বিগুণ", "double",
            "বন্ধ", "stop");

    private ChatPrompt() {
    }

    /**
     * The whole answer to a question that asks to change a medicine, as the prompt shows it: the patient's name and the
     * first prescription line as its token. It passes every check in either language (ChatAnswerWordingTest), and code adds
     * {@code chat.dose.ask_doctor} after it.
     */
    static String doseAnswer(ChatLabels labels, String patientName) {
        return "en".equals(labels.lang()) ? patientName + "'s latest prescription says: {{rx:1}}"
                : patientName + "-এর শেষ প্রেসক্রিপশনে লেখা আছে: {{rx:1}}";
    }

    /**
     * How a whole date is written, in the ask's language: the month by name, the digits as printed. A matching paper's date
     * in this form is taken out before the numbers are checked (ChatAnswerWordingTest).
     */
    static String dateExample(ChatLabels labels) {
        return "en".equals(labels.lang()) ? "12 September 2026" : "12 সেপ্টেম্বর 2026";
    }

    public static LlmRequest of(ChatContext ctx, ChatLabels labels, String patientName, String question, int bodyChars,
                                List<String> priorViolations, int maxTokens) {
        boolean english = "en".equals(labels.lang());
        String language = english ? "English" : "Bangla";
        String bangla = english ? "" : " Write Bangla words only, apart from a name or a unit the FACTS write in English: never "
                + "write an English word of these rules or of the tags, such as " + quoted(Stream.concat(ChatLabels.RULE_WORDS.stream(),
                Stream.of("VALUE")).toList()) + ".";
        List<String> verdicts = new ArrayList<>();
        verdicts.add(labels.inside());
        verdicts.addAll(labels.outside());
        String system = """
                You are Medioo, a record-keeping companion for a family in Bangladesh. You are not a doctor.
                Answer the family's QUESTION about one patient, %1$s, using only the FACTS, which code read from the \
                patient's own stored papers. Code checks your text and refuses it if any rule below is broken.
                How the FACTS are written: each line starts with a tag in capitals (PATIENT, VALUE, PRESCRIPTION, NOT SHOWN), \
                which is for you: never write a tag. A VALUE line is a value's name and reading, then the range printed on \
                its report, then its verdict, then its paper's date, divided by "|". "NOT SHOWN k" means k older readings \
                were left out. Every label in the FACTS is already in %2$s: when you use one, copy it exactly as the FACTS \
                write it.
                1. Answer in %2$s. Begin by naming the patient, %1$s.%4$s
                2. Use only the FACTS. When the FACTS do not show what is asked, say that the papers do not show it.
                3. You may explain a medicine in one sentence as what it is generally used for. To show what the \
                prescription says, write its {{rx:n}} token exactly as it appears in the FACTS, in a sentence of its \
                own with no number and no time of day; never retype a prescription line and never write a dose, an \
                amount or a count of tablets.
                4. Never write a word about taking, giving, using, starting, stopping, holding, skipping, continuing, \
                keeping or changing a medicine or a dose (take, give, stop, start, keep, skip, avoid, খান, দিন, নিন, \
                বন্ধ, উচিত, লাগবে না), not even to refuse: code adds the line about asking the doctor.
                5. A QUESTION that asks to change, halve, double, stop, skip or start a medicine gets only this: the \
                patient's name and the {{rx:n}} token of each medicine it is about, in a sentence of its own, as in: \
                %5$s — and, if you like, one sentence on what the medicine is generally used for. When the FACTS hold no \
                PRESCRIPTION, say only that the papers do not show it. Do not repeat the QUESTION's words about the change, \
                and never write %6$s, not even to say you cannot answer: code adds the line about asking the doctor.
                6. Copy every number in digits exactly as the FACTS print it (1.8 stays 1.8), and write no number that is \
                not in the FACTS (a value or a printed range). Never write a number as a word: "একটা" and "একটি" ("a", \
                "one") are number words, so write "ওষুধটা" or "কাগজটা", never "একটা ওষুধ"; never write %7$s. Write a \
                paper's date only whole, as in "%10$s". When you state a value, write its name as the FACTS \
                write it. Never repeat a number from the QUESTION that the FACTS do not hold.
                7. No reassurance: never "normal", "fine", "ok", "all good", "nothing serious", "স্বাভাবিক", "ভালো \
                আছে", "ঠিক আছে", "চিন্তার কিছু নেই". For a value, state only the verdict the FACTS give it, in the \
                FACTS' own words (%8$s); for "%9$s", say the paper's range cannot be compared, and never call it \
                inside. Do not diagnose the patient and do not explain what a value means beyond its printed range.
                8. Do not add a disclaimer, an emergency line or advice to see a doctor: code appends them.
                9. At most %3$d characters. Plain text, no markdown, no headings, no bullet symbols.
                """.formatted(patientName, language, bodyChars, bangla, doseAnswer(labels, patientName), quoted(DOSE_WORDS_NAMED),
                quoted(NUMBER_WORDS_NAMED), quoted(verdicts), labels.unknown(), dateExample(labels));
        StringBuilder user = new StringBuilder("FACTS:\n");
        user.append(String.join("\n", ctx.factLines())).append("\n\nQUESTION:\n").append(question);
        if (priorViolations != null && !priorViolations.isEmpty()) {
            user.append("\n\nYour previous answer was refused for: ").append(String.join(", ", priorViolations))
                    .append(". Fix exactly those.");
            for (String hint : hints(priorViolations, labels, patientName, language).values()) {
                user.append("\n").append(hint);
            }
        }
        return new LlmRequest(user.toString(), system, null, maxTokens, null, null, null, null, false);
    }

    /** How to fix each violation code of the live test's three findings, once per code, in the order first refused. */
    private static Map<String, String> hints(List<String> violations, ChatLabels labels, String patientName, String language) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String v : violations) {
            String code = v.contains(":") ? v.substring(0, v.indexOf(':')) : v;
            switch (code) {
                case "number_word" -> out.putIfAbsent(code, "Write every number in digits exactly as the FACTS print it, and "
                        + "leave out every number word, \"একটা\" and \"একটি\" included.");
                case "dose_change" -> out.putIfAbsent(code, "Leave out every word about a dose, a tablet or a change, the "
                        + "QUESTION's own words included; for a medicine, give only its token, as in: " + doseAnswer(labels, patientName));
                case "foreign_label" -> out.putIfAbsent(code, "Write the FACTS' labels exactly as the FACTS write them, in "
                        + language + ", and never a tag.");
                default -> {
                }
            }
        }
        return out;
    }

    private static String quoted(List<String> words) {
        return words.stream().map(w -> "\"" + w + "\"").collect(Collectors.joining(", "));
    }
}

package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.domain.ChatContext;
import com.elioo.baymax.outbound.application.service.BanglaDate;
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
 * and a Bangla ask is told never to write an English label or tag; the FACTS' values, ranges and dates are copied as digits
 * exactly as printed, and the number words the check refuses most often are named ({@link #NUMBER_WORDS_NAMED}, each one of
 * {@link AnswerCheck#NUMBER_WORDS}); a question about changing a medicine is steered to its written line
 * ({@link #doseAnswer}, an answer that passes every check) and away from the question's own change word. A repair names
 * each violation and, for those three findings, how to fix it. The checks are unchanged: the prompt only asks for what they
 * already require.
 *
 * <p>Review of #170: whether the question asks to change a medicine is code's decision ({@link DoseChangeGuard#asksForChange},
 * the one that appends {@code chat.dose.ask_doctor}), passed in; only such a question's prompt carries the dose rule. Its
 * example names no one medicine ({@code {{rx:n}}}, and which n), and is bundle copy ({@code chat.dose.example}) with the
 * possessive built by NameGrammar.
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

    /**
     * PO ruling P14 ("Only what the papers say"), said plainly in every prompt, for a Bangla ask and an English one: the
     * live re-measure of #170 found the model writing "এটি সাধারণত রক্তচাপ নিয়ন্ত্রণে ব্যবহৃত হয়" about the fictional
     * Demoprol, under the old rule that allowed "what it is generally used for". The words named are each refused in code
     * ({@link MedicinePurposeGuard}); the prompt only asks for what the check requires.
     */
    static final String PURPOSE_RULE = "Never say what a medicine is for, what it treats or helps with, or what kind of medicine "
            + "it is, even if you know it, and not with \"usually\", \"generally\" or \"may\": never write \"used for\", \"is for\", "
            + "\"treats\", \"helps\", \"a blood pressure medicine\", \"কীসের জন্য দেওয়া হয়\", \"ব্যবহৃত হয়\", \"জন্য দেওয়া হয়\", "
            + "\"জ্বরের ওষুধ\", \"ব্যথার ওষুধ\" or a kind of medicine (\"antibiotic\", \"অ্যান্টিবায়োটিক\"). State only what the "
            + "prescription writes about it, through its {{rx:n}} token, and when the QUESTION asks what a medicine is for, say "
            + "nothing about it: code adds the line that sends what it is for to the doctor or the pharmacist.";

    /** The token the example shows: any line, the one the question names (review of #170, finding 7). */
    static final String ANY_LINE = "{{rx:n}}";

    /** Which line the example's {@code n} is, and what to write when the question names no medicine, or one not written. */
    static final String WHICH_LINE = "n is the line of the medicine the QUESTION names. When the QUESTION does not name a medicine "
            + "on the PRESCRIPTION, write every {{rx:n}} line, each in its own sentence. When it names a medicine the PRESCRIPTION "
            + "does not hold, say only that the papers do not show it.";

    private ChatPrompt() {
    }

    /**
     * The whole answer to a question that asks to change a medicine, as the prompt shows it: {@code chat.dose.example} with
     * the patient's possessive (NameGrammar) and a line's token. Its line-1 instance passes every check in either language
     * (ChatAnswerWordingTest), and code adds {@code chat.dose.ask_doctor} after it.
     */
    static String doseAnswer(ChatLabels labels, String patientName, String token) {
        return labels.doseAnswer(patientName, token);
    }

    /**
     * How a whole date is written, in the ask's language: the month by name (BanglaDate's for a Bangla ask), the digits as
     * printed. A matching paper's date in this form is taken out before the numbers are checked (ChatAnswerWordingTest).
     */
    static String dateExample(ChatLabels labels) {
        return "en".equals(labels.lang()) ? "12 September 2026" : "12 " + BanglaDate.months().get(8) + " 2026";
    }

    /**
     * @param doseQuestion code's decision that the question asks to change a medicine ({@link DoseChangeGuard#asksForChange}):
     *                     only then is the dose rule in the prompt, and only then does code append the ask-the-doctor line
     */
    public static LlmRequest of(ChatContext ctx, ChatLabels labels, String patientName, String question, boolean doseQuestion,
                                int bodyChars, List<String> priorViolations, int maxTokens) {
        boolean english = "en".equals(labels.lang());
        String language = english ? "English" : "Bangla";
        String bangla = english ? "" : " Write Bangla words only, apart from a name or a unit the FACTS write in English: never "
                + "write an English word of these rules or of the tags, such as " + quoted(Stream.concat(ChatLabels.RULE_WORDS.stream(),
                Stream.of("VALUE")).toList()) + ".";
        List<String> verdicts = new ArrayList<>();
        verdicts.add(labels.inside());
        verdicts.addAll(labels.outside());
        List<String> rules = new ArrayList<>();
        rules.add("Answer in %s. Begin by naming the patient, %s.%s".formatted(language, patientName, bangla));
        rules.add("Use only the FACTS. When the FACTS do not show what is asked, say that the papers do not show it.");
        rules.add(PURPOSE_RULE);
        rules.add("""
                To show what the prescription says, write its {{rx:n}} token exactly as it appears in the FACTS, in a \
                sentence of its own with no number and no time of day; never retype a prescription line and never write a \
                dose, an amount or a count of tablets.""");
        rules.add("""
                Never write a word about taking, giving, using, starting, stopping, holding, skipping, continuing, keeping or \
                changing a medicine or a dose (take, give, stop, start, keep, skip, avoid, খান, দিন, নিন, বন্ধ, উচিত, লাগবে না), \
                not even to refuse.""");
        if (doseQuestion) {
            rules.add(doseRule(ctx, labels, patientName));
        }
        rules.add("""
                Copy every value, printed range and date in the FACTS in digits exactly as the FACTS print it (1.8 stays 1.8), \
                and write no other number; a prescription line's numbers are shown only through its {{rx:n}} token, never \
                typed. Never write a number as a word: "একটা" and "একটি" ("a", "one") are number words, so write "ওষুধটা" or \
                "কাগজটা", never "একটা ওষুধ"; never write %s. Write a paper's date only whole, as in "%s". When you state a \
                value, write its name as the FACTS write it. Never repeat a number from the QUESTION that the FACTS do not \
                hold.""".formatted(quoted(NUMBER_WORDS_NAMED), dateExample(labels)));
        rules.add("""
                No reassurance: never "normal", "fine", "ok", "all good", "nothing serious", "স্বাভাবিক", "ভালো আছে", "ঠিক আছে", \
                "চিন্তার কিছু নেই". For a value, state only the verdict the FACTS give it, in the FACTS' own words (%s); for \
                "%s", say the paper's range cannot be compared, and never call it inside. Do not diagnose the patient and do \
                not explain what a value means beyond its printed range.""".formatted(quoted(verdicts), labels.unknown()));
        rules.add("Do not add a disclaimer, an emergency line or advice to see a doctor: code appends them.");
        rules.add("At most %d characters. Plain text, no markdown, no headings, no bullet symbols.".formatted(bodyChars));

        StringBuilder system = new StringBuilder("""
                You are Medioo, a record-keeping companion for a family in Bangladesh. You are not a doctor.
                Answer the family's QUESTION about one patient, %1$s, using only the FACTS, which code read from the \
                patient's own stored papers. Code checks your text and refuses it if any rule below is broken.
                How the FACTS are written: each line starts with a tag in capitals (PATIENT, VALUE, PRESCRIPTION, NOT SHOWN), \
                which is for you: never write a tag. A VALUE line is a value's name and reading, then the range printed on \
                its report, then its verdict, then its paper's date, divided by "|". "NOT SHOWN k" means k older readings \
                were left out. Every label in the FACTS is already in %2$s: when you use one, copy it exactly as the FACTS \
                write it.
                """.formatted(patientName, language));
        for (int i = 0; i < rules.size(); i++) {
            system.append(i + 1).append(". ").append(rules.get(i)).append("\n");
        }
        StringBuilder user = new StringBuilder("FACTS:\n");
        user.append(String.join("\n", ctx.factLines())).append("\n\nQUESTION:\n").append(question);
        if (priorViolations != null && !priorViolations.isEmpty()) {
            user.append("\n\nYour previous answer was refused for: ").append(String.join(", ", priorViolations))
                    .append(". Fix exactly those.");
            for (String hint : hints(priorViolations, ctx, labels, patientName, language).values()) {
                user.append("\n").append(hint);
            }
        }
        return new LlmRequest(user.toString(), system.toString(), null, maxTokens, null, null, null, null, false);
    }

    /**
     * The dose rule, for a question code decided asks to change a medicine: only the written line, through the token of the
     * medicine asked about (which n, finding 7), or that the papers do not show it; no literal token when the FACTS hold no
     * line, since copied it could only be an unresolved reference.
     */
    private static String doseRule(ChatContext ctx, ChatLabels labels, String patientName) {
        String answer = ctx.verbatimLines().isEmpty()
                ? "The FACTS hold no prescription line, so say only that the papers do not show it."
                : "Answer it with only the patient's name and the {{rx:n}} token of the medicine it is about, in a sentence of "
                + "its own, as in: " + doseAnswer(labels, patientName, ANY_LINE) + " — " + WHICH_LINE;
        return "This QUESTION asks to change a medicine (code decided so, and code adds the line about asking the doctor after "
                + "your answer). " + answer + " Do not repeat the QUESTION's words about the change, and never write "
                + quoted(DOSE_WORDS_NAMED) + ", not even to say you cannot answer.";
    }

    /** How to fix each violation code of the live test's three findings, once per code, in the order first refused. */
    private static Map<String, String> hints(List<String> violations, ChatContext ctx, ChatLabels labels, String patientName,
                                             String language) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String v : violations) {
            String code = v.contains(":") ? v.substring(0, v.indexOf(':')) : v;
            switch (code) {
                case "number_word" -> out.putIfAbsent(code, "Write every value, printed range and date in digits exactly as "
                        + "the FACTS print it, and leave out every number word, \"একটা\" and \"একটি\" included.");
                case "dose_change" -> out.putIfAbsent(code, "Leave out every word about a dose, a tablet or a change, the "
                        + "QUESTION's own words included; " + (ctx.verbatimLines().isEmpty()
                        ? "the FACTS hold no prescription line, so say only that the papers do not show it."
                        : "for a medicine, give only its token, as in: " + doseAnswer(labels, patientName, ANY_LINE) + " — "
                        + WHICH_LINE));
                case "foreign_label" -> out.putIfAbsent(code, "Write the FACTS' labels exactly as the FACTS write them, in "
                        + language + ", and never a tag.");
                case "medicine_purpose" -> out.putIfAbsent(code, "Leave out every sentence about what a medicine is for, treats, "
                        + "helps with or what kind of medicine it is, a hedged one included (\"usually\", \"may\"); for a medicine, "
                        + "give only its {{rx:n}} token in a sentence of its own: code adds the line about asking the doctor or "
                        + "the pharmacist.");
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

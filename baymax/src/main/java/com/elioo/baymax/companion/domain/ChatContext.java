package com.elioo.baymax.companion.domain;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What one চ্যাট answer may be built from (DR-50 envelope 2), assembled by code from the asked patient's stored papers.
 *
 * @param factLines     the lines the model is given under FACTS, code-built, in order
 * @param numbers       the closed set: every number of a kept value or printed range, and each paper's year, in canonical
 *                      form ({@code AnswerCheck.canonical}); the model's own text may hold no other. A paper's day and month
 *                      are NOT in it: they are accepted only inside a whole date that matches one of {@link #dates}
 *                      (review of #164, findings 4, 11, 17)
 * @param verbatimLines the latest prescription's medicine lines exactly as stored; {@code {{rx:n}}} is line {@code n}
 *                      (from 1), inserted by code, never retyped by the model
 * @param documentIds   every paper a kept line came from
 * @param truncated     whether the token cap dropped a series' newest point too
 * @param facts         every kept reading, its name, unit, numbers and verdict: what a number in a sentence that names a fact
 *                      is bound to, and whether an inside-word is backed by an INSIDE verdict
 * @param dates         every kept paper's date and the prescription's
 * @param medicineNames the prescription's medicine names as words in {@link TextNormaliser}'s form ("demoprol"): a
 *                      medicine reference for the dose-change guard on both sides
 * @param foreignLabels the label phrases an answer to this ask may not hold: for a Bangla ask, the English labels, tags and
 *                      rule words ({@code ChatLabels.foreign}); empty for an English ask
 * @param prescriptionId the paper {@link #verbatimLines} are on, or null with no prescription: the one paper a reply that
 *                      quotes only prescription lines cites (review of #171, finding 9)
 * @param unshownNames  the names of the latest prescription's medicines whose line is never shown, an abbreviated one
 *                      (DR-16) or an empty one, in normal form: still a medicine on the paper, so a question that names one
 *                      is never told the paper does not hold it (review of #171, finding 6)
 * @param verbatimCropKeys the stored crop key of each of {@link #verbatimLines}, by position (DR-12): what an answer that
 *                      quotes line {@code n} is shown beside it (review of #166, finding 5); empty only with no crop known
 */
public record ChatContext(List<String> factLines, Set<String> numbers, List<String> verbatimLines, List<UUID> documentIds,
                          boolean truncated, List<Fact> facts, Set<LocalDate> dates, Set<String> medicineNames,
                          Set<String> foreignLabels, UUID prescriptionId, Set<String> unshownNames, List<String> verbatimCropKeys) {

    /** The canonical verdict that lets an answer say "inside". */
    public static final String INSIDE = "INSIDE";

    /** A form word on a medicine's name, never the medicine itself. */
    private static final Set<String> FORM_WORDS = Set.of("tab", "tabs", "tablet", "tablets", "cap", "caps", "capsule", "capsules", "syp",
            "syrup", "inj", "injection", "drop", "drops", "oint", "ointment", "cream", "gel", "susp", "suspension", "sol", "solution", "inh",
            "inhaler", "spray", "supp", "mg", "mcg", "ml", "gm", "iu", "sr", "xr", "er", "cr", "mr", "ds", "plus", "forte", "extra",
            "ট্যাব", "ট্যাবলেট", "ক্যাপ", "ক্যাপসুল", "সিরাপ", "ইনজেকশন", "ড্রপ", "মলম");

    /**
     * One kept reading.
     *
     * @param name    its printed name
     * @param unit    its unit as stored, possibly empty
     * @param numbers the canonical numbers of its value and printed range
     * @param verdict its canonical verdict ({@code INSIDE}, {@code OUTSIDE high}, {@code UNKNOWN}…), whatever language the
     *                fact line labels it in ({@code ChatLabels})
     */
    public record Fact(String name, String unit, Set<String> numbers, String verdict) {

        public Fact {
            name = name == null ? "" : name;
            unit = unit == null ? "" : unit;
            numbers = Set.copyOf(numbers);
            verdict = verdict == null ? "UNKNOWN" : verdict;
        }

        public boolean inside() {
            return verdict.startsWith(INSIDE);
        }
    }

    public ChatContext {
        factLines = List.copyOf(factLines);
        numbers = Set.copyOf(numbers);
        verbatimLines = List.copyOf(verbatimLines);
        documentIds = List.copyOf(documentIds);
        facts = List.copyOf(facts);
        dates = Set.copyOf(dates);
        medicineNames = Set.copyOf(medicineNames);
        foreignLabels = Set.copyOf(foreignLabels);
        unshownNames = Set.copyOf(unshownNames);
        verbatimCropKeys = verbatimCropKeys == null ? List.of() : List.copyOf(verbatimCropKeys);
        if (!verbatimCropKeys.isEmpty() && (verbatimCropKeys.size() != verbatimLines.size() || prescriptionId == null)) {
            throw new IllegalArgumentException("each verbatim line has its crop key and its paper, or none has");
        }
    }

    /** A context with no crop known for its lines (review of #166, finding 5): what #171's callers and tests build. */
    public ChatContext(List<String> factLines, Set<String> numbers, List<String> verbatimLines, List<UUID> documentIds, boolean truncated,
                       List<Fact> facts, Set<LocalDate> dates, Set<String> medicineNames, Set<String> foreignLabels,
                       UUID prescriptionId, Set<String> unshownNames) {
        this(factLines, numbers, verbatimLines, documentIds, truncated, facts, dates, medicineNames, foreignLabels, prescriptionId,
                unshownNames, List.of());
    }

    /** A context with no prescription paper named and no unshown line: what a test builds by hand. */
    public ChatContext(List<String> factLines, Set<String> numbers, List<String> verbatimLines, List<UUID> documentIds, boolean truncated,
                       List<Fact> facts, Set<LocalDate> dates, Set<String> medicineNames, Set<String> foreignLabels) {
        this(factLines, numbers, verbatimLines, documentIds, truncated, facts, dates, medicineNames, foreignLabels, null, Set.of());
    }

    /** This context with the prescription's paper and its unshown medicines' names; the lines' crop keys are kept. */
    public ChatContext withPrescription(UUID prescription, Set<String> unshown) {
        return new ChatContext(factLines, numbers, verbatimLines, documentIds, truncated, facts, dates, medicineNames, foreignLabels,
                prescription, unshown, verbatimCropKeys);
    }

    /** A context with no foreign labels: what an English ask is built with. */
    public ChatContext(List<String> factLines, Set<String> numbers, List<String> verbatimLines, List<UUID> documentIds, boolean truncated,
                       List<Fact> facts, Set<LocalDate> dates, Set<String> medicineNames) {
        this(factLines, numbers, verbatimLines, documentIds, truncated, facts, dates, medicineNames, Set.of());
    }

    /** A context with no fact records and no dates; the medicine names are read from the lines' name field. */
    public ChatContext(List<String> factLines, Set<String> numbers, List<String> verbatimLines, List<UUID> documentIds, boolean truncated) {
        this(factLines, numbers, verbatimLines, documentIds, truncated, List.of(), Set.of(), namesOfLines(verbatimLines));
    }

    /** Nothing on any paper: every number is refused, and no {@code {{rx:n}}} resolves. */
    public static ChatContext empty() {
        return new ChatContext(List.of(), Set.of(), List.of(), List.of(), false);
    }

    /**
     * The words of a stored medicine name that name the medicine: in normal form, three letters or more, no digit, and not
     * a form word ("Tab. Demoprol 5" is "demoprol").
     */
    public static Set<String> namesIn(String medicineName) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : TextNormaliser.normalise(medicineName).split(" ")) {
            if (w.codePointCount(0, w.length()) >= 3 && w.chars().noneMatch(Character::isDigit) && !FORM_WORDS.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    /** The names of verbatim lines, each read from its first field (a line is the name, then " · " and the rest). */
    static Set<String> namesOfLines(List<String> lines) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : lines) {
            out.addAll(namesOfLine(line));
        }
        return out;
    }

    /** The medicine names of one verbatim line, read from its first field (PO ruling P14: which line a question names). */
    public static Set<String> namesOfLine(String line) {
        int cut = line.indexOf(" · ");
        return namesIn(cut < 0 ? line : line.substring(0, cut));
    }

    /**
     * An over-estimate of a text's tokens, cheap and deterministic: a quarter token per ASCII character, one per other
     * character. Bangla runs well under one token per character, so the cap is conservative; the real count is logged from
     * the provider's usage.
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int ascii = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 128) {
                ascii++;
            } else {
                other++;
            }
        }
        return (int) Math.ceil(ascii / 4.0) + other;
    }
}

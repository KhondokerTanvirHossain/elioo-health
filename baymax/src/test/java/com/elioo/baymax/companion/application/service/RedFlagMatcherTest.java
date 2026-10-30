package com.elioo.baymax.companion.application.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan CHAT-api Task 2: the emergency pre-check (DR-50 envelope 1), in both directions. Every shipped phrase fires in
 * every inflection the templates write (derived from the loaded file, guarded against an empty or shrunken set), the
 * agglutinated Bangla forms a whole-token matcher misses fire too, and the negative set, which a family asks every day,
 * is answered normally.
 */
class RedFlagMatcherTest {

    static final List<String> TEMPLATES = List.of("আম্মার {p}", "{p} হচ্ছে", "{p} হয়ে গেছেন", "amma {p} korche", "my mother has {p}", "{P}!!");

    private static RedFlagMatcher shipped() {
        return new RedFlagMatcher(RedFlagMatcher.load(RedFlagMatcher.RESOURCE));
    }

    @Test
    void everyShippedPhraseFiresInEveryInflection() {
        RedFlagMatcher matcher = shipped();
        Map<String, List<String>> active = matcher.activePhrases();
        assertThat(active).as("active categories loaded from " + RedFlagMatcher.RESOURCE).hasSizeGreaterThanOrEqualTo(13);
        int phrases = active.values().stream().mapToInt(List::size).sum();
        assertThat(phrases).as("active phrases loaded (an empty or shrunken set would pass vacuously)").isGreaterThanOrEqualTo(100);

        List<String> missed = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, List<String>> category : active.entrySet()) {
            for (String phrase : category.getValue()) {
                for (String template : TEMPLATES) {
                    // a row's "anynumber" word stands for a number the family types
                    String written = phrase.replace(RedFlagMatcher.ANY_NUMBER, "10");
                    String question = template.replace("{p}", written).replace("{P}", written.toUpperCase(Locale.ROOT));
                    checked++;
                    Optional<String> got = matcher.match(question);
                    if (!got.equals(Optional.of(category.getKey()))) {
                        missed.add("\"" + question + "\" expected " + category.getKey() + " but was " + got);
                    }
                }
            }
        }
        assertThat(checked).isEqualTo(phrases * TEMPLATES.size());
        assertThat(missed).as("questions that did not fire their own category").isEmpty();
    }

    /** Spec §7.2's examples, and the agglutinated forms (a case ending on the last word) that a whole-token match misses. */
    @Test
    void theInflectedFormsTheWholeTokenMatcherMisses() {
        RedFlagMatcher matcher = shipped();
        assertThat(matcher.match("অজ্ঞান হয়ে গেছেন")).as("অজ্ঞান হয়ে গেছেন").isEqualTo(Optional.of("unresponsive"));
        assertThat(matcher.match("শ্বাস নিতে কষ্ট হচ্ছে")).as("শ্বাস নিতে কষ্ট হচ্ছে").isEqualTo(Optional.of("breathlessness"));
        assertThat(matcher.match("আব্বা শ্বাসকষ্টে ভুগছেন")).as("আব্বা শ্বাসকষ্টে ভুগছেন").isEqualTo(Optional.of("breathlessness"));
        assertThat(matcher.match("আম্মা খিঁচুনিতে কাঁপছেন")).as("আম্মা খিঁচুনিতে কাঁপছেন").isEqualTo(Optional.of("seizure"));
        assertThat(matcher.match("আম্মা বুকে ব্যথায় কাতর")).as("আম্মা বুকে ব্যথায় কাতর").isEqualTo(Optional.of("chest_pain"));
        assertThat(matcher.match("she had two seizures today")).as("she had two seizures today").isEqualTo(Optional.of("seizure"));
    }

    @Test
    void anOverdoseIsAnEmergencyNotADoseQuestion() {
        assertThat(shipped().match("আব্বা ভুল করে প্রেশারের ওষুধ দুটো খেয়ে ফেলেছে")).isEqualTo(Optional.of("overdose"));
    }

    /**
     * Review of #164, finding 13: the document emergency words (urgent, admission, জরুরি, ভর্তি) are routine in a family's
     * question and no longer fire; a real sign beside them still does.
     */
    @Test
    void aDocumentsEmergencyWordIsNotAnEmergencyInAQuestion() {
        RedFlagMatcher matcher = shipped();
        assertThat(matcher.match("is this report urgent?")).as("is this report urgent?").isEmpty();
        assertThat(matcher.match("জরুরি কিছু আছে?")).as("জরুরি কিছু আছে?").isEmpty();
        assertThat(matcher.match("ভর্তির তারিখ")).as("ভর্তির তারিখ").isEmpty();
        assertThat(matcher.match("what was the admission date?")).as("what was the admission date?").isEmpty();
        assertThat(matcher.match("ডাক্তার বলেছেন জরুরি ভর্তি, এখন বুকে ব্যথা")).isEqualTo(Optional.of("chest_pain"));
    }

    /** Review of #164, finding 10: a near row fires in any order within the window, and not beyond it or with a part missing. */
    @Test
    void aNearRowFiresInAnyOrderWithinItsWindowOnly() {
        RedFlagMatcher matcher = shipped();
        assertThat(matcher.match("বুকে খুব ব্যথা হচ্ছে")).isEqualTo(Optional.of("chest_pain"));
        assertThat(matcher.match("pain in my chest")).isEqualTo(Optional.of("chest_pain"));
        assertThat(matcher.match("Ma's sugar is 2.1 and she is shaking")).isEqualTo(Optional.of("hypoglycaemia"));
        assertThat(matcher.match("chest one two three four five six seven eight nine pain")).as("ten words apart").isEmpty();
        assertThat(matcher.match("what does the chest X-ray say?")).as("one part only").isEmpty();
        assertThat(matcher.match("she took 10 tablets")).isEqualTo(Optional.of("overdose"));
        assertThat(matcher.match("she took her tablets this morning")).as("no count").isEmpty();
    }

    @Test
    void theNegativeSetIsAnsweredNormally() {
        RedFlagMatcher matcher = shipped();
        assertThat(matcher.match("বুকের এক্স-রে রিপোর্ট কী বলছে?")).as("a chest X-ray report").isEmpty();
        assertThat(matcher.match("Napa কীসের ওষুধ?")).as("what a medicine is for").isEmpty();
        assertThat(matcher.match("আম্মার ক্রিয়েটিনিন কত?")).as("a value").isEmpty();
        assertThat(matcher.match("ওষুধটা অর্ধেক করব?")).as("a dose question, the guard's job").isEmpty();
        assertThat(matcher.match("ok thanks")).as("thanks").isEmpty();
    }

    /** The two short English words fire as words, never inside another word a family asks about every day. */
    @Test
    void aShortLatinWordFiresOnlyAsAWholeWord() {
        RedFlagMatcher matcher = shipped();
        assertThat(matcher.match("she has fits at night")).isEqualTo(Optional.of("seizure"));
        assertThat(matcher.match("what are the benefits of this medicine")).isEmpty();
        assertThat(matcher.match("he is having a hypo")).isEqualTo(Optional.of("hypoglycaemia"));
        assertThat(matcher.match("আম্মার hypothyroidism রিপোর্ট")).isEmpty();
    }

    @Test
    void aProposedRowNeverFires() {
        Properties rows = new Properties();
        rows.setProperty("test_flag.status", "proposed");
        rows.setProperty("test_flag.source", "test");
        rows.setProperty("test_flag.en.1", "purple elephant");
        assertThat(new RedFlagMatcher(rows).match("I see a purple elephant")).isEmpty();

        rows.setProperty("test_flag.status", "active");
        assertThat(new RedFlagMatcher(rows).match("I see a purple elephant")).isEqualTo(Optional.of("test_flag"));
    }
}

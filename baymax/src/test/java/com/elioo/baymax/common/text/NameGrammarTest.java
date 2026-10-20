package com.elioo.baymax.common.text;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §2 F8: a vowel or vowel sign takes র, a consonant takes এর (as ের), Latin keeps a hyphen. Both kinds of ending
 * are asserted, so an implementation that always writes one of them cannot pass.
 */
class NameGrammarTest {

    @ParameterizedTest
    @CsvSource({
            "আম্মা,আম্মার", "আব্বু,আব্বুর", "নানু,নানুর", "মাতৃ,মাতৃর", "খালা,খালার",    // vowel signs
            "রাবেয়া,রাবেয়ার", "দাদি,দাদির", "অ,অর",                                // vowel sign after য়; independent vowel
            "রহিম,রহিমের", "শরৎ,শরতের", "জন্,জনের",                              // consonant, khanda ta, hasanta
            "পাহাড়,পাহাড়ের", "জ\u09DF,জ\u09DFের", "জয়,জয়ের",                   // ড়; য় precomposed (U+09DF) and with nukta
            "Rina,Rina-র", "John,John-এর", "Rokeya,Rokeya-র",                       // Latin keeps a hyphen
            "রং,রং-এর", "নাম ২,নাম ২-এর", "'  আম্মা  ',আম্মার"})                   // anything unusual: the plain hyphen form
    void thePossessiveFollowsTheLastLetter(String name, String expected) {
        assertThat(NameGrammar.possessive(name)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL", "''", "'   '"}, nullValues = "NULL")
    void nothingInNothingOut(String name) {
        assertThat(NameGrammar.possessive(name)).isEmpty();
        assertThat(NameGrammar.objective(name)).isEmpty();
    }

    /** F8: "কে" always attaches to a Bangla name, whatever its last letter; a Latin name keeps a hyphen. */
    @ParameterizedTest
    @CsvSource({"আম্মা,আম্মাকে", "রহিম,রহিমকে", "শরৎ,শরৎকে", "Rina,Rina-কে", "John,John-কে", "নাম ২,নাম ২-কে", "'  আব্বু ',আব্বুকে"})
    void theObjectiveAttachesKeToABanglaNameAndHyphensAnyOther(String name, String expected) {
        assertThat(NameGrammar.objective(name)).isEqualTo(expected);
    }
}

package com.elioo.baymax.companion.application.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review of #171, finding 6: a stored name typed in Bangla script or a vowel off is the stored name, both directions. The
 * fictional medicines only; "Napa" and "Paracetamol" as a family would type them.
 */
class NameSkeletonTest {

    @Test
    void aStoredNameInBanglaScriptOrMisspeltIsTheStoredName() {
        assertThat(NameSkeleton.of("ডেমোপ্রল")).isEqualTo(NameSkeleton.of("demoprol")).isEqualTo("dmprl");
        assertThat(NameSkeleton.of("ক্যালমোরা")).isEqualTo(NameSkeleton.of("Calmora"));
        assertThat(NameSkeleton.of("প্যারাসিটামল")).isEqualTo(NameSkeleton.of("paracetamol"));
        assertThat(NameSkeleton.matches("Demopral", Set.of("demoprol"))).isTrue();
        assertThat(NameSkeleton.matches("ডেমোপ্রল", Set.of("demoprol"))).isTrue();
        assertThat(NameSkeleton.anyWord(" ডেমোপ্রল কীসের ওষুধ ", Set.of("demoprol", "calmora"))).isTrue();
    }

    @Test
    void anotherNameOrAShortWordIsNot() {
        assertThat(NameSkeleton.matches("Calmora", Set.of("demoprol"))).isFalse();
        assertThat(NameSkeleton.matches("ক্রিয়েটিনিন", Set.of("demoprol", "calmora"))).isFalse();
        // two consonants are too few to say a name was meant: "নাপা" is never matched by skeleton
        assertThat(NameSkeleton.matches("নাপা", Set.of("napa"))).isFalse();
        assertThat(NameSkeleton.matches("", Set.of("demoprol"))).isFalse();
        assertThat(NameSkeleton.anyWord(" আম্মার প্রেশার কত ", Set.of("demoprol", "calmora"))).isFalse();
    }
}

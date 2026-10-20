package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §7.4 glossary, over every bundle part. The patient by name, never "রোগী"; কাগজ, never "নথি"; "রিপোর্টে লেখা
 * সীমা", never "স্বাভাবিক সীমা"; the tab is "কাগজপত্র"; never "বন্ধ করুন" (a forbidden phrase,
 * BaymaxProperties.Outbound#forbiddenPhrases). A ratchet: KNOWN listed what main had on 2026-10-19, only shrank, and D2
 * emptied it. A name is never glued to a case ending in a bundle: the possessive is built in code (NameGrammar, F8).
 */
class GlossaryTest {

    static final List<String> WEB_RETIRED = List.of("রোগী", "নথি", "ছাপা সীমা", "স্বাভাবিক সীমা", "তুলনা নেই",
            "টাইমলাইন", "বন্ধ করুন", "চিন্তার কিছু নেই", "যা চোখে পড়ার মতো");
    /** A placeholder glued to a case ending means the bundle does name grammar; F8 does it in code (D2). */
    static final Pattern GLUED_CASE = Pattern.compile("\\{\\d+}-?(এর|র|কে)(?![\\u0980-\\u09FF])");
    static final List<String> MESSAGE_RANGE_WORDS = List.of("স্বাভাবিক সীমা", "ছাপা সীমা", "তুলনা নেই");
    /** messages_bn exemptions, each with its owner: the D35 claim wording is the PO's (Phase 0 PR 2). */
    static final Map<String, String> MESSAGE_EXEMPT = Map.of("explanation.routine", "স্বাভাবিক সীমা");

    /**
     * "<lang-part>:<key> <word>" for every violation main still has. Emptied by D2 (2026-10-20): a new violation is red
     * at once, and nothing may be added here again — fix the copy instead.
     */
    static final Set<String> KNOWN = Set.of();

    @Test
    void noBundleStringUsesARetiredTermExceptTheKnownOnesAndTheKnownOnesStillDo() throws Exception {
        Set<String> found = new TreeSet<>();
        int keys = 0;
        for (String part : UiCopy.PARTS) {
            for (Map.Entry<String, String> e : BundleFiles.values(part, Lang.BN).entrySet()) {
                keys++;
                for (String w : WEB_RETIRED) {
                    if (e.getValue().contains(w)) {
                        found.add(part + ":" + e.getKey() + " " + w);
                    }
                }
                if (GLUED_CASE.matcher(e.getValue()).find()) {
                    found.add(part + ":" + e.getKey() + " glued-case");
                }
            }
        }
        for (Map.Entry<String, String> e : BundleFiles.messages(Lang.BN).entrySet()) {
            keys++;
            for (String w : MESSAGE_RANGE_WORDS) {
                if (e.getValue().contains(w) && !w.equals(MESSAGE_EXEMPT.get(e.getKey()))) {
                    found.add("messages:" + e.getKey() + " " + w);
                }
            }
        }
        assertThat(keys).as("keys read; an empty read would pass vacuously").isGreaterThan(200);
        Set<String> fresh = new TreeSet<>(found);
        fresh.removeAll(KNOWN);
        Set<String> healed = new TreeSet<>(KNOWN);
        healed.removeAll(found);
        assertThat(fresh).as("new glossary violations").isEmpty();
        assertThat(healed).as("fixed, so remove from KNOWN (the ratchet only shrinks)").isEmpty();
        MESSAGE_EXEMPT.forEach((k, w) -> {
            try {
                assertThat(BundleFiles.messages(Lang.BN).get(k)).as("exemption " + k + " still needed").contains(w);
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        });
    }
}

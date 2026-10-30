package com.elioo.baymax.companion.application.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-50: the roadmap's D11 item 1 ships as rows. Every one of the thirteen categories is active, names its source, and
 * carries phrases in all three ways a family writes: Bangla script, Banglish and English. The script of each phrase is
 * read from its characters, never from its position or its key alone: a {@code bn} row must be Bangla letters, a
 * {@code banglish} or {@code en} row Latin letters.
 */
class ShippedChatRedFlagsTest {

    static final List<String> D11 = List.of("chest_pain", "breathlessness", "unresponsive", "stroke", "seizure", "bleeding",
            "headache_sudden", "head_injury", "fever_confusion", "anaphylaxis", "hypoglycaemia", "overdose", "self_harm");

    @Test
    void everyD11CategoryHasAnActiveRowWithASource() {
        Properties rows = RedFlagMatcher.load(RedFlagMatcher.RESOURCE);
        Map<String, RedFlagMatcher.Category> categories = RedFlagMatcher.categories(rows);
        assertThat(categories.keySet()).as("the shipped categories").containsAll(D11);

        List<String> wrong = new ArrayList<>();
        for (String name : D11) {
            RedFlagMatcher.Category c = categories.get(name);
            if (!c.active()) {
                wrong.add(name + ": not active");
            }
            if (c.source() == null || c.source().isBlank()) {
                wrong.add(name + ": no source");
            }
            int bangla = 0;
            int banglish = 0;
            int english = 0;
            for (RedFlagMatcher.Phrase p : c.phrases()) {
                String script = scriptOf(p.text());
                switch (p.script()) {
                    case "bn" -> {
                        if ("bangla".equals(script)) bangla++; else wrong.add(name + ": bn row is " + script + ": " + p.text());
                    }
                    case "banglish" -> {
                        if ("latin".equals(script)) banglish++; else wrong.add(name + ": banglish row is " + script + ": " + p.text());
                    }
                    case "en" -> {
                        if ("latin".equals(script)) english++; else wrong.add(name + ": en row is " + script + ": " + p.text());
                    }
                    default -> wrong.add(name + ": unknown script key " + p.script());
                }
            }
            if (bangla == 0) wrong.add(name + ": no Bangla-script phrase");
            if (banglish == 0) wrong.add(name + ": no Banglish phrase");
            if (english == 0) wrong.add(name + ": no English phrase");
        }
        assertThat(wrong).isEmpty();
    }

    /**
     * Review of #164 (findings 2, 10, 13): the recall corpus, sentences written from how families type an emergency and a
     * routine question, never derived from the phrase file, so a phrase the file lacks is a named failure here. Both
     * directions, at least twenty rows per script, every row reported by itself.
     */
    @Test
    void aFamilysEmergencySentencesFireAndItsRoutineQuestionsDoNot() throws IOException {
        RedFlagMatcher matcher = new RedFlagMatcher(RedFlagMatcher.load(RedFlagMatcher.RESOURCE));
        Map<String, Integer> perScript = new LinkedHashMap<>();
        Map<String, Integer> perGroup = new LinkedHashMap<>();
        List<String> wrong = new ArrayList<>();
        try (InputStream in = ShippedChatRedFlagsTest.class.getResourceAsStream("/chat/red-flag-corpus.tsv")) {
            assertThat(in).as("the red-flag corpus").isNotNull();
            String[] lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n");
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i];
                if (l.isBlank() || l.startsWith("#")) {
                    continue;
                }
                String[] cols = l.split("\t", 3);
                assertThat(cols).as("line " + (i + 1) + " has three tab-separated columns").hasSize(3);
                perScript.merge(cols[0], 1, Integer::sum);
                perGroup.merge(cols[0] + "/" + cols[1], 1, Integer::sum);
                Optional<String> got = matcher.match(cols[2]);
                if ("flag".equals(cols[1]) && got.isEmpty()) {
                    wrong.add("row " + (i + 1) + ": expected an emergency (" + cols[0] + "): " + cols[2]);
                } else if ("pass".equals(cols[1]) && got.isPresent()) {
                    wrong.add("row " + (i + 1) + ": expected no emergency (" + cols[0] + "), got " + got.get() + ": " + cols[2]);
                }
            }
        }
        assertThat(perScript.keySet()).containsExactlyInAnyOrder("bn", "banglish", "en");
        perScript.forEach((script, n) -> assertThat(n).as("rows in " + script).isGreaterThanOrEqualTo(20));
        assertThat(perGroup.keySet()).as("both directions in every script").hasSize(6);
        assertThat(wrong).isEmpty();
    }

    /** "bangla" when every letter is in the Bengali block, "latin" when every letter is ASCII, else "mixed". */
    static String scriptOf(String text) {
        boolean bangla = false;
        boolean latin = false;
        for (int cp : text.codePoints().toArray()) {
            if (!Character.isLetter(cp) && Character.getType(cp) != Character.NON_SPACING_MARK
                    && Character.getType(cp) != Character.COMBINING_SPACING_MARK) {
                continue;
            }
            if (Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.BENGALI) {
                bangla = true;
            } else if (cp < 128) {
                latin = true;
            } else {
                return "mixed";
            }
        }
        return bangla && !latin ? "bangla" : latin && !bangla ? "latin" : "mixed";
    }
}

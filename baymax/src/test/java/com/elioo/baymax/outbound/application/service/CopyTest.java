package com.elioo.baymax.outbound.application.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The copy file loads as UTF-8, every template fills, and none of the four drafts exceeds the cap. */
class CopyTest {

    private final Copy copy = new Copy();

    @Test
    void everyDraftFillsAndFitsTheCap() {
        Map<String, String> vars = Map.of("document_type", "ল্যাব রিপোর্ট", "date", "2026-09-14", "patient", "মা",
                "link", "https://baymax.eliooo.org/baymax/documents/x", "standout", "HbA1c এসেছে 9.8 %, স্বাভাবিক সীমা 4.0–5.6।");
        for (String key : List.of("explanation.routine", "explanation.this_week", "explanation.now", "retake")) {
            String text = copy.bn(key, vars);
            assertThat(text).as(key).doesNotContain("{").doesNotContain("\\n").contains("\n");
            assertThat(text.length()).as(key).isLessThanOrEqualTo(600);
            assertThat(copy.en(key, vars)).as("en mirror " + key).doesNotContain("{");
        }
        assertThat(copy.documentType("prescription")).isEqualTo("প্রেসক্রিপশন");
        assertThat(copy.documentType("something_new")).isEqualTo("রিপোর্ট");
    }
}

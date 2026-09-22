package com.elioo.baymax.outbound.application.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-26 and the overflow path. The 600-character cap was set for single-bubble readability; WhatsApp allows
 * 4,096. It must never be the reason a family loses their medicine instructions, so a body that will not fit
 * becomes two messages — never a truncated one, and never a dropped medicine.
 *
 * <p>Written before the implementation, because this rule has two directions: fit in one message where it
 * fits, and lose nothing where it does not.
 */
class MessageSplitTest {

    private static final int CAP = 600;
    private static final String MED_HEADER = "প্রেসক্রিপশনে যা লেখা আছে:";

    private static Map<String, Object> medicine(String name, String dose, String freq, String timing, String duration) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("dose_text", dose);
        m.put("frequency_text", freq);
        m.put("timing_text", timing);
        m.put("duration_text", duration);
        return m;
    }

    private static Map<String, Object> wholeLine(String name, String instruction) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("instruction_text", instruction);
        return m;
    }

    /** Document 08c02570 as it actually is: four medicines and three diagnosis lines. */
    @Test
    void theProductionDocumentStillFitsInOneMessage() {
        String summary = "রিপোর্টটি পেয়েছি — প্রেসক্রিপশন, ১৯ সেপ্টেম্বর।\n"
                + "Test Patient-এর টাইমলাইনে রেখে দিলাম: https://medioo.eliooo.org/app/documents/08c02570\n"
                + "কাগজে যা লেখা আছে নিচে তুলে দিলাম।\nবিস্তারিত জানতে চাইলে লিখুন \"বিস্তারিত\"।";
        String diagnosis = "ডাক্তার লিখেছেন:\nHTN (known)\nHTN + DM (known)\nParkinson Disease";
        List<Map<String, Object>> medicines = List.of(
                medicine("Cap. DDR", "30 mg", "১+০+০", "খাবারের আগে", null),
                medicine("Cap. Ginoba", "60 mg", "১+০+১", "সকাল-রাত", null),
                medicine("Tab. Rosuva", "5 mg", "০+০+১", "খাবারের পরে", null),
                medicine("Tab. Terbicon", "250 mg", "১+০+০", "সকাল", null));

        List<String> parts = MessageSplit.split(summary, diagnosis, MED_HEADER, medicines, CAP);

        assertThat(parts).as("this document fits, so it stays one message").hasSize(1);
        assertThat(parts.get(0).length()).isLessThanOrEqualTo(CAP);
        assertThat(parts.get(0)).contains("Parkinson Disease").contains("Tab. Terbicon");
    }

    /**
     * Seven medicines including the real two-phase DDR line. Every line must survive, verbatim, across however
     * many messages it takes.
     */
    @Test
    void aSevenMedicinePrescriptionBecomesTwoMessagesAndLosesNothing() {
        String summary = "রিপোর্টটি পেয়েছি — প্রেসক্রিপশন, ১৯ সেপ্টেম্বর।\n"
                + "Test Patient-এর টাইমলাইনে রেখে দিলাম: https://medioo.eliooo.org/app/documents/08c02570\n"
                + "কাগজে যা লেখা আছে নিচে তুলে দিলাম।\nবিস্তারিত জানতে চাইলে লিখুন \"বিস্তারিত\"।";
        String diagnosis = "ডাক্তার লিখেছেন:\nHTN (known)\nHTN + DM (known)\nParkinson Disease";
        String ddr = "Cap. DDR · 30mg · ১+০+০ - খাবারের আগে - ৩০ দিন। তারপর ১+০+১ - খাবারের আগে - ২ সপ্তাহ";
        List<Map<String, Object>> medicines = List.of(
                wholeLine("Cap. DDR", ddr),
                medicine("Cap. Ginoba", "60 mg", "১+০+১", "সকাল-রাত", "১ মাস"),
                medicine("Tab. Rosuva", "5 mg", "০+০+১", "খাবারের পরে", "৩ মাস"),
                medicine("Tab. Terbicon", "250 mg", "১+০+০", "সকাল", "৭ দিন"),
                medicine("Tab. Losartan", "50 mg", "১+০+০", "সকাল", "চলবে"),
                medicine("Tab. Metformin", "500 mg", "১+০+১", "খাবারের পরে", "চলবে"),
                medicine("Cap. Omeprazole", "20 mg", "১+০+০", "খাবারের আগে", "১৪ দিন"));

        List<String> parts = MessageSplit.split(summary, diagnosis, MED_HEADER, medicines, CAP);

        assertThat(parts).as("it does not fit, so it becomes more than one message").hasSizeGreaterThan(1);
        assertThat(parts).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(CAP));

        String everything = String.join("\n", parts);
        assertThat(everything).as("the two-phase line survives whole, with its sequence word")
                .contains(ddr).contains("তারপর");
        for (Map<String, Object> m : medicines) {
            String line = MedicineTranscription.line(m);
            assertThat(everything).as("every medicine line is present verbatim: " + line).contains(line);
        }
        assertThat(everything).doesNotContain("...").doesNotContain("…");
        assertThat(parts.get(0)).as("the diagnosis travels with the summary, not the medicines")
                .contains("Parkinson Disease");
    }

    /** A single line longer than the room left splits at the medicine boundary, never mid-line. */
    @Test
    void aLineTooLongForTheRemainingRoomMovesWholeToTheNextMessage() {
        String summary = "স".repeat(400);
        String longLine = "Cap. Something · 500 mg · ১+০+০ - খাবারের আগে - ৩০ দিন। তারপর ১+০+১ - রাতে - ২ সপ্তাহ";
        List<Map<String, Object>> medicines = List.of(
                medicine("Tab. Short", "5 mg", "০+০+১", "রাতে", null),
                wholeLine("Cap. Something", longLine));

        List<String> parts = MessageSplit.split(summary, "", MED_HEADER, medicines, CAP);

        assertThat(parts).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(CAP));
        assertThat(String.join("\n", parts)).contains(longLine);
        assertThat(parts).as("the long line is whole in exactly one message")
                .filteredOn(p -> p.contains(longLine)).hasSize(1);
    }

    /**
     * INTERIM behaviour until the multi-message contract exists: an overflowing body is sent as ONE message,
     * never failed closed on length alone. A long bubble costs readability; a failed message costs the family
     * their dosing instructions. Asserted here on the assembled body so the trade is explicit and tested — the
     * split exists and is proven above, but nothing may drop a medicine while the pairing contract is missing.
     */
    @Test
    void whenOverflowCannotYetBeSplitTheWholeBodyIsStillProducedIntact() {
        String summary = "রিপোর্টটি পেয়েছি — প্রেসক্রিপশন, ১৯ সেপ্টেম্বর।\n"
                + "Test Patient-এর টাইমলাইনে রেখে দিলাম: https://medioo.eliooo.org/app/documents/08c02570\n"
                + "কাগজে যা লেখা আছে নিচে তুলে দিলাম।\nবিস্তারিত জানতে চাইলে লিখুন \"বিস্তারিত\"।";
        String diagnosis = "ডাক্তার লিখেছেন:\nHTN (known)\nHTN + DM (known)\nParkinson Disease";
        String ddr = "Cap. DDR · 30mg · ১+০+০ - খাবারের আগে - ৩০ দিন। তারপর ১+০+১ - খাবারের আগে - ২ সপ্তাহ";
        List<Map<String, Object>> medicines = List.of(
                wholeLine("Cap. DDR", ddr),
                medicine("Cap. Ginoba", "60 mg", "১+০+১", "সকাল-রাত", "১ মাস"),
                medicine("Tab. Rosuva", "5 mg", "০+০+১", "খাবারের পরে", "৩ মাস"),
                medicine("Tab. Terbicon", "250 mg", "১+০+০", "সকাল", "৭ দিন"),
                medicine("Tab. Losartan", "50 mg", "১+০+০", "সকাল", "চলবে"),
                medicine("Tab. Metformin", "500 mg", "১+০+১", "খাবারের পরে", "চলবে"),
                medicine("Cap. Omeprazole", "20 mg", "১+০+০", "খাবারের আগে", "১৪ দিন"));

        // the single assembled body, as ExplanationService builds it today
        String whole = summary + "\n\n" + diagnosis + "\n\n"
                + MedicineTranscription.block(MED_HEADER, medicines);

        assertThat(whole.length()).as("this fixture really does overflow, or the test proves nothing")
                .isGreaterThan(CAP);
        for (Map<String, Object> m : medicines) {
            assertThat(whole).as("no medicine is dropped when the body overflows")
                    .contains(MedicineTranscription.line(m));
        }
        assertThat(whole).contains(ddr).contains("তারপর").contains("Parkinson Disease");
        assertThat(whole).doesNotContain("...").doesNotContain("…");
    }

    /** The cap applies to what is actually sent — the assembled body, not an intermediate. */
    @Test
    void everyPartIsWithinTheCapIncludingMedicinesAndDiagnosis() {
        List<Map<String, Object>> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(medicine("Tab. Medicine" + i, "500 mg", "১+০+১", "খাবারের পরে", "৩০ দিন"));
        }
        List<String> parts = MessageSplit.split("সংক্ষেপে", "ডাক্তার লিখেছেন:\nHTN", MED_HEADER, many, CAP);

        assertThat(parts).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(CAP));
        String everything = String.join("\n", parts);
        for (Map<String, Object> m : many) {
            assertThat(everything).contains(MedicineTranscription.line(m));
        }
    }
}

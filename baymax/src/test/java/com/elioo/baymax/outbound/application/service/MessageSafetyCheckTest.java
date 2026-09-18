package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.outbound.domain.Urgency;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageSafetyCheckTest {

    private final MessageSafetyCheck check = new MessageSafetyCheck(new BaymaxProperties());
    private final DocumentFacts facts = UrgencyServiceTest.facts(
            List.of(UrgencyServiceTest.value("HbA1c", "9.8", "4.0", "5.6")), List.of(), Map.of(), "{}");

    @Test
    void aNumberAbsentFromTheExtractionIsAViolation() {
        assertThat(check.violations("HbA1c এসেছে 9.8, স্বাভাবিক 4.0–5.6। ডাক্তার দেখান।", facts, Urgency.THIS_WEEK)).isEmpty();
        assertThat(check.violations("HbA1c এসেছে 12.3। ডাক্তার দেখান।", facts, Urgency.THIS_WEEK)).contains("number_not_in_extraction:12", "number_not_in_extraction:3");
        assertThat(check.violations("HbA1c এসেছে ৯.৮। ডাক্তার দেখান।", facts, Urgency.THIS_WEEK)).isEmpty();   // Bangla digits fold
    }

    @Test
    void medicineOrDoseAdviceIsAViolationButDoctorDekhanIsNot() {
        assertThat(check.violations("ওষুধ খান দিনে দুবার। ডাক্তার দেখান।", facts, Urgency.ROUTINE)).contains("forbidden_phrase:ওষুধ খান");
        assertThat(check.violations("Stop taking the tablet. ডাক্তার দেখান।", facts, Urgency.ROUTINE))
                .contains("forbidden_phrase:stop taking", "forbidden_phrase:tablet");
        // দেখান contains খান as a substring; whole-token matching must not trip on it
        assertThat(check.violations("এ সপ্তাহের মধ্যে একজন ডাক্তার দেখান।", facts, Urgency.THIS_WEEK)).isEmpty();
    }

    @Test
    void urgentMessagesMustBeDoctorFirst() {
        assertThat(check.violations("সব ঠিক আছে।", facts, Urgency.ROUTINE)).isEmpty();
        assertThat(check.violations("সব ঠিক আছে।", facts, Urgency.NOW)).contains("doctor_first_missing");
        assertThat(check.violations("এখনই ডাক্তারের কাছে যান।", facts, Urgency.NOW)).isEmpty();
    }

    /** Acceptance: any message ≤ 600 characters. */
    @Test
    void longerThanSixHundredIsAViolation() {
        assertThat(check.violations("ক".repeat(601), facts, Urgency.ROUTINE)).anyMatch(v -> v.startsWith("too_long"));
        assertThat(check.violations("ক".repeat(600), facts, Urgency.ROUTINE)).isEmpty();
    }

    /** The link's UUID must neither be counted as body numbers nor widen the allowed set (a random id can contain "42"). */
    @Test
    void theLinkIsNeitherANumberInTheBodyNorAnAllowedNumber() {
        DocumentFacts base = UrgencyServiceTest.facts(List.of(UrgencyServiceTest.value("HbA1c", "9.8", "4.0", "5.6")), List.of(), Map.of(), "{}");
        DocumentFacts f = new DocumentFacts(base.document(), base.patientName(), base.values(), base.followUps(), base.clinicalContext(),
                "https://x/app/documents/7a42f-7777");
        assertThat(check.violations("HbA1c 9.8। টাইমলাইন: " + f.link() + " ডাক্তার", f, Urgency.THIS_WEEK)).isEmpty();
        assertThat(f.numbers()).doesNotContain("42").doesNotContain("7777");
        assertThat(check.violations("HbA1c 9.8 আর 42 দিন। ডাক্তার", f, Urgency.THIS_WEEK)).contains("number_not_in_extraction:42");
    }
}

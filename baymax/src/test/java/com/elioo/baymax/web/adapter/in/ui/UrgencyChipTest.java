package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.domain.TimelineEntry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Kit §6: NOW and THIS_WEEK only, the bundle's short form verbatim; ROUTINE and unassessed carry none of the lines. */
class UrgencyChipTest {

    private final UiCopy copy = new UiCopy();

    private static TimelineEntry entry(Urgency level) {
        return new TimelineEntry(UUID.randomUUID(), LocalDate.parse("2026-10-12"), false, "lab_report", "Shapla Diagnostics", "DONE",
                14, 0, 0, 0, "k", Instant.parse("2026-10-12T08:00:00Z"), level, 0, null, null);
    }

    @ParameterizedTest
    @EnumSource(value = Urgency.class, names = {"NOW", "THIS_WEEK"})
    void aChipSaysTheBundlesShortFormVerbatim(Urgency level) throws Exception {
        String chip = UrgencyChip.html(copy, Lang.BN, entry(level));
        // read from the file WhatsApp uses, not through UiCopy: a chip that retyped its words would differ from the bundle
        String shortForm = BundleFiles.messages(Lang.BN).get("urgency." + level.dbValue() + ".short");
        assertThat(shortForm).as("the short form is in messages_bn").isNotBlank();
        assertThat(chip).contains(Html.esc(shortForm)).contains(copy.urgency(Lang.BN, level, true))
                .contains("class=\"chip " + (level == Urgency.NOW ? "now" : "soon") + "\"");
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(value = Urgency.class, names = "ROUTINE")
    void routineAndUnassessedCarryNoneOfTheUrgencyLines(Urgency level) throws Exception {
        String chip = UrgencyChip.html(copy, Lang.BN, entry(level));
        List<String> lines = BundleFiles.messages(Lang.BN).entrySet().stream().filter(e -> e.getKey().startsWith("urgency."))
                .map(e -> Html.esc(e.getValue())).toList();
        assertThat(lines).as("every urgency line, long and short").hasSizeGreaterThanOrEqualTo(4);
        assertThat(lines).allSatisfy(l -> assertThat(chip).doesNotContain(l));
        assertThat(chip).isEmpty();
    }
}

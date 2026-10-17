package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.outbound.domain.Urgency;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The one urgency rule (Phase 0, D5): NOW / THIS_WEEK carry their line then the red-flag line; ROUTINE nothing. */
class UrgencyLinesTest {

    private final Copy copy = new Copy();

    @Test
    void nowAndThisWeekCarryTheirLineThenTheRedFlagLineAndRoutineCarriesNothing() {
        assertThat(UrgencyLines.block(copy, Urgency.NOW))
                .containsExactly(copy.bn("urgency.now", Map.of()), copy.bn("safety.red_flag_line", Map.of()));
        assertThat(UrgencyLines.block(copy, Urgency.THIS_WEEK))
                .containsExactly(copy.bn("urgency.this_week", Map.of()), copy.bn("safety.red_flag_line", Map.of()));
        assertThat(UrgencyLines.block(copy, Urgency.ROUTINE)).isEmpty();
    }

    /** D5: the urgency is never behind what it qualifies. */
    @Test
    void leadPutsTheBlockFirst() {
        assertThat(UrgencyLines.lead(copy, Urgency.NOW, "body")).isEqualTo(String.join("\n",
                copy.bn("urgency.now", Map.of()), copy.bn("safety.red_flag_line", Map.of()), "body"));
        assertThat(UrgencyLines.lead(copy, Urgency.ROUTINE, "body")).isEqualTo("body");
    }
}

package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.outbound.domain.Urgency;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Principle 3: urgency appears only as the fixed strings, and the web says exactly what WhatsApp says. */
class UrgencyCopyTest {

    private final UiCopy ui = new UiCopy();
    private final Copy messages = new Copy();

    @Test
    void theUrgencyLineIsTheMessagesBundleLineVerbatimInBothLanguages() {
        for (Urgency level : List.of(Urgency.THIS_WEEK, Urgency.NOW)) {
            assertThat(ui.urgency(Lang.BN, level, false)).isEqualTo(Html.esc(messages.bn("urgency." + level.dbValue(), Map.of())));
            assertThat(ui.urgency(Lang.EN, level, false)).isEqualTo(Html.esc(messages.en("urgency." + level.dbValue(), Map.of())));
        }
    }

    /** D27: ROUTINE has no line; asking for one is a programming error, so no renderer can draw a ROUTINE chip or banner. */
    @Test
    void routineAndNullHaveNoLine() {
        assertThatThrownBy(() -> ui.urgency(Lang.BN, Urgency.ROUTINE, false)).hasMessageContaining("ROUTINE has no urgency line");
        assertThatThrownBy(() -> ui.urgency(Lang.BN, null, false)).hasMessageContaining("ROUTINE has no urgency line");
    }

    /** One source: no ui bundle part defines an urgency.* key a page could read instead. */
    @Test
    void noUiBundlePartDefinesAnUrgencyKey() {
        assertThat(ui.keys(Lang.BN)).hasSizeGreaterThan(150).noneMatch(k -> k.startsWith("urgency."));
        assertThat(ui.keys(Lang.EN)).hasSizeGreaterThan(150).noneMatch(k -> k.startsWith("urgency."));
    }
}

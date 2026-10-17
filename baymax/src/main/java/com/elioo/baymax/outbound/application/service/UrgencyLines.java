package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.outbound.domain.Urgency;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one place an urgency level becomes words (Phase 0, D5). NOW and THIS_WEEK: their fixed line, then the approved
 * red-flag line; ROUTINE: nothing. Callers put the block before anything it qualifies. D11's signed emergency
 * template replaces the red-flag line here, once, in Phase 3.
 */
public final class UrgencyLines {

    private UrgencyLines() {
    }

    public static List<String> block(Copy copy, Urgency level) {
        return switch (level) {
            case NOW -> List.of(copy.bn("urgency.now", Map.of()), copy.bn("safety.red_flag_line", Map.of()));
            case THIS_WEEK -> List.of(copy.bn("urgency.this_week", Map.of()), copy.bn("safety.red_flag_line", Map.of()));
            case ROUTINE -> List.of();
        };
    }

    public static String lead(Copy copy, Urgency level, String... lines) {
        List<String> all = new ArrayList<>(block(copy, level));
        all.addAll(List.of(lines));
        return String.join("\n", all);
    }
}

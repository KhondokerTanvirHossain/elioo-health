package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.domain.TimelineEntry;

/**
 * Kit §6: an icon and the bundle's short form for NOW and THIS_WEEK; nothing at all otherwise (D27). Whether a paper has
 * a chip is {@link TimelineEntry#chip()}'s rule, stated once there; the words are {@link UiCopy#urgency}'s, read from the
 * file WhatsApp uses. A {@code <span>}, not a control.
 */
final class UrgencyChip {

    private UrgencyChip() {
    }

    static String html(UiCopy copy, Lang lang, TimelineEntry e) {
        return e.chip().map(level -> of(copy, lang, level)).orElse("");
    }

    /** The chip of a level that has one (NOW, THIS_WEEK): the চ্যাট strip's urgent cards (C4) carry only those. */
    static String of(UiCopy copy, Lang lang, Urgency level) {
        boolean now = level == Urgency.NOW;
        return "<span class=\"chip " + (now ? "now" : "soon") + "\">" + Icons.svg(now ? "octagon" : "calendar", 18)
                + copy.urgency(lang, level, true) + "</span>";
    }
}

package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.web.domain.TimelineEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * The one line under each paper on the timeline (spec §4.15), built in code from the stored counts — never a model
 * summary. A lab or imaging paper counts its values and, only when there are any, how many are outside their range
 * (nothing outside says nothing about the range: principle 10); a prescription counts its medicines; any other paper
 * counts what it has. An undated paper says its date could not be read. Counts pass through {@link UiCopy#t}, so a
 * Bangla page writes them in Bangla digits; the result is HTML-escaped.
 */
final class TimelineSummary {

    private TimelineSummary() {
    }

    static String line(UiCopy copy, Lang lang, TimelineEntry e) {
        List<String> parts = new ArrayList<>();
        String type = e.documentType() == null ? "" : e.documentType();
        switch (type) {
            case "lab_report", "imaging_report" -> parts.add(values(copy, lang, e));
            case "prescription" -> parts.add(copy.t(lang, "timeline.sum.medicines", e.medicines()));
            default -> {
                if (e.values() > 0) {
                    parts.add(values(copy, lang, e));
                }
                if (e.medicines() > 0) {
                    parts.add(copy.t(lang, "timeline.sum.medicines", e.medicines()));
                }
            }
        }
        if (e.dateIsFallback()) {
            parts.add(copy.t(lang, "timeline.sum.nodate"));
        }
        return String.join(" · ", parts);
    }

    private static String values(UiCopy copy, Lang lang, TimelineEntry e) {
        String values = copy.t(lang, "timeline.sum.values", e.values());
        return e.outside() > 0 ? values + ", " + copy.t(lang, "timeline.sum.outside", e.outside()) : values;
    }
}

package com.elioo.baymax.web.domain;

import java.time.YearMonth;
import java.util.List;

/**
 * Plan B3, the স্বাস্থ্য calendar: one month of one patient, on the patient's calendar. {@code papers} are the papers filed
 * inside it (D2's filing day), in the timeline's order; {@code followUps} the open follow-ups the doctor wrote that fall
 * due inside it, soonest first. Nothing here is derived from an upload instant.
 */
public record CalendarMonth(YearMonth month, List<TimelineEntry> papers, List<AttentionItem.FollowUp> followUps) {

    public CalendarMonth {
        papers = List.copyOf(papers);
        followUps = List.copyOf(followUps);
    }
}

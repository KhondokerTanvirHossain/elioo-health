package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.web.adapter.in.ImageRefs;
import com.elioo.baymax.web.domain.AttentionItem;
import com.elioo.baymax.web.domain.CalendarMonth;
import com.elioo.baymax.web.domain.TimelineEntry;
import com.elioo.baymax.web.domain.TimelinePage;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The timeline, the calendar and the follow-ups as {@code /app/api/patients/{id}/…} sends them (plan B3), snake case, as
 * {@link PaperJson} is. Every display decision is made here, in Java, and arrives as a field React renders: the
 * {@code month} an entry is grouped under is {@code YearMonth.from} of its filed day (D2's {@code timeline_date}), so
 * React groups by equal keys and never computes a month; the chip is {@link TimelineEntry#chip()} (NOW or THIS_WEEK, or
 * none); {@code date_uncertain} is the read model's {@code timeline_date_uncertain}; a retake carries its copy key, never
 * its code or the developer prose; {@code overdue} is decided against the patient's Dhaka today by the caller. No
 * confidence, no model, no status reason anywhere.
 */
final class TimelineJson {

    private TimelineJson() {
    }

    static Map<String, Object> page(TimelinePage page, Function<String, String> retakeKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entries", page.entries().stream().map(e -> entry(e, retakeKey)).toList());
        body.put("next_cursor", page.nextCursor());
        return body;
    }

    static Map<String, Object> entry(TimelineEntry e, Function<String, String> retakeKey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("document_id", e.documentId().toString());
        m.put("month", YearMonth.from(e.date()).toString());
        m.put("date", e.date().toString());
        m.put("date_is_upload_day", e.dateIsFallback());
        m.put("date_uncertain", e.dateUncertain());
        m.put("document_type", e.documentType());
        m.put("facility", e.facility());
        m.put("status", e.status());
        m.put("chip", chip(e));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("values", e.values());
        counts.put("outside", e.outside());
        counts.put("medicines", e.medicines());
        counts.put("follow_ups", e.followUps());
        counts.put("unverified", e.unverified());
        m.put("counts", counts);
        m.put("review_pending", "pending".equals(e.reviewState()));
        m.put("retake_reason_key", e.retakeReasonCode() == null ? null : retakeKey.apply(e.retakeReasonCode()));
        m.put("page_one", Map.of("src", ImageRefs.page(e.documentId(), 1)));
        return m;
    }

    /** Only the days that hold a paper or a follow-up, ascending; papers in the timeline's order within a day. */
    static Map<String, Object> calendar(CalendarMonth month) {
        TreeMap<LocalDate, Map<String, List<Map<String, Object>>>> days = new TreeMap<>();
        for (TimelineEntry e : month.papers()) {
            Map<String, Object> paper = new LinkedHashMap<>();
            paper.put("document_id", e.documentId().toString());
            paper.put("document_type", e.documentType());
            paper.put("chip", chip(e));
            day(days, e.date()).get("papers").add(paper);
        }
        for (AttentionItem.FollowUp f : month.followUps()) {
            Map<String, Object> follow = new LinkedHashMap<>();
            follow.put("document_id", f.documentId().toString());
            follow.put("instruction", f.instruction());
            follow.put("crop", Map.of("src", ImageRefs.crop(f.documentId(), f.cropKey())));
            day(days, f.dueDate()).get("follow_ups").add(follow);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        days.forEach((date, content) -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("date", date.toString());
            d.put("papers", content.get("papers"));
            d.put("follow_ups", content.get("follow_ups"));
            out.add(d);
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("month", month.month().toString());
        body.put("days", out);
        return body;
    }

    /** The follow-ups list, in the order given (soonest first); {@code overdue} says which are past the patient's today. */
    static Map<String, Object> followUps(List<AttentionItem.FollowUp> items, Predicate<LocalDate> overdue) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AttentionItem.FollowUp f : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("document_id", f.documentId().toString());
            m.put("instruction", f.instruction());
            m.put("due_date", f.dueDate().toString());
            m.put("overdue", overdue.test(f.dueDate()));
            m.put("crop", Map.of("src", ImageRefs.crop(f.documentId(), f.cropKey())));
            out.add(m);
        }
        return Map.of("items", out);
    }

    private static String chip(TimelineEntry e) {
        return e.chip().map(u -> u.dbValue()).orElse(null);
    }

    private static Map<String, List<Map<String, Object>>> day(TreeMap<LocalDate, Map<String, List<Map<String, Object>>>> days, LocalDate date) {
        return days.computeIfAbsent(date, d -> {
            Map<String, List<Map<String, Object>>> content = new LinkedHashMap<>();
            content.put("papers", new ArrayList<>());
            content.put("follow_ups", new ArrayList<>());
            return content;
        });
    }
}

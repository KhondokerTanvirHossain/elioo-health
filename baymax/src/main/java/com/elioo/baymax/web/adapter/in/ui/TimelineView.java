package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.time.PatientCalendar;
import com.elioo.baymax.web.domain.TimelineEntry;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static com.elioo.baymax.web.adapter.in.ui.Html.esc;

/**
 * The কাগজপত্র list (spec §4.15, s16, kit §14): a header per month of the paper's own date (or its upload day, marked),
 * and each paper one ≥ 88 px link — a drawn LAB/Rx thumb (no picture per row, so a page of twenty costs no crop bytes),
 * the type and the patient, the date and the facility as printed, {@link TimelineSummary}'s line and the stored chip
 * ({@link UrgencyChip}: NOW and THIS_WEEK only, nothing on ROUTINE or unassessed), the pending-review chip beside it. A
 * paper that is not finished says where it is and never carries a level or a count. Reads only the entry it is given;
 * nothing here assesses.
 */
final class TimelineView {

    private final UiCopy copy;

    TimelineView(UiCopy copy) {
        this.copy = copy;
    }

    /** The rows in the order given (the read model's), one {@code section.month} per run of the same month. */
    String items(Lang lang, List<TimelineEntry> entries, String patientName) {
        StringBuilder b = new StringBuilder();
        YearMonth month = null;
        for (TimelineEntry e : entries) {
            YearMonth m = YearMonth.from(e.date());
            if (!m.equals(month)) {
                b.append(month == null ? "" : "</ol></section>").append("<section class=\"month\"><h2>")
                        .append(copy.monthYear(lang, e.date())).append("</h2><ol class=\"titems\">");
                month = m;
            }
            b.append("<li>").append(item(lang, e, patientName)).append("</li>");
        }
        return b.append(month == null ? "" : "</ol></section>").toString();
    }

    /** One paper: the whole row is the one link to it (kit §14), with nothing interactive inside. */
    String item(Lang lang, TimelineEntry e, String patientName) {
        boolean done = "DONE".equals(e.status());
        StringBuilder b = new StringBuilder("<a class=\"titem\" href=\"").append(PageChrome.BASE).append("/documents/")
                .append(e.documentId()).append("\">").append(thumb(done ? e.documentType() : null)).append("<span class=\"tbody\">");
        String who = esc(patientName);
        switch (e.status()) {
            case "DONE" -> done(b, lang, e, who);
            case "NEEDS_RETAKE" -> b.append(state(lang, "timeline.paper", who, e, "timeline.retake"))
                    .append("<span class=\"meta\">").append(DocumentPages.retakeReason(copy, lang, e.retakeReasonCode())).append("</span>");
            case "FAILED" -> b.append(state(lang, "timeline.paper", who, e, "timeline.failed"));
            default -> b.append(state(lang, "timeline.reading.title", who, e, "timeline.reading"));
        }
        return b.append("</span>").append(Icons.svg("chevron-right", 24)).append("</a>").toString();
    }

    private void done(StringBuilder b, Lang lang, TimelineEntry e, String who) {
        b.append("<span class=\"ttitle\">").append(typeLabel(copy, lang, e.documentType())).append(" · ").append(who).append("</span>")
                .append("<span class=\"meta\">").append(dateLine(lang, e)).append("</span>");
        String summary = TimelineSummary.line(copy, lang, e);
        if (!summary.isEmpty()) {
            b.append("<span class=\"meta\">").append(summary).append("</span>");
        }
        if (e.unverified() > 0) {
            b.append("<span class=\"meta\">").append(copy.t(lang, "timeline.unverified", e.unverified())).append("</span>");
        }
        // the stored level first; the review state beside it, never instead of it (D5: every gate state)
        String chip = UrgencyChip.html(copy, lang, e);
        boolean pending = "pending".equals(e.reviewState());
        if (!chip.isEmpty() || pending) {
            b.append("<span class=\"chips\">").append(chip);
            if (pending) {
                b.append("<span class=\"chip review\">").append(copy.t(lang, "timeline.review.pending")).append("</span>");
            }
            b.append("</span>");
        }
    }

    /** A paper not (yet) read: what it is, the day it was sent (the patient's day), and where it is. */
    private String state(Lang lang, String titleKey, String who, TimelineEntry e, String stateKey) {
        String sent = copy.dayMonthText(lang, LocalDate.ofInstant(e.createdAt(), PatientCalendar.ZONE));
        return "<span class=\"ttitle\">" + copy.t(lang, titleKey) + " · " + who + "</span>"
                + "<span class=\"meta\">" + copy.t(lang, "timeline.sent", sent) + "</span>"
                + "<span class=\"state\">" + copy.t(lang, stateKey) + "</span>";
    }

    /** "১২ অক্টোবর · Diagnostic Centre, Mirpur"; an upload day is marked, never presented as the visit's. */
    private String dateLine(Lang lang, TimelineEntry e) {
        return copy.dayMonth(lang, e.date()) + (e.dateIsFallback() ? " " + copy.t(lang, "timeline.uploaddate") : "")
                + (e.facility() == null || e.facility().isBlank() ? "" : " · " + esc(e.facility()));
    }

    /** The paper's type as the bundle names it, else "কাগজ"; shared with the চ্যাট cards and the strip (C4). */
    static String typeLabel(UiCopy copy, Lang lang, String type) {
        String label = type == null ? null : copy.maybe(lang, "type." + type);
        return label != null ? label : copy.t(lang, "timeline.paper");
    }

    /** Kit §14: a drawn paper with a LAB/Rx badge — Latin pictogram text on a thumbnail, not reading text. */
    private static String thumb(String documentType) {
        String badge = "prescription".equals(documentType) ? "Rx"
                : "lab_report".equals(documentType) || "imaging_report".equals(documentType) ? "LAB" : null;
        return "<span class=\"thumb\" aria-hidden=\"true\"><i></i><i></i><i></i>"
                + (badge == null ? "" : "<span class=\"badge\" lang=\"en\">" + badge + "</span>") + "</span>";
    }
}

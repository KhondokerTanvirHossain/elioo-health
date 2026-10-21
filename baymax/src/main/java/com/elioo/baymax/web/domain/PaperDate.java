package com.elioo.baymax.web.domain;

import com.elioo.baymax.extraction.domain.Document;

import java.time.LocalDate;

/**
 * D2: the day a paper is filed under, as Postgres generated it, and nothing else. {@code date} is
 * {@code document.timeline_date} (the printed date, or for a retake or a paper that printed none its upload day in
 * Dhaka), {@code uploadDay} is {@code timeline_date_is_upload_day}, and {@code uncertain} is the model's
 * {@code document_date_uncertain} (Q6: such a paper is still filed under its printed date, and marked). Every display
 * reads these; none re-derives them from the printed date.
 */
public record PaperDate(LocalDate date, boolean uploadDay, boolean uncertain) {

    /** The document's filing columns, as read back from the database. */
    public static PaperDate of(Document d) {
        return new PaperDate(d.timelineDate(), d.timelineDateIsUploadDay(), d.docDateUncertain());
    }
}

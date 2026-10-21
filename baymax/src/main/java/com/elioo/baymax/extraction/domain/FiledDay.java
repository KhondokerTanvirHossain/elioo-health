package com.elioo.baymax.extraction.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * D2: the day a paper is filed under, as Postgres generated it from the document row (V25), and nothing else.
 * {@code date} is {@code document.timeline_date} (the printed date, or for a retake or a paper that printed none its
 * upload day in Asia/Dhaka), {@code uploadDay} is {@code timeline_date_is_upload_day}, and {@code uncertain} is
 * {@code timeline_date_uncertain}: the model's {@code document_date_uncertain} where the paper is filed under its
 * printed date (Q6), never on an upload day. Every display reads these; none re-derives them from the printed date.
 *
 * <p>A {@link Document} carries one only once its row has been read back ({@link Document#filed()} is null on a record
 * Java wrote and has not re-read), so "not known" is never mistaken for "this is the printed date".
 */
public record FiledDay(LocalDate date, boolean uploadDay, boolean uncertain) {

    public FiledDay {
        Objects.requireNonNull(date, "D2: a filed day has a date; a row read back always has timeline_date");
        if (uploadDay && uncertain) {
            // V25's timeline_date_uncertain cannot be true on an upload day; a record that says so was not read from it
            throw new IllegalArgumentException("D2: an upload day is never marked uncertain");
        }
    }
}

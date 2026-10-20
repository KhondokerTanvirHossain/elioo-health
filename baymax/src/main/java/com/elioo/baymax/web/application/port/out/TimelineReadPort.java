package com.elioo.baymax.web.application.port.out;

import com.elioo.baymax.web.domain.TimelineCursor;
import com.elioo.baymax.web.domain.TimelineEntry;
import com.elioo.baymax.web.domain.TimelineFilter;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

/**
 * The one owner of the timeline's data (C2): its order, cursor, filter, the stored F1 urgency, the F2 outside count, the
 * review state and the F5 retake code, all read from stored columns. Callers decide visibility first; this port never
 * does, and never assesses.
 */
public interface TimelineReadPort {

    /** One patient's papers after {@code after}: newest timeline_date, then upload, then id; at most {@code limit}. */
    Flux<TimelineEntry> page(UUID patientId, TimelineCursor after, TimelineFilter filter, int limit);

    /** DONE papers uploaded at or after {@code since} with a stored NOW or THIS_WEEK, in any review state; NOW first, then newest. */
    Flux<TimelineEntry> attention(UUID patientId, Instant since, int limit);

    /** RECEIVED, PROCESSING and NEEDS_RETAKE papers uploaded at or after {@code since}, newest upload first (C4's cards). */
    Flux<TimelineEntry> inFlightAndRetakes(UUID patientId, Instant since);
}

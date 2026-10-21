package com.elioo.baymax.web.domain;

import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.extraction.domain.FiledDay;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.domain.Urgency;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything a document page renders, read from stored rows only. {@code urgency} is the document's stored F1 level
 * and is null only while the document is not DONE (or a DONE document could not be assessed). {@code wording} is the
 * released explanation's body and is non-null only when {@code gate == RELEASED} (F3, asserted here so no renderer
 * can show a parked or rejected body).
 *
 * @param retakeOrdinal 1, 2, 3… for a NEEDS_RETAKE document (this patient's streak up to and including it), else 0
 * @param reviewHours   the typical review time in the PENDING copy (spec §4.10 "[N]"), from configuration
 * @param date          D2: the day the paper is filed under, read from the same document row {@code document} was built
 *                      from ({@link FiledDay}: never null here, and its date never null)
 * @param replaces      D1: the paper this one was sent to replace, while that paper still waits for a retake; null
 *                      otherwise. Read for a FAILED paper only, so its resend clears the same retake card
 */
public record ResultView(DocumentView document, PatientProfile patient, boolean owner, ViewerLocale viewer,
                         Urgency urgency, List<String> urgencyReasons, GateState gate, String wording,
                         int retakeOrdinal, int reviewHours, Instant receivedAt, Instant now, FiledDay date, UUID replaces) {

    /** A paper that replaces no retake: every status but a FAILED replacement. */
    public ResultView(DocumentView document, PatientProfile patient, boolean owner, ViewerLocale viewer,
                      Urgency urgency, List<String> urgencyReasons, GateState gate, String wording,
                      int retakeOrdinal, int reviewHours, Instant receivedAt, Instant now, FiledDay date) {
        this(document, patient, owner, viewer, urgency, urgencyReasons, gate, wording, retakeOrdinal, reviewHours, receivedAt, now,
                date, null);
    }

    /** This view naming the retake its paper was sent to replace. */
    public ResultView withReplaces(UUID paper) {
        return new ResultView(document, patient, owner, viewer, urgency, urgencyReasons, gate, wording, retakeOrdinal, reviewHours,
                receivedAt, now, date, paper);
    }

    public ResultView {
        if (date == null) {
            throw new IllegalArgumentException("D2: a paper is shown with the day it is filed under, read from its row");
        }
        if (wording != null && gate != GateState.RELEASED) {
            throw new IllegalArgumentException("F3: model wording only when released or approved, not " + gate);
        }
        if (urgency != null && !"DONE".equals(document.status())) {
            throw new IllegalArgumentException("urgency is shown from DONE onwards, not " + document.status());
        }
        urgencyReasons = urgencyReasons == null ? List.of() : List.copyOf(urgencyReasons);
    }

    public boolean done() {
        return "DONE".equals(document.status());
    }
}

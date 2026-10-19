package com.elioo.baymax.web.domain;

import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.healthrecord.domain.PatientProfile;

import java.time.Instant;

/** What the reading page (s09) shows: the document as stored, whose it is, when it arrived, and where the reader is. */
public record ReadingView(DocumentView document, PatientProfile patient, boolean owner, ViewerLocale viewer,
                          Instant receivedAt, Instant now) {
}

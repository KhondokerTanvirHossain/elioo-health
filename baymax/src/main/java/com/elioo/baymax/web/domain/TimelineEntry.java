package com.elioo.baymax.web.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One document on a patient's timeline. {@code date} is the document's own date when the page carried one;
 * otherwise the day it was received, and {@code dateIsFallback} says so — the UI marks it rather than
 * presenting an upload day as a visit day.
 */
public record TimelineEntry(UUID documentId, LocalDate date, boolean dateIsFallback, String documentType,
                            String facility, String status, int values, int medicines, int followUps,
                            int unverified, String pageOneKey, Instant createdAt) {
}

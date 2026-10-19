package com.elioo.baymax.web.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One earlier reading on the value sheet (s13): the same marker in the same unit, for the same patient, from a finished
 * paper before this one. The value and the unit as that paper printed them; its own crop (no number without the strip
 * of paper it came from, DR-12) and its own observation, so the row opens that paper's sheet.
 *
 * @param date           the paper's timeline date: its printed date, else its upload day in Dhaka
 * @param dateIsFallback true when {@code date} is the upload day (the paper printed none), so the page says so
 */
public record EarlierReading(UUID documentId, UUID observationId, String value, String unit, String cropKey,
                             LocalDate date, boolean dateIsFallback) {
}

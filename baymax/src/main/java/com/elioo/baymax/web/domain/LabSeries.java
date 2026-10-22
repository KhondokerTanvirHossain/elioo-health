package com.elioo.baymax.web.domain;

import com.elioo.baymax.outbound.domain.ValueVerdict;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One marker's readings for one patient over time, the health screen's trend (plan B6). The series key is
 * {@code (patient, canonicalName, MarkerThreshold.foldUnit(unit))}: two readings are one series only when all three are
 * equal, so an absolute count and a percentage of one cell type, or one marker in two units, are never joined. The
 * patient is the read's own (one patient per call), so it is not repeated here.
 *
 * @param unitFold the folded unit, the key's third part; a join key, never displayed
 * @param name     the newest point's name, as printed
 * @param unit     the newest point's unit, as printed
 * @param points   newest first, by the paper's filing day, then upload, then id
 */
public record LabSeries(String canonicalName, String unitFold, String name, String unit, List<Point> points) {

    /**
     * One reading. {@code kind} is {@code PaperDisplay.kindOf} over the stored row (a one-sided INSIDE is UNKNOWN), and
     * {@code side} its drawn side; the date and its two marks are the paper's filing day as Postgres defines it (D2).
     * {@code range} is the range this paper printed beside this reading ({@link PrintedBand#of} over the stored
     * {@code ref_low} and {@code ref_high}), {@link PrintedBand#NONE} when it printed none: never a reference table's.
     */
    public record Point(UUID documentId, UUID observationId, String value, LocalDate date, boolean dateIsUploadDay,
                        boolean dateUncertain, ValueVerdict.Kind kind, String side, String cropKey, PrintedBand range) {
    }

    /** The paired blood-pressure readings, newest first. */
    public record BpSeries(List<BpPoint> points) {
    }

    /**
     * One printed blood-pressure reading: a systolic and a diastolic on the same document and the same OCR line
     * ({@code observation.source_line}), exactly one of each. Each half keeps its own crop: they are two stored crops of
     * one line. {@code sourceLine} is the join key, never displayed.
     */
    public record BpPoint(UUID documentId, LocalDate date, boolean dateIsUploadDay, boolean dateUncertain,
                          Point systolic, Point diastolic, String sourceLine) {
    }
}

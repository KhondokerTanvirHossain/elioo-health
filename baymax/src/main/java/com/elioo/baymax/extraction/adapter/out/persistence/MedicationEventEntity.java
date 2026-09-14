package com.elioo.baymax.extraction.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One medicine as written on a prescription. Action stays {@code recorded} until BMX-7 diffs prescriptions. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "medication_event", schema = BaymaxSchema.NAME)
public class MedicationEventEntity {

    @Id
    private UUID id;
    @Column("patient_id")
    private UUID patientId;
    @Column("document_id")
    private UUID documentId;
    private String name;
    @Column("dose_text")
    private String doseText;
    private String route;
    @Column("timing_text")
    private String timingText;
    @Column("frequency_text")
    private String frequencyText;
    @Column("duration_text")
    private String durationText;
    private String action;
    @Column("crop_key")
    private String cropKey;
    private Instant at;

    public Map<String, Object> toView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", name);
        ObservationEntity.put(view, "dose_text", doseText);
        ObservationEntity.put(view, "route", route);
        ObservationEntity.put(view, "frequency_text", frequencyText);
        ObservationEntity.put(view, "timing_text", timingText);
        ObservationEntity.put(view, "duration_text", durationText);
        view.put("crop_key", cropKey);
        return view;
    }
}

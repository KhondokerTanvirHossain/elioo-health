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

/** One measured value. {@code crop_key} is NOT NULL in the schema: no number without its source crop. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "observation", schema = BaymaxSchema.NAME)
public class ObservationEntity {

    @Id
    private UUID id;
    @Column("patient_id")
    private UUID patientId;
    @Column("document_id")
    private UUID documentId;
    private String name;
    @Column("canonical_name")
    private String canonicalName;
    private String value;
    private String unit;
    @Column("ref_low")
    private String refLow;
    @Column("ref_high")
    private String refHigh;
    private String flag;
    @Column("crop_key")
    private String cropKey;
    @Column("observed_at")
    private Instant observedAt;

    /** The shape the timeline reads: the number, its range, and the crop that proves it. */
    public Map<String, Object> toView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", name);
        put(view, "canonical_name", canonicalName);
        view.put("value", value);
        put(view, "unit", unit);
        put(view, "ref_low", refLow);
        put(view, "ref_high", refHigh);
        put(view, "flag", flag);
        view.put("crop_key", cropKey);
        return view;
    }

    static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}

package com.elioo.baymax.extraction.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A recheck or revisit the document asks for. The proactive engine (BMX-7) reads these by due date. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "follow_up", schema = BaymaxSchema.NAME)
public class FollowUpEntity {

    @Id
    private UUID id;
    @Column("patient_id")
    private UUID patientId;
    @Column("document_id")
    private UUID documentId;
    private String instruction;
    @Column("due_date")
    private LocalDate dueDate;
    private String status;
    @Column("crop_key")
    private String cropKey;

    public Map<String, Object> toView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("instruction", instruction);
        ObservationEntity.put(view, "due_date", dueDate == null ? null : dueDate.toString());
        view.put("status", status);
        view.put("crop_key", cropKey);
        return view;
    }
}

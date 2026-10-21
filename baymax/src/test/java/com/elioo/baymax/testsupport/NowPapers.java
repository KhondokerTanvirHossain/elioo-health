package com.elioo.baymax.testsupport;

import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;

/**
 * Plan B3's shared papers: a finished lab report with a stored NOW, as the urgency writer leaves it (the shape
 * {@code AppApiContext.paper(…, "now")} makes, reachable from every test package), and the value that makes a paper NOW
 * when the sweep assesses it. Synthetic (DR-36): no real patient, no real reading.
 */
public final class NowPapers {

    /** The stopgap's NOW: 12.0 against a printed 3.5–5.1 is more than twice the high. */
    public static final String CRITICAL = "12.0";

    private NowPapers() {
    }

    /** A DONE lab report of this patient, uploaded now, with a stored {@code now} and no reasons. */
    public static UUID nowPaper(ApplicationContext context, PostgreSQLContainer<?> postgres, PatientProfile patient) throws Exception {
        return paper(context, postgres, patient, "now");
    }

    /** A DONE lab report of this patient, uploaded now, with this stored level ({@code null} = never assessed). */
    public static UUID paper(ApplicationContext context, PostgreSQLContainer<?> postgres, PatientProfile patient, String level) throws Exception {
        UUID id = context.getBean(DocumentRecordPort.class)
                .create(Document.received(patient.id(), patient.familyId(), 1, Instant.now())).block().id();
        exec(postgres, level == null
                ? "UPDATE baymax.document SET status = 'DONE', document_type = 'lab_report' WHERE id = ?"
                : "UPDATE baymax.document SET status = 'DONE', document_type = 'lab_report', urgency = '" + level
                        + "', urgency_reasons = '[]', urgency_assessed_at = now() WHERE id = ?", id);
        return id;
    }

    /** One value of the paper with no verdict yet, {@link #CRITICAL} against a printed 3.5–5.1: the sweep makes it NOW. */
    public static UUID criticalValue(PostgreSQLContainer<?> postgres, PatientProfile patient, UUID document) throws Exception {
        return value(postgres, patient, document, "Kalium-B3", CRITICAL);
    }

    /** One value of the paper with no verdict yet, against a printed 3.5–5.1 mmol/L (4.2 is inside: no reason at all). */
    public static UUID value(PostgreSQLContainer<?> postgres, PatientProfile patient, UUID document, String name, String value) throws Exception {
        try (Connection c = connect(postgres); PreparedStatement s = c.prepareStatement("INSERT INTO baymax.observation (patient_id, "
                + "document_id, name, value, unit, ref_low, ref_high, crop_key, observed_at) VALUES (?, ?, ?, ?, 'mmol/L', '3.5', '5.1', ?, now()) "
                + "RETURNING id")) {
            s.setObject(1, patient.id());
            s.setObject(2, document);
            s.setString(3, name);
            s.setString(4, value);
            s.setString(5, patient.familyId() + "/" + patient.id() + "/" + document + "/crop-v1.jpg");
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static void exec(PostgreSQLContainer<?> postgres, String sql, UUID id) throws Exception {
        try (Connection c = connect(postgres); PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, id);
            s.executeUpdate();
        }
    }

    private static Connection connect(PostgreSQLContainer<?> postgres) throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl() + "&sslmode=disable", postgres.getUsername(), postgres.getPassword());
    }
}

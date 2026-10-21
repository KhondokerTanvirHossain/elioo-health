package com.elioo.baymax.extraction.adapter.out.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2's migration on real Postgres, against rows written before it: the schema is migrated to the version before D2,
 * legacy documents are inserted as production holds them (V5's {@code extraction_json} is the model's output as
 * returned, so its {@code document_date_uncertain} may be a boolean, absent, null, a string or a number), then the rest
 * is migrated. The backfill must read a real boolean, keep anything else {@code false}, and never fail: a cast that threw
 * would fail Flyway at startup after {@code deploy.sh} has removed the old container. The redefined filing day must move
 * existing retakes to their upload day and leave a finished paper where it was, and V20's index must be back.
 */
@Testcontainers
class FiledDayMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    /** D2's migration, by its description: its number is taken at merge time, so it is found, never written here. */
    private static final String D2 = "filed day and uncertain date";

    /** case → document id, inserted before D2's migration ran. */
    private static final Map<String, String> ROWS = new LinkedHashMap<>();

    private static Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("baymax").defaultSchema("baymax").createSchemas(true)
                .locations("classpath:db/baymax/migration").baselineOnMigrate(false);
        return (target == null ? config : config.target(target)).load();
    }

    @BeforeAll
    static void migrateAcrossD2WithLegacyRows() throws Exception {
        String before = versionBefore(D2);
        flyway(before).migrate();
        assertThat(scalar("SELECT max(version::int) FROM baymax.flyway_schema_history WHERE success")).isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'baymax' "
                + "AND table_name = 'document' AND column_name = 'doc_date_uncertain'")).as("D2 has not run yet").isEqualTo("0");
        sql("INSERT INTO baymax.family_account (id, whatsapp_number, owner_name, terms_accepted_at) "
                + "VALUES ('00000000-0000-4000-8000-000000000001', '+8801700000901', 'Rahim', now())");
        sql("INSERT INTO baymax.patient_profile (id, family_id, name, age, sex, proxy_consent_at) "
                + "VALUES ('00000000-0000-4000-8000-000000000002', '00000000-0000-4000-8000-000000000001', 'Ma', 68, 'female', now())");
        ROWS.put("true", done("{\"document_date\":\"2026-07-11\",\"document_date_uncertain\":true}", "2026-07-11", "2026-10-20T04:00:00Z"));
        ROWS.put("false", done("{\"document_date\":\"2026-07-11\",\"document_date_uncertain\":false}", "2026-07-11", "2026-10-20T04:00:00Z"));
        ROWS.put("absent", done("{\"document_date\":\"2026-07-11\"}", "2026-07-11", "2026-10-20T04:00:00Z"));
        ROWS.put("json-null", done("{\"document_date_uncertain\":null}", "2026-07-11", "2026-10-20T04:00:00Z"));
        ROWS.put("no-extraction", done(null, "2026-07-11", "2026-10-20T04:00:00Z"));
        ROWS.put("yes", done("{\"document_date_uncertain\":\"yes\"}", "2026-07-11", "2026-10-20T04:00:00Z"));
        ROWS.put("one", done("{\"document_date_uncertain\":1}", "2026-07-11", "2026-10-20T04:00:00Z"));
        // 18:30 UTC on 31 October is 00:30 on 1 November in Dhaka
        ROWS.put("retake", insert("NEEDS_RETAKE", "{\"document_date\":\"2021-03-04\"}", "2021-03-04", "2026-10-31T18:30:00Z"));
        ROWS.put("done-across-midnight", done("{\"document_date\":\"2026-09-30\"}", "2026-09-30", "2026-10-31T18:30:00Z"));
        assertThat(scalar("SELECT timeline_date FROM baymax.document WHERE id = '" + ROWS.get("retake") + "'"))
                .as("before D2 the retake sits under the date misread off it (V20)").isEqualTo("2021-03-04");

        flyway(null).migrate();
    }

    /** The version of the migration just before the one described {@code description}, from Flyway's own list. */
    private static String versionBefore(String description) {
        var all = flyway(null).info().all();
        for (int i = 1; i < all.length; i++) {
            if (description.equals(all[i].getDescription())) {
                return all[i - 1].getVersion().getVersion();
            }
        }
        throw new AssertionError("no migration described '" + description + "'");
    }

    private static String done(String extraction, String docDate, String createdAt) throws Exception {
        return insert("DONE", extraction, docDate, createdAt);
    }

    private static String insert(String status, String extraction, String docDate, String createdAt) throws Exception {
        return scalar("INSERT INTO baymax.document (patient_id, family_id, status, status_reason_code, doc_date, extraction_json, created_at) "
                + "VALUES ('00000000-0000-4000-8000-000000000002', '00000000-0000-4000-8000-000000000001', '" + status + "', "
                + ("NEEDS_RETAKE".equals(status) ? "'low_overall'" : "NULL") + ", '" + docDate + "', "
                + (extraction == null ? "NULL" : "'" + extraction + "'::jsonb") + ", '" + createdAt + "') RETURNING id");
    }

    @Test
    void theBackfillReadsTheFlagFromTheStoredExtraction() throws Exception {
        assertThat(uncertain("true")).as("a stored boolean true").isEqualTo("t");
        assertThat(uncertain("false")).isEqualTo("f");
        assertThat(uncertain("absent")).as("no key: the model said nothing").isEqualTo("f");
        assertThat(uncertain("json-null")).isEqualTo("f");
        assertThat(uncertain("no-extraction")).as("extraction_json NULL").isEqualTo("f");
        assertThat(uncertain("yes")).as("an off-schema string is never cast").isEqualTo("f");
        assertThat(uncertain("one")).as("an off-schema number is never cast").isEqualTo("f");
        assertThat(scalar("SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'baymax' "
                + "AND table_name = 'document' AND column_name = 'doc_date_uncertain'")).isEqualTo("NO");
    }

    @Test
    void existingRetakesMoveToTheirUploadDay() throws Exception {
        assertThat(column("timeline_date", "retake")).as("the Dhaka day of its upload").isEqualTo("2026-11-01");
        assertThat(column("timeline_date_is_upload_day", "retake")).isEqualTo("t");
        assertThat(column("doc_date", "retake")).as("the printed date is kept, never shown").isEqualTo("2021-03-04");
        assertThat(column("timeline_date", "done-across-midnight")).as("a finished paper keeps its printed date").isEqualTo("2026-09-30");
        assertThat(column("timeline_date_is_upload_day", "done-across-midnight")).isEqualTo("f");
    }

    /** V20's index, exactly: the timeline's order and cursor read it. */
    @Test
    void theTimelineIndexIsBack() throws Exception {
        assertThat(scalar("SELECT indexdef FROM pg_indexes WHERE schemaname = 'baymax' AND indexname = 'document_patient_timeline_idx'"))
                .isEqualTo("CREATE INDEX document_patient_timeline_idx ON baymax.document USING btree "
                        + "(patient_id, timeline_date DESC, created_at DESC, id DESC)");
    }

    private static String uncertain(String row) throws Exception {
        return column("doc_date_uncertain", row);
    }

    private static String column(String column, String row) throws Exception {
        return scalar("SELECT " + column + " FROM baymax.document WHERE id = '" + ROWS.get(row) + "'");
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private static String scalar(String query) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            assertThat(rs.next()).as("a row for " + query).isTrue();
            return rs.getString(1);
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}

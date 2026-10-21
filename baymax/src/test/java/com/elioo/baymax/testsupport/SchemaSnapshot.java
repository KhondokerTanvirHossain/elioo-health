package com.elioo.baymax.testsupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every table of a schema as it stands, asked of the database and never of a list (CLAUDE.md: an operation spanning
 * tables is asserted from the schema): per table of {@code information_schema.tables}, its row count and the md5 of its
 * rows' text in a fixed order. Two snapshots taken around a request are equal exactly when the request wrote nothing
 * outside its {@link Exclusions}, so a "this request writes nothing but X" test sees a write to a table its author never
 * thought of. A row's text is {@code to_jsonb(row)} with the excluded columns removed, so a column the request is allowed
 * to touch (the session guard's sliding expiry) is dropped from the comparison and nothing else is.
 *
 * <p>Each exclusion names its reason, and must name a table (and column) the schema really has: an exclusion that
 * matched nothing would hide nothing and say it did.
 */
public final class SchemaSnapshot {

    /** One table's state: how many rows, and the md5 of their text ordered by that text. */
    public record Table(long rows, String digest) {
    }

    /** What a snapshot leaves out, each with its reason. */
    public static final class Exclusions {
        private final Map<String, String> tables = new LinkedHashMap<>();
        private final Map<String, Map<String, String>> columns = new LinkedHashMap<>();

        private Exclusions() {
        }

        public static Exclusions none() {
            return new Exclusions();
        }

        /** Leaves {@code table} out of the snapshot entirely. */
        public Exclusions table(String table, String reason) {
            tables.put(table, requireReason(reason));
            return this;
        }

        /** Drops {@code column} of {@code table} from its rows' text; the table's count and other columns still compare. */
        public Exclusions column(String table, String column, String reason) {
            columns.computeIfAbsent(table, t -> new LinkedHashMap<>()).put(column, requireReason(reason));
            return this;
        }

        private static String requireReason(String reason) {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("every exclusion says why");
            }
            return reason;
        }
    }

    private SchemaSnapshot() {
    }

    /** The base tables of {@code schema}, sorted, from information_schema; guarded against an empty answer. */
    public static List<String> tables(Connection c, String schema) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_type = 'BASE TABLE' ORDER BY table_name")) {
            s.setString(1, schema);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        }
        assertThat(tables).as("information_schema lists the tables of " + schema + " — else a snapshot compares nothing").isNotEmpty();
        return tables;
    }

    /** Every table of {@code schema} but the excluded ones, by name, with its count and digest. */
    public static Map<String, Table> of(Connection c, String schema, Exclusions exclusions) throws SQLException {
        Objects.requireNonNull(exclusions, "exclusions (Exclusions.none() for none)");
        List<String> tables = tables(c, schema);
        for (String excluded : exclusions.tables.keySet()) {
            assertThat(tables).as("an excluded table the schema has (else the exclusion hides nothing)").contains(excluded);
        }
        Map<String, Table> out = new TreeMap<>();
        try (Statement s = c.createStatement()) {
            for (String table : tables) {
                if (exclusions.tables.containsKey(table)) {
                    continue;
                }
                Map<String, String> dropped = exclusions.columns.getOrDefault(table, Map.of());
                for (String column : dropped.keySet()) {
                    assertThat(columnsOf(c, schema, table)).as("an excluded column " + table + " has").contains(column);
                }
                String minus = dropped.keySet().stream().map(col -> " - '" + col.replace("'", "''") + "'").collect(Collectors.joining());
                String sql = "SELECT count(*), coalesce(md5(string_agg(r, '' ORDER BY r)), '') FROM (SELECT (to_jsonb(t)" + minus
                        + ")::text AS r FROM " + schema + "." + table + " t) x";
                try (ResultSet rs = s.executeQuery(sql)) {
                    rs.next();
                    out.put(table, new Table(rs.getLong(1), rs.getString(2)));
                }
            }
        }
        return out;
    }

    private static List<String> columnsOf(Connection c, String schema, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ?")) {
            s.setString(1, schema);
            s.setString(2, table);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
        }
        return columns;
    }
}

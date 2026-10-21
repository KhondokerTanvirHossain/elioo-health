package com.elioo.baymax.testsupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What is left of a deleted paper, asked of the database itself, never of a list someone remembered (CLAUDE.md: an
 * operation spanning tables is asserted from the schema). Shared by {@code WebOnlyJourneyAcceptanceTest} and the
 * {@code /app/api} delete tests, so there is one sweep and one list of survivors and the two cannot drift on P1.
 *
 * <p>Two halves:
 * <ul>
 *   <li><b>by column</b> ({@link #tablesStillHolding}): every column the catalog says can hold a paper's id
 *       ({@link #documentReferences}: a foreign key to {@code document(id)}, or a {@code document_id}-named column),
 *       plus {@code document} itself by {@code id};</li>
 *   <li><b>by content</b> ({@link #textStillHolding}): every {@code text}, {@code character varying} and {@code jsonb}
 *       column in the schema, scanned for needles the paper put there (its words, its id, its rows' ids). This is the
 *       half that sees {@code nudge}, which names a paper only in {@code trigger_key} and copies its words into
 *       {@code vars_json}, and a nudge's own message, which carries no {@code document_id} at all.</li>
 * </ul>
 * Rows a {@link #SURVIVES} predicate matches are left out of both halves, each with its reason. Each half guards
 * against emptiness: a schema query that returned nothing would make the sweep pass vacuously.
 */
public final class DeletionSweep {

    /** A table whose rows matching {@code predicate} outlive a deleted paper, and why. {@code others} names the rest. */
    public record Survivor(String table, String predicate, String others, String reason) {
    }

    /** First raised by A13 (#76) and written there as OPEN; it is PO decision P1 of the Wave 2 plan. */
    static final String P1 = "delivered history, kept pending PO decision P1, first raised by A13 (#76)";

    public static final List<Survivor> SURVIVES = List.of(
            new Survivor("audit_event", "true", null,
                    "the audit of the view and of the delete itself; ids only, no text (V8)"),
            new Survivor("outbound_message", "sent_at IS NOT NULL", "sent_at is null", P1),
            // delivered is read from the message, not from nudge.status alone: a gated nudge keeps 'gated' after the
            // reviewer approves it and WhatsApp accepts it (approve updates only the message)
            new Survivor("nudge", "status = 'sent' OR EXISTS (SELECT 1 FROM baymax.outbound_message dm WHERE dm.id = nudge.message_id "
                    + "AND dm.sent_at IS NOT NULL)", "undelivered", P1));

    private DeletionSweep() {
    }

    /** The tables that carry a {@code document_id} column, from information_schema, guarded against emptiness. */
    public static List<String> documentIdTables(Connection c) throws SQLException {
        List<String> tables = column(c, "select table_name from information_schema.columns where table_schema = 'baymax' "
                + "and column_name = 'document_id' order by table_name");
        assertThat(tables).as("information_schema returned the document_id tables — else the sweep would pass vacuously")
                .contains("stored_object", "observation", "outbound_message", "audit_event");
        return tables;
    }

    /** A column that can hold a paper's id: {@code table.column}. */
    public record DocumentReference(String table, String column) {
        @Override
        public String toString() {
            return table + "." + column;
        }
    }

    /**
     * Every column of the schema that can hold a paper's id, from the catalog: each column with a foreign key to
     * {@code baymax.document(id)} (pg_constraint, pairing each key column with the column it references, so a composite
     * key's {@code patient_id} half is not mistaken for a paper), plus every column named {@code document_id} and every
     * uuid column whose name ends in {@code document_id}, keyed or not (a later column added without its key still holds
     * the id). Guarded both against emptiness and against a half that silently stopped matching: the self-reference
     * {@code document.replaces_document_id} (D1, uuid) is invisible to a by-name-only or a text-only sweep.
     */
    public static List<DocumentReference> documentReferences(Connection c) throws SQLException {
        List<DocumentReference> refs = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                select t.relname, a.attname
                  from pg_constraint k
                  join pg_class t on t.oid = k.conrelid
                  join pg_namespace n on n.oid = t.relnamespace
                  cross join lateral unnest(k.conkey, k.confkey) as pair(col, refcol)
                  join pg_attribute a on a.attrelid = k.conrelid and a.attnum = pair.col
                  join pg_attribute r on r.attrelid = k.confrelid and r.attnum = pair.refcol
                 where k.contype = 'f' and n.nspname = 'baymax' and k.confrelid = 'baymax.document'::regclass and r.attname = 'id'
                union
                select table_name, column_name from information_schema.columns
                 where table_schema = 'baymax'
                   and (column_name = 'document_id' or (data_type = 'uuid' and column_name like '%document\\_id'))
                order by 1, 2
                """)) {
            while (rs.next()) {
                refs.add(new DocumentReference(rs.getString(1), rs.getString(2)));
            }
        }
        assertThat(refs).as("the catalog returned the columns naming a paper — else the sweep would pass vacuously")
                .contains(new DocumentReference("document", "replaces_document_id"),
                        new DocumentReference("stored_object", "document_id"), new DocumentReference("observation", "document_id"),
                        new DocumentReference("outbound_message", "document_id"), new DocumentReference("audit_event", "document_id"));
        return refs;
    }

    /**
     * {@code table=count} (or {@code table[others]=count} for a survivor's table, {@code table.column=count} for a column
     * not named {@code document_id}) for every column of {@link #documentReferences} still holding this document outside
     * {@link #SURVIVES}. Empty means the paper is gone.
     */
    public static List<String> tablesStillHolding(Connection c, UUID documentId) throws SQLException {
        List<String> offenders = new ArrayList<>();
        long left = count(c, "select count(*) from baymax.document where id = ?", documentId);
        if (left != 0) {
            offenders.add("document=" + left);
        }
        for (DocumentReference ref : documentReferences(c)) {
            String table = ref.table();
            Optional<Survivor> kept = survivor(table);
            if (kept.isPresent() && kept.get().others() == null) {
                continue;
            }
            String where = kept.map(k -> " and not (" + k.predicate() + ")").orElse("");
            long n = count(c, "select count(*) from baymax." + table + " where " + ref.column() + " = ?" + where, documentId);
            if (n != 0) {
                String name = "document_id".equals(ref.column()) ? table : ref.toString();
                offenders.add(kept.map(k -> name + "[" + k.others() + "]").orElse(name) + "=" + n);
            }
        }
        return offenders;
    }

    /** Rows per table that name this document by column, survivors included: a precondition's "every table has one". */
    public static Map<String, Long> rowsByTable(Connection c, UUID documentId) throws SQLException {
        Map<String, Long> rows = new LinkedHashMap<>();
        rows.put("document", count(c, "select count(*) from baymax.document where id = ?", documentId));
        for (String table : documentIdTables(c)) {
            rows.put(table, count(c, "select count(*) from baymax." + table + " where document_id = ?", documentId));
        }
        return rows;
    }

    /** Survivor rows that name this document by column: each predicate's rows, asserted to exist when the fixture made one. */
    public static Map<String, Long> survivorsOf(Connection c, UUID documentId) throws SQLException {
        Map<String, Long> rows = new LinkedHashMap<>();
        List<String> tables = documentIdTables(c);
        for (Survivor s : SURVIVES) {
            if (tables.contains(s.table())) {
                rows.put(s.table(), count(c, "select count(*) from baymax." + s.table() + " where document_id = ? and ("
                        + s.predicate() + ")", documentId));
            }
        }
        return rows;
    }

    /** Every text, varchar and jsonb column of the schema as {@code table.column}, guarded against emptiness. */
    public static List<String> textColumns(Connection c) throws SQLException {
        List<String> columns = column(c, "select table_name || '.' || column_name from information_schema.columns "
                + "where table_schema = 'baymax' and data_type in ('text', 'character varying', 'jsonb') "
                + "order by table_name, column_name");
        assertThat(columns).as("information_schema returned the text columns — else the content scan would pass vacuously")
                .contains("nudge.trigger_key", "nudge.vars_json", "outbound_message.body",
                        "medication_event.name", "follow_up.instruction");
        return columns;
    }

    /** {@code table.column holds <needle> ×n} for every hit outside {@link #SURVIVES}. Empty means no column holds any. */
    public static List<String> textStillHolding(Connection c, Collection<String> needles) throws SQLException {
        return scan(c, needles, true);
    }

    /** The same scan with the survivors counted too: before a delete, every needle must be found (the needle is real). */
    public static List<String> textHolding(Connection c, Collection<String> needles) throws SQLException {
        return scan(c, needles, false);
    }

    private static List<String> scan(Connection c, Collection<String> needles, boolean excludeSurvivors) throws SQLException {
        assertThat(needles).as("needles to scan for").isNotEmpty().allSatisfy(n -> assertThat(n).hasSizeGreaterThanOrEqualTo(10));
        List<String> hits = new ArrayList<>();
        for (String qualified : textColumns(c)) {
            String table = qualified.substring(0, qualified.indexOf('.'));
            String col = qualified.substring(qualified.indexOf('.') + 1);
            String where = excludeSurvivors ? survivor(table).map(k -> " and not (" + k.predicate() + ")").orElse("") : "";
            for (String needle : needles) {
                try (PreparedStatement s = c.prepareStatement("select count(*) from baymax." + table
                        + " where position(? in " + col + "::text) > 0" + where)) {
                    s.setString(1, needle);
                    try (ResultSet rs = s.executeQuery()) {
                        rs.next();
                        long n = rs.getLong(1);
                        if (n != 0) {
                            hits.add(qualified + " holds " + needle + " x" + n);
                        }
                    }
                }
            }
        }
        return hits;
    }

    /** Needles none of whose hits were found by {@link #textHolding}: a fixture that never put them anywhere. */
    public static List<String> absent(List<String> hits, Collection<String> needles) {
        return needles.stream().filter(n -> hits.stream().noneMatch(h -> h.contains(" holds " + n + " x"))).toList();
    }

    private static Optional<Survivor> survivor(String table) {
        return SURVIVES.stream().filter(s -> s.table().equals(table)).findFirst();
    }

    private static long count(Connection c, String sql, UUID id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, id);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static List<String> column(Connection c, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }
}

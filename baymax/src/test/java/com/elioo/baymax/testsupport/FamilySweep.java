package com.elioo.baymax.testsupport;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What is left of a deleted family or patient, asked of the schema and never of a list someone remembered (CLAUDE.md:
 * an operation spanning tables is asserted from the schema). Lifted out of {@code FamilyDeletionAcceptanceTest} (plan
 * B3), so the admin delete and any later API delete share one derivation and cannot drift.
 *
 * <p>The swept columns are derived twice and united: every foreign key in schema {@code baymax} that points at
 * {@code family_account}, {@code patient_profile} or {@code share_member}, whatever the column is called; and every
 * column named {@code family_id} or {@code patient_id}, which finds the tables that carry the ids with no foreign key at
 * all ({@link #FK_LESS}). Each derivation is guarded against going blind, and {@link #emptyBefore} is the guard on the
 * guard: a swept column the fixture left empty would pass the sweep without testing anything.
 */
public final class FamilySweep {

    /** The tables that hold family data with no foreign key at all: only the column-name lookup can find them. */
    public static final List<String> FK_LESS = List.of("audit_event.family_id", "audit_event.patient_id",
            "stored_object.family_id", "stored_object.patient_id");

    /** One swept column, and what it points at: family_account, patient_profile or share_member. */
    public record Target(String table, String column, String target) {
        public String name() {
            return table + "." + column;
        }
    }

    /** The ids of one deleted subject: its family, its patient and the share-member rows of that patient. */
    public record Ids(UUID family, UUID patient, List<UUID> shareMembers) {
    }

    private FamilySweep() {
    }

    /** Every column that can hold a family's data (see the class comment), guarded against either derivation going blind. */
    public static List<Target> targets(Connection c) throws SQLException {
        Map<String, Target> byName = new LinkedHashMap<>();
        List<String> fkNames = new ArrayList<>();
        try (Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("""
                    SELECT cl.relname AS tbl, a.attname AS col, ref.relname AS target
                    FROM pg_constraint c
                    JOIN pg_class cl ON cl.oid = c.conrelid
                    JOIN pg_namespace n ON n.oid = cl.relnamespace
                    JOIN pg_class ref ON ref.oid = c.confrelid
                    JOIN LATERAL unnest(c.conkey) AS k(attnum) ON true
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
                    WHERE c.contype = 'f' AND n.nspname = 'baymax'
                      AND ref.relname IN ('family_account', 'patient_profile', 'share_member')
                    ORDER BY 1, 2""")) {
                while (rs.next()) {
                    Target t = new Target(rs.getString(1), rs.getString(2), rs.getString(3));
                    byName.put(t.name(), t);
                    fkNames.add(t.name());
                }
            }
            List<String> byColumnName = new ArrayList<>();
            try (ResultSet rs = s.executeQuery(
                    "SELECT table_name, column_name FROM information_schema.columns "
                            + "WHERE table_schema = 'baymax' AND column_name IN ('family_id','patient_id') "
                            + "ORDER BY table_name, column_name")) {
                while (rs.next()) {
                    Target t = new Target(rs.getString(1), rs.getString(2),
                            "family_id".equals(rs.getString(2)) ? "family_account" : "patient_profile");
                    byName.putIfAbsent(t.name(), t);
                    byColumnName.add(t.name());
                }
            }
            assertThat(fkNames).as("pg_constraint returned no foreign keys to a family or patient — the sweep would "
                    + "vacuously pass, which is the failure mode this helper exists to prevent")
                    .contains("document.family_id", "inbound_message.family_id");
            assertThat(byColumnName).as("the column-name lookup no longer sees the tables that hold family data "
                    + "without a foreign key").containsAll(FK_LESS);
        }
        return List.copyOf(byName.values());
    }

    /** The guard on the guard: the swept columns in {@code scope} that hold nothing for these ids before the delete. */
    public static List<String> emptyBefore(Connection c, List<Target> targets, Ids ids, Predicate<Target> scope) throws SQLException {
        List<String> empty = new ArrayList<>();
        for (Target t : targets) {
            if (scope.test(t) && rows(c, t, idsFor(t, ids)) == 0) {
                empty.add(t.name());
            }
        }
        return empty;
    }

    /** {@code table.column=count} for every swept column in {@code scope} that still holds these ids' rows. */
    public static List<String> stillHolding(Connection c, List<Target> targets, Ids ids, Predicate<Target> scope) throws SQLException {
        List<String> offenders = new ArrayList<>();
        for (Target t : targets) {
            long n = scope.test(t) ? rows(c, t, idsFor(t, ids)) : 0;
            if (n > 0) {
                offenders.add(t.name() + "=" + n);
            }
        }
        return offenders;
    }

    /**
     * Rows per swept column for these ids, every column, empty ones included, plus the subject rows themselves
     * ({@code family_account.id}, {@code patient_profile.id}): what a delete of someone else must leave unchanged.
     */
    public static Map<String, Long> rowsByColumn(Connection c, List<Target> targets, Ids ids) throws SQLException {
        Map<String, Long> rows = new LinkedHashMap<>();
        rows.put("family_account.id", rows(c, new Target("family_account", "id", "family_account"), List.of(ids.family().toString())));
        rows.put("patient_profile.id", rows(c, new Target("patient_profile", "id", "patient_profile"), List.of(ids.patient().toString())));
        for (Target t : targets) {
            rows.put(t.name(), rows(c, t, idsFor(t, ids)));
        }
        return rows;
    }

    /** The ids a column must be checked against. */
    private static List<String> idsFor(Target t, Ids ids) {
        return switch (t.target()) {
            case "family_account" -> List.of(ids.family().toString());
            case "patient_profile" -> List.of(ids.patient().toString());
            case "share_member" -> ids.shareMembers().stream().map(UUID::toString).toList();
            default -> throw new IllegalStateException("unexpected target " + t.target());
        };
    }

    private static long rows(Connection c, Target t, List<String> ids) throws SQLException {
        if (ids.isEmpty()) {
            return 0;
        }
        String in = ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM baymax." + t.table() + " WHERE " + t.column() + " IN (" + in + ")")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}

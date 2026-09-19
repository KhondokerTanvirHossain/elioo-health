package com.elioo.baymax.healthrecord.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rows actually removed, per table, in the order they were deleted — what happened, not what was intended.
 *
 * <p>The receipt a family is shown under §6.3 has to be answerable from this: "everything is gone" is a claim
 * about rows, and before this the deletion reported a patient count it had computed rather than the rows it had
 * removed, while {@code document} was never deleted at all.
 */
public record DeletionCounts(Map<String, Long> byTable) {

    public DeletionCounts {
        byTable = byTable == null ? new LinkedHashMap<>() : new LinkedHashMap<>(byTable);
    }

    public static DeletionCounts empty() {
        return new DeletionCounts(new LinkedHashMap<>());
    }

    /** A copy with one more table's count recorded; insertion order is the deletion order. */
    public DeletionCounts with(String table, Long rows) {
        LinkedHashMap<String, Long> next = new LinkedHashMap<>(byTable);
        next.put(table, rows == null ? 0L : rows);
        return new DeletionCounts(next);
    }

    public long of(String table) {
        return byTable.getOrDefault(table, 0L);
    }

    public long total() {
        return byTable.values().stream().mapToLong(Long::longValue).sum();
    }

    /** e.g. {@code document=3, observation=12, …} — every table, including the zeroes. */
    public String describe() {
        return byTable.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + ", " + b).orElse("(nothing)");
    }
}

package com.elioo.baymax.web.domain;

/**
 * The কাগজপত্র filter chips (সব · ল্যাব · প্রেসক্রিপশন). Each value carries a fixed SQL fragment, so request text never
 * reaches the query: {@link #fromParam} maps anything unknown to {@link #ALL}.
 */
public enum TimelineFilter {
    ALL("", ""),
    LAB("lab", "AND d.document_type IN ('lab_report', 'imaging_report')"),
    PRESCRIPTION("rx", "AND d.document_type = 'prescription'");

    private final String param;
    private final String sql;

    TimelineFilter(String param, String sql) {
        this.param = param;
        this.sql = sql;
    }

    /** The {@code ?type=} value: "lab", "rx", or empty for ALL. */
    public String param() {
        return param;
    }

    /** A fixed fragment appended to the timeline's WHERE clause. */
    public String sql() {
        return sql;
    }

    public static TimelineFilter fromParam(String raw) {
        if ("lab".equals(raw)) {
            return LAB;
        }
        if ("rx".equals(raw)) {
            return PRESCRIPTION;
        }
        return ALL;
    }
}

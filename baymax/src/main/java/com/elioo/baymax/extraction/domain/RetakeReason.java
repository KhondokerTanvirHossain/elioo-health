package com.elioo.baymax.extraction.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * F5: why a document needs a retake, as the code stored in {@code document.status_reason_code} (V20). A page maps the
 * code to Bangla copy keyed by {@link #prefix} ({@code retake.reason.<prefix>}, optionally {@code .<section>}); the
 * English {@code status_reason} stays developer-facing and is never rendered.
 *
 * <p>{@link #LEGACY} is written only by V20's backfill, for a pre-V20 retake whose prose matched none of the four
 * forms; no code path produces it.</p>
 */
public enum RetakeReason {
    NO_TEXT("no_text", false),
    LOW_OVERALL("low_overall", false),
    LEGACY("legacy", false),
    EXPECTED_SECTION_EMPTY("expected_section_empty", true),
    LOW_SECTION("low_section", true);

    private final String prefix;
    private final boolean hasSection;

    RetakeReason(String prefix, boolean hasSection) {
        this.prefix = prefix;
        this.hasSection = hasSection;
    }

    public String prefix() {
        return prefix;
    }

    /** The stored code: the prefix, and for a sectioned reason {@code :<section>}. */
    public String code(String section) {
        if (hasSection != (section != null)) {
            throw new IllegalArgumentException(prefix + (hasSection ? " needs a section" : " takes no section"));
        }
        return hasSection ? prefix + ":" + section : prefix;
    }

    /** The reason a stored code names, or empty for null or an unknown prefix. */
    public static Optional<RetakeReason> ofCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String p = code.contains(":") ? code.substring(0, code.indexOf(':')) : code;
        return Arrays.stream(values()).filter(r -> r.prefix.equals(p)).findFirst();
    }

    /** The section a stored code names ({@code values} in {@code low_section:values}), or null when it names none. */
    public static String sectionOf(String code) {
        return code == null || !code.contains(":") ? null : code.substring(code.indexOf(':') + 1);
    }
}

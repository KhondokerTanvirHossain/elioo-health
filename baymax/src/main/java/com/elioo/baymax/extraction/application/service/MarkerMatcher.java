package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Maps an extracted value name onto one of the configured chronic markers, so trend detection later has a
 * stable key across labs that spell the same test five different ways.
 *
 * <p>The model is asked to set {@code canonical_name} itself; this is the server-side check on top, both to
 * catch what the model missed and to reject a canonical name that is not in the configured list. The list is
 * configuration ({@code baymax.markers[]}), so it can change without a release.</p>
 */
@Component
public class MarkerMatcher {

    private final Map<String, String> byAlias = new LinkedHashMap<>();
    /** canonical marker -> names that must never resolve to it, however well they match. */
    private final Map<String, java.util.List<String>> exclusions = new LinkedHashMap<>();

    public MarkerMatcher(BaymaxProperties properties) {
        for (BaymaxProperties.Marker marker : properties.getMarkers()) {
            String canonical = normalise(marker.getCanonical());
            if (canonical.isEmpty()) {
                continue;
            }
            byAlias.put(canonical, marker.getCanonical().trim());
            for (String alias : marker.getAliases()) {
                String key = normalise(alias);
                if (!key.isEmpty()) {
                    byAlias.putIfAbsent(key, marker.getCanonical().trim());
                }
            }
            java.util.List<String> excluded = marker.getNotAliases().stream()
                    .map(MarkerMatcher::normalise)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (!excluded.isEmpty()) {
                exclusions.put(marker.getCanonical().trim(), excluded);
            }
        }
    }

    /**
     * @param name          the value name as printed on the document
     * @param modelProposed what the model put in {@code canonical_name}, possibly null or not a real marker
     * @return the canonical marker, or empty when this value is not one we track
     */
    public Optional<String> canonicalFor(String name, String modelProposed) {
        return canonicalFor(name, modelProposed, null);
    }

    /**
     * @param specimen the sample this value was measured in ({@code blood}, {@code urine}, …), from the
     *                 section heading the row sits under; null or blank when the page does not say
     */
    public Optional<String> canonicalFor(String name, String modelProposed, String specimen) {
        // The model's own canonical_name is checked against the PAGE's name too, so a row printed "Non-HDL
        // Cholesterol" cannot be pulled onto hdl by the model proposing it. Direction-inverted markers make
        // that the difference between "your cholesterol is fine" and a missed escalation.
        Optional<String> fromModel = lookup(modelProposed)
                .filter(canonical -> !isExcluded(canonical, normalise(name)));
        Optional<String> resolved = fromModel.isPresent() ? fromModel : lookup(name);
        return resolved.filter(canonical -> allowedForSpecimen(canonical, specimen));
    }

    /** Markers measured in urine. Everything else in the table is a blood test. */
    private static final java.util.Set<String> URINE_MARKERS = java.util.Set.of("specific_gravity");

    /**
     * True unless the specimen contradicts the marker.
     *
     * <p>A urine report and a blood report share words freely: glucose, protein, bilirubin, calcium, RBC,
     * WBC. "Calcium-Oxalate" under URINE R/E is a crystal counted down a microscope; serum calcium is an
     * mg/dL concentration with a threshold either side, and judging one by the other's numbers is how a
     * normal urine sample becomes an urgent message.</p>
     *
     * <p>An unknown specimen allows everything, deliberately. Most reports do not label their sections, and
     * refusing to match without a heading would take every threshold offline on those pages — a safety rule
     * that silently disables the safety rules is worse than the collision it prevents.</p>
     */
    private static boolean allowedForSpecimen(String canonical, String specimen) {
        String sample = normalise(specimen);
        if (sample.isEmpty() || "other".equals(sample)) {
            return true;
        }
        boolean urineMarker = URINE_MARKERS.contains(canonical);
        return "urine".equals(sample) ? urineMarker : !urineMarker;
    }

    private Optional<String> lookup(String candidate) {
        String key = normalise(candidate);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        String exact = byAlias.get(key);
        if (exact != null && !isExcluded(exact, key)) {
            return Optional.of(exact);
        }
        // "s. creatinine (serum)" should still find "creatinine"; longest alias wins so a short alias cannot
        // beat a more specific one.
        //
        // Exclusions are applied to the CANDIDATES, not to the winner alone: "Non-HDL Cholesterol" matches
        // both "hdl" and "total cholesterol", and dropping the excluded match lets the correct one win
        // instead of losing the name entirely.
        return byAlias.entrySet().stream()
                .filter(e -> containsWords(key, e.getKey()))
                .filter(e -> !isExcluded(e.getValue(), key))
                .max(Map.Entry.comparingByKey(java.util.Comparator.comparingInt(String::length)))
                .map(Map.Entry::getValue);
    }

    /**
     * True when {@code alias} appears in {@code name} as a contiguous run of WHOLE WORDS.
     *
     * <p>A plain {@code contains} produced five wrong mappings on batch 2, each a marker whose letters
     * happened to sit inside a longer word: "appe<b>anc</b>e" became an absolute neutrophil count,
     * "athero<b>scl</b>erosis" became chloride, "RBC <b>total</b> count" became bilirubin. Every one was a
     * threshold pointed at the wrong test, and patching them individually only waits for the next lab to
     * print a name nobody anticipated.</p>
     *
     * <p>Both strings arrive {@link #normalise}d — lower-cased, punctuation turned into spaces, whitespace
     * collapsed — so word boundaries are simply spaces, and a lab writing "S-POTASSIUM", "S.POTASSIUM" or
     * "(Potassium)" all still match.</p>
     */
    static boolean containsWords(String name, String alias) {
        if (alias.isEmpty()) {
            return false;
        }
        // Space-padding turns a substring test into a whole-word test: " anc " is not inside " appeance ",
        // while " potassium " is inside " s potassium ".
        return (" " + name + " ").contains(" " + alias + " ");
    }

    /**
     * True when this name is one the marker explicitly disowns.
     *
     * <p>The substring fallback is deliberately generous, which is how "S-POTASSIUM" finds potassium without
     * an entry for every prefix a lab might print. The cost is that it also matches names that merely contain
     * a marker's letters: "Non-HDL Cholesterol" resolved to {@code hdl}, whose threshold is direction-
     * inverted, so a bad result would have been read as a protective one.</p>
     */
    private boolean isExcluded(String canonical, String normalisedName) {
        for (String excluded : exclusions.getOrDefault(canonical, java.util.List.of())) {
            if (normalisedName.contains(excluded)) {
                return true;
            }
        }
        return false;
    }

    /** Lower-case, punctuation to spaces, whitespace collapsed: "S. Creatinine" and "s creatinine" agree. */
    public static String normalise(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9%/]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    /** The canonical names currently configured, for the prompt and for tests. */
    public java.util.Set<String> canonicalNames() {
        return java.util.Set.copyOf(byAlias.values());
    }
}

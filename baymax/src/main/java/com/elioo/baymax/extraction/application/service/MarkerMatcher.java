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
        Optional<String> fromModel = lookup(modelProposed);
        return fromModel.isPresent() ? fromModel : lookup(name);
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
        // "s. creatinine (serum)" should still find "creatinine"; longest alias wins to avoid
        // matching "ldl" inside an unrelated word.
        //
        // Exclusions are applied to the CANDIDATES, not to the winner alone: "Non-HDL Cholesterol" matches
        // both "hdl" and "total cholesterol", and dropping the excluded match lets the correct one win
        // instead of losing the name entirely.
        return byAlias.entrySet().stream()
                .filter(e -> key.contains(e.getKey()))
                .filter(e -> !isExcluded(e.getValue(), key))
                .max(Map.Entry.comparingByKey(java.util.Comparator.comparingInt(String::length)))
                .map(Map.Entry::getValue);
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

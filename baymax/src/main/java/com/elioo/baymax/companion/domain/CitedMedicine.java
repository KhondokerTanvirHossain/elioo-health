package com.elioo.baymax.companion.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A prescription line an answer quotes ({@code {{rx:n}}}, inserted by code): the paper it is on and the stored crop key of
 * that line (DR-12), so the family is shown the crop beside the transcription, as everywhere else a prescription line is
 * shown (review of #166, finding 5). Never the line's text: the answer already carries it, verbatim.
 */
public record CitedMedicine(UUID documentId, String cropKey) {

    public CitedMedicine {
        Objects.requireNonNull(documentId, "documentId");
        if (cropKey == null || cropKey.isBlank()) {
            throw new IllegalArgumentException("a quoted line without its crop is never shown (DR-12)");
        }
    }

    /** V30's two stored arrays, paired by position (the constraint holds them to one length); null reads as none. */
    public static List<CitedMedicine> paired(UUID[] papers, String[] crops) {
        if (papers == null || crops == null) {
            return List.of();
        }
        List<CitedMedicine> out = new ArrayList<>();
        for (int i = 0; i < Math.min(papers.length, crops.length); i++) {
            out.add(new CitedMedicine(papers[i], crops[i]));
        }
        return List.copyOf(out);
    }
}

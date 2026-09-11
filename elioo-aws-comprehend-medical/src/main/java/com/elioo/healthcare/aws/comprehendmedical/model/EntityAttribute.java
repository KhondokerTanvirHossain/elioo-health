package com.elioo.healthcare.aws.comprehendmedical.model;

import java.util.List;

/**
 * Attribute of a medical entity (e.g., dosage for medication, direction for anatomy).
 */
public record EntityAttribute(
        String type,
        Double score,
        Double relationshipScore,
        Integer id,
        Integer beginOffset,
        Integer endOffset,
        String text,
        List<EntityTrait> traits
) {}

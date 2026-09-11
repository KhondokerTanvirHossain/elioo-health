package com.elioo.healthcare.aws.comprehendmedical.model;

/**
 * Trait of a medical entity (NEGATION, DIAGNOSIS, SIGN, SYMPTOM, etc.).
 */
public record EntityTrait(
        String name,
        Double score
) {}

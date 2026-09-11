package com.elioo.healthcare.aws.bedrock.health.dto;

/**
 * Target audience for clinical content generation.
 * Determines the language complexity and terminology used.
 */
public enum TargetAudience {
    /**
     * Patient-friendly language with minimal medical jargon.
     */
    PATIENT,

    /**
     * Medical professional terminology and clinical detail.
     */
    PROVIDER,

    /**
     * Scientific/research context with technical depth.
     */
    RESEARCHER
}

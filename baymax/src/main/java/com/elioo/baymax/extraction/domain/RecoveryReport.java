package com.elioo.baymax.extraction.domain;

import java.util.UUID;

/**
 * What the startup recovery sweep (F9b) did with one document. {@code outcome} is a machine code — reprocessed,
 * explained, skipped_too_old, failed_interrupted_repeatedly, failed_pages_missing or error — never patient text.
 */
public record RecoveryReport(UUID documentId, String outcome) {
}

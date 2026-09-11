package com.elioo.healthcare.gcp.common.exception;

/**
 * Exception thrown when GCP configuration is invalid or incomplete.
 *
 * <p>This exception is typically thrown during application startup when:</p>
 * <ul>
 *   <li>Required configuration properties are missing</li>
 *   <li>Credentials cannot be loaded</li>
 *   <li>GCP project ID is invalid</li>
 *   <li>Configuration values are malformed</li>
 * </ul>
 *
 * <p><b>Usage examples:</b></p>
 * <pre>
 * // Missing project ID
 * if (properties.getProjectId() == null) {
 *     throw new GcpConfigurationException("GCP project ID is required but not configured");
 * }
 *
 * // Invalid credentials file
 * if (!credentialsFile.exists()) {
 *     throw new GcpConfigurationException(
 *         "Credentials file not found: " + credentialsPath,
 *         fileNotFoundException
 *     );
 * }
 * </pre>
 *
 * @since 0.1.0
 */
public class GcpConfigurationException extends RuntimeException {

    /**
     * Constructs a new configuration exception.
     *
     * @param message Error message describing the configuration issue
     */
    public GcpConfigurationException(String message) {
        super(message);
    }

    /**
     * Constructs a new configuration exception with a root cause.
     *
     * @param message Error message describing the configuration issue
     * @param cause   Root cause exception
     */
    public GcpConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}

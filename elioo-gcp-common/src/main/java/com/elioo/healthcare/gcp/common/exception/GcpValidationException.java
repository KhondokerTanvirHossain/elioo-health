package com.elioo.healthcare.gcp.common.exception;

/**
 * Exception thrown when input validation fails for GCP service requests.
 *
 * <p>This exception is thrown when:</p>
 * <ul>
 *   <li>Image size exceeds GCP limits</li>
 *   <li>Image format is not supported</li>
 *   <li>Request parameters are invalid</li>
 *   <li>Text exceeds maximum length</li>
 * </ul>
 *
 * <p><b>Usage examples:</b></p>
 * <pre>
 * // Image too large
 * if (imageSizeMb > 20) {
 *     throw new GcpValidationException(
 *         "Image size exceeds GCP Vision limit of 20 MB: " + imageSizeMb + " MB"
 *     );
 * }
 *
 * // Invalid image format
 * if (!supportedFormats.contains(imageFormat)) {
 *     throw new GcpValidationException(
 *         "Unsupported image format: " + imageFormat + ". Supported: " + supportedFormats
 *     );
 * }
 * </pre>
 *
 * @since 0.1.0
 */
public class GcpValidationException extends RuntimeException {

    /**
     * Constructs a new validation exception.
     *
     * @param message Error message describing the validation failure
     */
    public GcpValidationException(String message) {
        super(message);
    }

    /**
     * Constructs a new validation exception with a root cause.
     *
     * @param message Error message describing the validation failure
     * @param cause   Root cause exception
     */
    public GcpValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}

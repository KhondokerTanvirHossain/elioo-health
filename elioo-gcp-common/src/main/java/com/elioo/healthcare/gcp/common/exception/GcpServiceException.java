package com.elioo.healthcare.gcp.common.exception;

/**
 * Base exception for all GCP service errors.
 *
 * <p>This exception is thrown when a GCP API call fails or returns an error.
 * It provides context about which GCP service failed and includes the original error details.</p>
 *
 * <p><b>Usage examples:</b></p>
 * <pre>
 * // Vision API error
 * throw new GcpServiceException(
 *     "Vision API",
 *     "INVALID_ARGUMENT",
 *     "Image format not supported",
 *     400
 * );
 *
 * // With root cause
 * try {
 *     visionClient.annotateImage(request);
 * } catch (ApiException e) {
 *     throw new GcpServiceException("Vision API", e.getStatusCode().name(), e.getMessage(), e.getStatusCode().getCode().getHttpStatusCode(), e);
 * }
 * </pre>
 *
 * @since 0.1.0
 */
public class GcpServiceException extends RuntimeException {

    /**
     * Name of the GCP service that threw the error.
     * Examples: "Vision API", "Cloud Storage", "Cloud Functions"
     */
    private final String serviceName;

    /**
     * GCP error code (if available).
     * Examples: "INVALID_ARGUMENT", "PERMISSION_DENIED", "RESOURCE_EXHAUSTED"
     */
    private final String errorCode;

    /**
     * HTTP status code (if available).
     * Examples: 400 (Bad Request), 403 (Forbidden), 429 (Too Many Requests)
     */
    private final Integer statusCode;

    /**
     * Constructs a new GCP service exception.
     *
     * @param serviceName Name of the GCP service
     * @param errorCode   GCP error code
     * @param message     Error message
     * @param statusCode  HTTP status code
     */
    public GcpServiceException(String serviceName, String errorCode, String message, Integer statusCode) {
        super(formatMessage(serviceName, errorCode, message, statusCode));
        this.serviceName = serviceName;
        this.errorCode = errorCode;
        this.statusCode = statusCode;
    }

    /**
     * Constructs a new GCP service exception with a root cause.
     *
     * @param serviceName Name of the GCP service
     * @param errorCode   GCP error code
     * @param message     Error message
     * @param statusCode  HTTP status code
     * @param cause       Root cause exception
     */
    public GcpServiceException(String serviceName, String errorCode, String message, Integer statusCode, Throwable cause) {
        super(formatMessage(serviceName, errorCode, message, statusCode), cause);
        this.serviceName = serviceName;
        this.errorCode = errorCode;
        this.statusCode = statusCode;
    }

    /**
     * Constructs a new GCP service exception with minimal information.
     *
     * @param serviceName Name of the GCP service
     * @param message     Error message
     */
    public GcpServiceException(String serviceName, String message) {
        this(serviceName, null, message, null);
    }

    /**
     * Constructs a new GCP service exception with minimal information and root cause.
     *
     * @param serviceName Name of the GCP service
     * @param message     Error message
     * @param cause       Root cause exception
     */
    public GcpServiceException(String serviceName, String message, Throwable cause) {
        this(serviceName, null, message, null, cause);
    }

    /**
     * Format error message with service context.
     */
    private static String formatMessage(String serviceName, String errorCode, String message, Integer statusCode) {
        StringBuilder sb = new StringBuilder();
        sb.append("GCP ").append(serviceName).append(" error");

        if (errorCode != null) {
            sb.append(" [").append(errorCode).append("]");
        }

        if (statusCode != null) {
            sb.append(" (HTTP ").append(statusCode).append(")");
        }

        sb.append(": ").append(message);

        return sb.toString();
    }

    // Getters

    public String getServiceName() {
        return serviceName;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Integer getStatusCode() {
        return statusCode;
    }
}

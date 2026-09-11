package com.elioo.healthcare.aws.common.exception;

/**
 * Base exception for all AWS service-related errors.
 *
 * This exception wraps AWS SDK exceptions and provides a consistent
 * error handling interface across all Elioo AWS libraries.
 *
 * Subclasses:
 * - AwsConfigurationException: Configuration errors
 * - AwsAuthenticationException: Credential/authentication errors
 * - AwsThrottlingException: Rate limiting errors
 * - AwsValidationException: Request validation errors
 */
public class AwsServiceException extends RuntimeException {

    private final String serviceName;
    private final String errorCode;
    private final Integer statusCode;

    public AwsServiceException(String message) {
        super(message);
        this.serviceName = null;
        this.errorCode = null;
        this.statusCode = null;
    }

    public AwsServiceException(String message, Throwable cause) {
        super(message, cause);
        this.serviceName = null;
        this.errorCode = null;
        this.statusCode = null;
    }

    public AwsServiceException(
            String serviceName,
            String errorCode,
            Integer statusCode,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.serviceName = serviceName;
        this.errorCode = errorCode;
        this.statusCode = statusCode;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    @Override
    public String toString() {
        if (serviceName != null) {
            return String.format(
                    "%s [service=%s, errorCode=%s, statusCode=%d]: %s",
                    getClass().getSimpleName(),
                    serviceName,
                    errorCode,
                    statusCode,
                    getMessage()
            );
        }
        return super.toString();
    }
}

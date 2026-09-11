package com.elioo.healthcare.aws.common.exception;

/**
 * Exception thrown when AWS service configuration is invalid or incomplete.
 *
 * Examples:
 * - Missing required configuration properties
 * - Invalid region specification
 * - Malformed configuration values
 */
public class AwsConfigurationException extends AwsServiceException {

    public AwsConfigurationException(String message) {
        super(message);
    }

    public AwsConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}

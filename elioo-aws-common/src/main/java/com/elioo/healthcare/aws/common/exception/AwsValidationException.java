package com.elioo.healthcare.aws.common.exception;

/**
 * Exception thrown when request validation fails before calling AWS.
 *
 * Examples:
 * - Image size exceeds maximum limit
 * - Text length exceeds service limits
 * - Invalid parameter values
 * - Missing required fields
 */
public class AwsValidationException extends AwsServiceException {

    private final String fieldName;
    private final Object invalidValue;

    public AwsValidationException(String message) {
        super(message);
        this.fieldName = null;
        this.invalidValue = null;
    }

    public AwsValidationException(String fieldName, Object invalidValue, String message) {
        super(String.format("Validation failed for field '%s': %s", fieldName, message));
        this.fieldName = fieldName;
        this.invalidValue = invalidValue;
    }

    public String getFieldName() {
        return fieldName;
    }

    public Object getInvalidValue() {
        return invalidValue;
    }
}

# Testing AWS Medical Classification Integration

This guide explains how to test the Medical Classification functionality with AWS Comprehend Medical.

## Prerequisites

### 1. AWS SDK Dependencies

The AWS SDK dependencies are already configured in `build.gradle`:

```gradle
implementation(platform("software.amazon.awssdk:bom:2.21.26"))
implementation("software.amazon.awssdk:comprehendmedical")
```

Download dependencies:
```bash
./gradlew build
```

### 2. Configure AWS Credentials

Same as OCR testing - see [TESTING_AWS_OCR.md](TESTING_AWS_OCR.md#2-configure-aws-credentials)

### 3. AWS IAM Permissions

Ensure your AWS user/role has these permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "comprehendmedical:DetectEntitiesV2",
        "comprehendmedical:InferICD10CM",
        "comprehendmedical:InferRxNorm"
      ],
      "Resource": "*"
    }
  ]
}
```

---

## Running Tests

### 1. Unit Tests (No AWS Credentials Required)

Run unit tests with mocked AWS Comprehend Medical client:

```bash
# Run all unit tests
./gradlew test --tests ComprehendMedicalAdapterTest

# Run with verbose output
./gradlew test --tests ComprehendMedicalAdapterTest --info

# Run specific test method
./gradlew test --tests ComprehendMedicalAdapterTest.testClassifyMedicalEntities_Success
```

**What these tests do:**
- ✅ Mock AWS Comprehend Medical client responses
- ✅ Test business logic and data transformation
- ✅ Validate error handling
- ✅ Test medical code mapping (ICD-10-CM, RxNorm)
- ✅ Test classification validation logic

**Runs WITHOUT AWS credentials** - perfect for CI/CD pipelines.

### 2. Integration Tests (Requires AWS Credentials)

Run integration tests that call real AWS Comprehend Medical:

```bash
# Run all integration tests
./gradlew test --tests ComprehendMedicalAdapterIntegrationTest

# Run with verbose output
./gradlew test --tests ComprehendMedicalAdapterIntegrationTest --info

# Run specific test
./gradlew test --tests ComprehendMedicalAdapterIntegrationTest.testClassifyMedicalEntities_HappyPath
```

**What these tests do:**
1. ✅ Extract medical entities from real clinical text
2. ✅ Map diagnoses to ICD-10-CM codes
3. ✅ Map medications to RxNorm codes
4. ✅ Extract entity relationships
5. ✅ Validate classification quality
6. ✅ Test complete end-to-end workflow

**Expected Output:**
```
=== Classified Medical Entities ===
Entity: hypertension | Category: MEDICAL_CONDITION | Type: DX_NAME | Confidence: 0.95
Entity: type 2 diabetes mellitus | Category: MEDICAL_CONDITION | Type: DX_NAME | Confidence: 0.98
Entity: metformin | Category: MEDICATION | Type: GENERIC_NAME | Confidence: 0.99
Entity: 500mg | Category: MEDICATION | Type: DOSAGE | Confidence: 0.97

Detected: 2 medical conditions, 2 medications

=== ICD-10-CM Codes ===
Code: I10 | Description: Essential (primary) hypertension | Confidence: 0.95
Code: E11.9 | Description: Type 2 diabetes mellitus without complications | Confidence: 0.92

=== RxNorm Codes ===
Code: 6809 | Description: Metformin | Confidence: 0.99
Code: 860975 | Description: Metformin 500 MG Oral Tablet | Confidence: 0.97
```

---

## Test Coverage

### Unit Test Coverage

**[ComprehendMedicalAdapterTest.java](../src/test/java/com/elioo/healthcare/medicalreport/adapter/out/aws/ComprehendMedicalAdapterTest.java)**

| Test Method | Description | Verifies |
|-------------|-------------|----------|
| `testClassifyMedicalEntities_Success()` | Extract medical entities | ✅ Entity extraction with attributes |
| `testMapToMedicalCodes_ICD10_Success()` | Map to ICD-10-CM codes | ✅ ICD-10-CM code inference |
| `testMapToMedicalCodes_RxNorm_Success()` | Map to RxNorm codes | ✅ RxNorm medication codes |
| `testMapToMedicalCodes_SNOMED_NotSupported()` | Handle unsupported code system | ✅ Graceful degradation for SNOMED-CT |
| `testValidateClassification_HighConfidence()` | Validate high-quality results | ✅ Validation with confidence > 0.70 |
| `testValidateClassification_LowConfidence()` | Handle low confidence | ✅ Validation warnings and errors |
| `testClassifyMedicalEntities_TextTooLong()` | Handle text exceeding limits | ✅ Error handling for 20K+ chars |
| `testClassifyMedicalEntities_AwsError()` | Handle AWS service errors | ✅ Error handling and exceptions |
| `testExtractRelationships_Success()` | Extract entity relationships | ✅ Relationship extraction logic |
| `testAdapterImplementsPort()` | Verify interface implementation | ✅ Port implementation |

### Integration Test Coverage

**[ComprehendMedicalAdapterIntegrationTest.java](../src/test/java/com/elioo/healthcare/medicalreport/adapter/out/aws/ComprehendMedicalAdapterIntegrationTest.java)**

| Test Method | Description | Verifies |
|-------------|-------------|----------|
| `testClassifyMedicalEntities_HappyPath()` | Classify clinical text | ✅ Full entity extraction with real AWS |
| `testMapToMedicalCodes_ICD10_HappyPath()` | Map diagnoses to ICD-10 | ✅ Real ICD-10-CM code inference |
| `testMapToMedicalCodes_RxNorm_HappyPath()` | Map medications to RxNorm | ✅ Real RxNorm code inference |
| `testMapToMedicalCodes_MultipleCodeSystems()` | Use multiple code systems | ✅ Combined ICD-10 + RxNorm |
| `testValidateClassification_HighQuality()` | Validate real results | ✅ Quality validation workflow |
| `testExtractRelationships_HappyPath()` | Extract relationships | ✅ Entity relationship extraction |
| `testClassifyMedicalEntities_ShortText()` | Handle short text | ✅ Minimum text handling |
| `testCompleteClassificationWorkflow()` | End-to-end workflow | ✅ Complete classification pipeline |
| `testAdapterImplementsPort()` | Verify interface | ✅ Port implementation |

---

## Supported Features

### ✅ Supported Code Systems

| Code System | API | Status |
|-------------|-----|--------|
| **ICD-10-CM** | `InferICD10CM` | ✅ Fully Supported |
| **RxNorm** | `InferRxNorm` | ✅ Fully Supported |
| **SNOMED-CT** | N/A | ❌ Not available via AWS API |
| **LOINC** | N/A | ❌ Not available via AWS API |
| **CPT** | N/A | ❌ Not available via AWS API |

**Note:** For SNOMED-CT, LOINC, and CPT codes, you would need:
- UMLS API integration
- Local terminology databases
- Third-party medical coding services

### ✅ Supported Entity Types

AWS Comprehend Medical can detect:
- **Medical Conditions**: Diagnoses, diseases, disorders
- **Medications**: Generic names, brand names, dosages
- **Test/Treatment/Procedure**: Lab tests, treatments, procedures
- **Anatomy**: Body parts, organs, systems
- **Protected Health Information (PHI)**: Names, dates, addresses

### ✅ Supported Entity Attributes

- **Dosage**: Medication dosages
- **Duration**: Treatment duration
- **Frequency**: Administration frequency
- **Route**: Administration route
- **Strength**: Medication strength
- **Form**: Medication form

### ✅ Supported Entity Traits

- **Negation**: Absence of condition
- **Diagnosis**: Confirmed diagnosis
- **Sign**: Observable sign
- **Symptom**: Reported symptom

---

## Troubleshooting

### Issue 1: AWS Credentials Not Found

**Error:**
```
Unable to load credentials from any of the providers in the chain
```

**Solution:**
1. Run `aws configure`
2. Or set environment variables
3. Verify: `aws sts get-caller-identity`

See [TESTING_AWS_OCR.md - Troubleshooting](TESTING_AWS_OCR.md#troubleshooting) for details.

### Issue 2: Access Denied

**Error:**
```
AccessDeniedException: User is not authorized to perform: comprehendmedical:DetectEntitiesV2
```

**Solution:**
Add required permissions to your IAM user/role (see [Prerequisites](#3-aws-iam-permissions) above).

### Issue 3: Text Too Long

**Error:**
```
ClassificationException: Text exceeds maximum length of 20000 characters
```

**Solution:**
AWS Comprehend Medical has a 20,000 character limit per request. Split longer texts into chunks.

### Issue 4: Low Confidence Results

**Issue:**
Many entities have low confidence scores (< 0.70)

**Possible Causes:**
- Text contains non-standard medical terminology
- Text has poor formatting or OCR artifacts
- Text contains abbreviations not recognized by AWS

**Solutions:**
1. Improve OCR quality before classification
2. Pre-process text to expand abbreviations
3. Adjust confidence threshold in classification request

### Issue 5: Missing Expected Entities

**Issue:**
Some medical terms not detected

**Possible Causes:**
- Term is too generic or ambiguous
- Term uses non-standard spelling
- Context is insufficient for detection

**Solutions:**
1. Provide more clinical context
2. Use standard medical terminology
3. Review AWS Comprehend Medical limitations

---

## Example Test Runs

### Unit Tests

```bash
$ ./gradlew test --tests ComprehendMedicalAdapterTest

> Task :test

Medical Classification Unit Test - ComprehendMedicalAdapter with Mocks PASSED
  ✓ Classify medical entities successfully
  ✓ Map medical terms to ICD-10 codes successfully
  ✓ Map medications to RxNorm codes successfully
  ✓ Handle SNOMED-CT code system gracefully (not supported)
  ✓ Validate classification with high confidence
  ✓ Validate classification with low confidence - generate warnings
  ✓ Handle text exceeding maximum length
  ✓ Handle AWS Comprehend Medical error gracefully
  ✓ Extract entity relationships successfully
  ✓ Verify adapter implements MedicalClassificationPort interface

BUILD SUCCESSFUL in 2s
10 tests completed, 10 passed
```

### Integration Tests

```bash
$ ./gradlew test --tests ComprehendMedicalAdapterIntegrationTest.testClassifyMedicalEntities_HappyPath

> Task :test

=== Classified Medical Entities ===
Entity: hypertension | Category: MEDICAL_CONDITION | Type: DX_NAME | Confidence: 0.95
Entity: type 2 diabetes mellitus | Category: MEDICAL_CONDITION | Type: DX_NAME | Confidence: 0.98
Entity: metformin | Category: MEDICATION | Type: GENERIC_NAME | Confidence: 0.99
Entity: 500mg | Category: MEDICATION | Type: DOSAGE | Confidence: 0.97
Entity: lisinopril | Category: MEDICATION | Type: GENERIC_NAME | Confidence: 0.98
Entity: 10mg | Category: MEDICATION | Type: DOSAGE | Confidence: 0.96

Detected: 2 medical conditions, 4 medications

Medical Classification Integration Test - AWS Comprehend Medical Adapter > Happy Path: Classify medical entities from clinical text PASSED

BUILD SUCCESSFUL in 3s
```

---

## CI/CD Integration

### GitHub Actions / GitLab CI

Use **unit tests** in CI/CD (no AWS credentials required):

```yaml
test:
  stage: test
  script:
    - ./gradlew test --tests ComprehendMedicalAdapterTest
  # Skip integration tests in CI - run locally only
```

### Optional: LocalStack for Integration Tests

For testing AWS services locally in CI (note: LocalStack's Comprehend Medical support is limited):

```yaml
services:
  - name: localstack/localstack:latest
    alias: localstack

test:
  stage: test
  variables:
    AWS_ACCESS_KEY_ID: test
    AWS_SECRET_ACCESS_KEY: test
    AWS_ENDPOINT_OVERRIDE: http://localstack:4566
  script:
    - ./gradlew test --tests ComprehendMedicalAdapterTest  # Unit tests only
```

---

## Cost Considerations

### AWS Comprehend Medical Pricing (as of 2024)

- **DetectEntitiesV2**: $0.01 per 100 characters (~$0.10 per 1,000 characters)
- **InferICD10CM**: $0.01 per 100 characters
- **InferRxNorm**: $0.01 per 100 characters

### Recommendations

1. **Use unit tests** for frequent testing (no AWS cost)
2. **Use integration tests** sparingly (incurs AWS charges)
3. **Set up billing alerts** in AWS Console
4. **Cache results** when appropriate

**Estimated Cost for Testing:**
- 100 integration test runs with ~500 chars each = ~$0.05 (negligible)
- But be mindful in CI/CD with frequent runs

---

## Next Steps

After verifying Medical Classification tests pass:

1. **Test Insights Generation**: Create similar tests for `BedrockAdapter`
2. **End-to-End Test**: Test complete workflow: OCR → Classification → Insights
3. **Performance Testing**: Test with various text sizes and complexities
4. **Load Testing**: Test with multiple concurrent requests
5. **Production Deployment**: Deploy with IAM roles (no credentials in config)

---

## Comparison with OCR Testing

| Aspect | OCR (Textract) | Classification (Comprehend Medical) |
|--------|----------------|-------------------------------------|
| **Input** | Images (PNG, JPG, PDF) | Text |
| **Output** | Extracted text + structure | Medical entities + codes |
| **Size Limit** | 10 MB (image) | 20,000 characters (text) |
| **Primary API** | `DetectDocumentText` | `DetectEntitiesV2` |
| **Secondary APIs** | `AnalyzeDocument` | `InferICD10CM`, `InferRxNorm` |
| **Cost** | $1.50 per 1,000 pages | $0.10 per 1,000 characters |
| **Typical Use** | Extract text from reports | Classify and code medical terms |

---

## Support

For issues or questions:
- Check AWS Comprehend Medical documentation: https://docs.aws.amazon.com/comprehend-medical/
- Review [AWS_INTEGRATION_ARCHITECTURE.md](AWS_INTEGRATION_ARCHITECTURE.md)
- Contact MedScribe AI development team

---

**Happy Testing! 🏥**

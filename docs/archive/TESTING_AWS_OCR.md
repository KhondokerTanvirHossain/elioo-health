# Testing AWS OCR Integration

This guide explains how to test the OCR functionality with AWS Textract.

## Prerequisites

### 1. Download Dependencies
First, download the AWS SDK dependencies:

```bash
./gradlew build
```

This will download:
- AWS SDK v2 BOM (2.21.26)
- AWS Textract SDK
- AWS Comprehend Medical SDK
- AWS Bedrock Runtime SDK
- AWS S3 SDK

### 2. Configure AWS Credentials

You have three options for AWS authentication:

#### Option A: AWS CLI Configuration (Recommended for Local Development)
```bash
aws configure
# Enter your AWS Access Key ID
# Enter your AWS Secret Access Key
# Enter default region: us-east-1
```

#### Option B: Environment Variables
```bash
export AWS_ACCESS_KEY_ID=your_access_key
export AWS_SECRET_ACCESS_KEY=your_secret_key
export AWS_REGION=us-east-1
```

#### Option C: Application Properties
Edit `src/main/resources/application-aws.properties`:
```properties
aws.access-key-id=YOUR_ACCESS_KEY_ID
aws.secret-access-key=YOUR_SECRET_ACCESS_KEY
aws.region=us-east-1
```

**⚠️ Security Note**: Never commit credentials to Git. Use environment variables or AWS CLI for local development.

### 3. AWS IAM Permissions

Ensure your AWS user/role has these permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "textract:DetectDocumentText",
        "textract:AnalyzeDocument"
      ],
      "Resource": "*"
    }
  ]
}
```

### 4. Test Image (Optional)

Place a medical report image in:
```
src/test/resources/test-images/blood-test-report.png
```

If no image is provided, tests will use a minimal fallback image.

---

## Running Tests

### 1. Integration Test (Requires AWS Credentials)

Run the complete integration test that calls real AWS Textract:

```bash
# Run specific integration test
./gradlew test --tests TextractAdapterIntegrationTest

# Run with verbose output
./gradlew test --tests TextractAdapterIntegrationTest --info

# Run specific test method
./gradlew test --tests TextractAdapterIntegrationTest.testExtractMedicalData_HappyPath
```

**What this test does:**
1. ✅ Loads test image (or generates minimal image)
2. ✅ Converts image to base64
3. ✅ Calls AWS Textract via `OcrPort`
4. ✅ Parses Textract response
5. ✅ Maps to domain `TestResult` objects
6. ✅ Validates extracted data structure
7. ✅ Prints results to console

**Expected Output:**
```
=== Extracted Test Results ===
Test: Hemoglobin | Value: 14.5 g/dL | Status: NORMAL | Confidence: 0.96
Test: Glucose | Value: 95 mg/dL | Status: NORMAL | Confidence: 0.94
Test: Creatinine | Value: 1.2 mg/dL | Status: ABNORMAL | Confidence: 0.98

=== Image Quality Validation ===
Valid: true
Quality Score: 0.85
Reason: Image quality acceptable

=== Complete OCR Workflow ===
Step 1: Image Quality - 0.85
Step 2: Data Extraction - 3 tests extracted
  - Hemoglobin: 14.5
  - Glucose: 95
  - Creatinine: 1.2
Step 3: Validation - Average Confidence: 0.96
```

### 2. Unit Test (No AWS Credentials Required)

Run unit tests with mocked AWS Textract client:

```bash
./gradlew test --tests TextractAdapterTest
```

**What this test does:**
1. ✅ Mocks AWS Textract client
2. ✅ Tests business logic in adapter
3. ✅ Validates data transformation
4. ✅ Tests error handling
5. ✅ Verifies interface implementation

**Runs WITHOUT AWS credentials** - perfect for CI/CD pipelines.

---

## Test Coverage

### Integration Test Coverage

**[TextractAdapterIntegrationTest.java](../src/test/java/com/elioo/healthcare/medicalreport/adapter/out/aws/TextractAdapterIntegrationTest.java)**

| Test Method | Description | Verifies |
|-------------|-------------|----------|
| `testExtractMedicalData_HappyPath()` | Extract structured test results | ✅ Full OCR workflow with real AWS |
| `testExtractRawText_HappyPath()` | Extract unstructured text | ✅ DetectDocumentText API |
| `testValidateImageQuality_HappyPath()` | Validate image before processing | ✅ Quality checks (size, format) |
| `testGetProcessingConfidence_HappyPath()` | Get confidence by report type | ✅ Confidence scoring logic |
| `testCompleteOcrWorkflow_HappyPath()` | Complete 3-step workflow | ✅ Quality → Extract → Validate |
| `testExtractMedicalData_DifferentReportTypes()` | Handle multiple report types | ✅ BLOOD_TEST, URINE_TEST, RADIOLOGY |
| `testConfidenceThresholdFiltering()` | Filter by confidence threshold | ✅ Threshold logic (>= 0.80) |
| `testStatusDetermination()` | Determine test status | ✅ NORMAL/ABNORMAL/CRITICAL logic |

### Unit Test Coverage

**[TextractAdapterTest.java](../src/test/java/com/elioo/healthcare/medicalreport/adapter/out/aws/TextractAdapterTest.java)**

| Test Method | Description | Verifies |
|-------------|-------------|----------|
| `testExtractRawText_Success()` | Mock raw text extraction | ✅ Text parsing logic |
| `testValidateImageQuality_AcceptableSize()` | Validate normal image | ✅ Quality validation logic |
| `testValidateImageQuality_TooLarge()` | Reject oversized image | ✅ Size limit enforcement (10MB) |
| `testGetProcessingConfidence()` | Confidence by report type | ✅ Report type mapping |
| `testExtractMedicalData_WithTableData()` | Mock table parsing | ✅ Table structure handling |
| `testExtractMedicalData_TextractError()` | Handle AWS errors | ✅ Error handling & custom exceptions |
| `testExtractRawText_Error()` | Handle text extraction errors | ✅ Error propagation |
| `testAdapterImplementsOcrPort()` | Verify interface | ✅ Port implementation |

---

## Troubleshooting

### Issue 1: AWS Credentials Not Found
**Error:**
```
Unable to load credentials from any of the providers in the chain
```

**Solution:**
1. Run `aws configure` to set up credentials
2. Or set environment variables: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`
3. Verify credentials: `aws sts get-caller-identity`

### Issue 2: Access Denied
**Error:**
```
AccessDeniedException: User is not authorized to perform: textract:DetectDocumentText
```

**Solution:**
Add required permissions to your IAM user/role (see Prerequisites section above).

### Issue 3: Test Image Not Found
**Error:**
```
java.nio.file.NoSuchFileException: src/test/resources/test-images/blood-test-report.png
```

**Solution:**
Test will automatically use a fallback minimal image. For better results:
1. Add your own test image: `src/test/resources/test-images/blood-test-report.png`
2. See [test-images/README.md](../src/test/resources/test-images/README.md) for image requirements

### Issue 4: Build Errors (AWS SDK Classes Not Found)
**Error:**
```
cannot find symbol: class TextractAsyncClient
```

**Solution:**
1. Download dependencies: `./gradlew build`
2. Refresh IDE: File → Invalidate Caches / Restart (IntelliJ)
3. Re-import Gradle project

### Issue 5: Test Timeout
**Error:**
```
reactor.core.publisher.Mono.block() timed out
```

**Solution:**
1. Check internet connectivity
2. Verify AWS service availability: https://status.aws.amazon.com/
3. Increase test timeout if needed

---

## Example Test Run

```bash
$ ./gradlew test --tests TextractAdapterIntegrationTest.testExtractMedicalData_HappyPath

> Task :test

TextractAdapterIntegrationTest > Happy Path: Extract medical data from blood test report PASSED

=== Extracted Test Results ===
Test: Hemoglobin | Value: 14.5 g/dL | Status: NORMAL | Confidence: 0.96
Test: White Blood Count | Value: 7.2 K/µL | Status: NORMAL | Confidence: 0.95
Test: Platelet Count | Value: 250 K/µL | Status: NORMAL | Confidence: 0.97
Test: Glucose (Fasting) | Value: 95 mg/dL | Status: NORMAL | Confidence: 0.94
Test: Creatinine | Value: 1.2 mg/dL | Status: ABNORMAL | Confidence: 0.98

BUILD SUCCESSFUL in 8s
5 actionable tasks: 1 executed, 4 up-to-date
```

---

## CI/CD Integration

### GitHub Actions / GitLab CI

Use **unit tests** in CI/CD (no AWS credentials required):

```yaml
test:
  stage: test
  script:
    - ./gradlew test --tests TextractAdapterTest
  # Skip integration tests in CI - run locally only
```

### Optional: LocalStack for Integration Tests in CI

For testing AWS services locally in CI:

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
    - ./gradlew test --tests TextractAdapterIntegrationTest
```

---

## Next Steps

After verifying OCR tests pass:

1. **Test Classification**: Create similar tests for `ComprehendMedicalAdapter`
2. **Test Insights**: Create tests for `BedrockAdapter`
3. **End-to-End Test**: Test complete workflow: OCR → Classification → Suggestions
4. **Load Testing**: Test with multiple concurrent requests
5. **Production Deployment**: Deploy with IAM roles (no credentials in config)

---

## Cost Considerations

### AWS Textract Pricing (as of 2024)

- **DetectDocumentText**: $1.50 per 1,000 pages
- **AnalyzeDocument** (Tables + Forms): $15.00 per 1,000 pages

### Recommendations

1. **Use unit tests** for frequent testing (no AWS cost)
2. **Use integration tests** sparingly (incurs AWS charges)
3. **Set up billing alerts** in AWS Console
4. **Use LocalStack** for unlimited local testing (free)

**Estimated Cost for Testing:**
- 100 integration test runs = ~$0.015 (negligible)
- But be mindful in CI/CD with frequent runs

---

## Support

For issues or questions:
- Check AWS Textract documentation: https://docs.aws.amazon.com/textract/
- Review [AWS_INTEGRATION_ARCHITECTURE.md](AWS_INTEGRATION_ARCHITECTURE.md)
- Contact MedScribe AI development team

---

**Happy Testing! 🚀**

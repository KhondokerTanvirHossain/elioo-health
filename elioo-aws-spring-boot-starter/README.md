# Elioo AWS Spring Boot Starter

Spring Boot starter that aggregates all Elioo AWS service libraries into a single dependency.

## What's Included

This starter automatically includes and configures:

- **elioo-aws-textract** - AWS Textract OCR service
- **elioo-aws-comprehend-medical** - AWS Comprehend Medical NLP service
- **elioo-aws-bedrock** - AWS Bedrock LLM service
- **elioo-aws-common** - Shared AWS infrastructure

## Quick Start

### 1. Add Dependency

**Gradle:**
```gradle
dependencies {
    implementation 'com.elioo.healthcare:elioo-aws-spring-boot-starter:0.1.0'
}
```

**Maven:**
```xml
<dependency>
    <groupId>com.elioo.healthcare</groupId>
    <artifactId>elioo-aws-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

### 2. Configure Properties

```yaml
# application.yml
aws:
  region: us-east-1
  access-key-id: ${AWS_ACCESS_KEY_ID}  # Optional: uses IAM roles if not provided
  secret-access-key: ${AWS_SECRET_ACCESS_KEY}  # Optional

  # Textract Configuration
  textract:
    enabled: true
    min-confidence-threshold: 0.80
    max-image-size-mb: 10

  # Comprehend Medical Configuration
  comprehend-medical:
    enabled: true
    min-confidence-threshold: 0.75
    max-text-length: 20000

  # Bedrock Configuration
  bedrock:
    enabled: true
    model-id: anthropic.claude-3-5-sonnet-20241022-v2:0
    max-tokens: 4096
    temperature: 0.7
```

### 3. Use Services

All services are auto-configured and ready to inject:

```java
@Service
@RequiredArgsConstructor
public class MedicalReportService {

    private final TextractService textractService;
    private final ComprehendMedicalService comprehendMedicalService;
    private final BedrockService bedrockService;

    public Mono<ProcessedReport> processReport(String imageBase64) {
        // OCR: Extract text from image
        return textractService.analyzeDocument(OcrRequest.standard(imageBase64))
            .flatMap(ocrResponse -> {
                String extractedText = ocrResponse.extractAllText();

                // NLP: Classify medical entities
                return comprehendMedicalService.detectEntities(
                    EntityDetectionRequest.standard(extractedText)
                );
            })
            .flatMap(entities -> {
                // LLM: Generate insights
                String prompt = buildPrompt(entities);
                return bedrockService.invokeClaude(prompt, "You are a medical AI assistant");
            })
            .map(this::buildReport);
    }
}
```

## Configuration Reference

### AWS Common Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `aws.region` | String | `us-east-1` | AWS region for all services |
| `aws.access-key-id` | String | - | AWS access key (optional, uses IAM if empty) |
| `aws.secret-access-key` | String | - | AWS secret key (optional, uses IAM if empty) |

### Textract Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `aws.textract.enabled` | Boolean | `true` | Enable/disable Textract service |
| `aws.textract.min-confidence-threshold` | Double | `0.80` | Minimum confidence for OCR results |
| `aws.textract.max-image-size-mb` | Integer | `10` | Maximum image size in MB |

### Comprehend Medical Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `aws.comprehend-medical.enabled` | Boolean | `true` | Enable/disable Comprehend Medical |
| `aws.comprehend-medical.min-confidence-threshold` | Double | `0.75` | Minimum entity confidence |
| `aws.comprehend-medical.max-text-length` | Integer | `20000` | Maximum text length |

### Bedrock Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `aws.bedrock.enabled` | Boolean | `true` | Enable/disable Bedrock service |
| `aws.bedrock.model-id` | String | `anthropic.claude-3-5-sonnet-20241022-v2:0` | LLM model ID |
| `aws.bedrock.max-tokens` | Integer | `4096` | Maximum tokens in response |
| `aws.bedrock.temperature` | Double | `0.7` | Model temperature (0.0-1.0) |

## Selective Service Enablement

You can enable only the services you need:

```yaml
aws:
  textract:
    enabled: true  # Enable OCR
  comprehend-medical:
    enabled: false  # Disable NLP
  bedrock:
    enabled: true  # Enable LLM
```

## IAM Permissions Required

Your AWS IAM role/user needs these permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "textract:DetectDocumentText",
        "textract:AnalyzeDocument",
        "comprehendmedical:DetectEntitiesV2",
        "comprehendmedical:InferICD10CM",
        "comprehendmedical:InferRxNorm",
        "bedrock:InvokeModel"
      ],
      "Resource": "*"
    }
  ]
}
```

## Version

- **Current Version:** 0.1.0
- **Requires:** Spring Boot 3.4.2+, Java 21+

## License

Proprietary - Elioo Healthcare

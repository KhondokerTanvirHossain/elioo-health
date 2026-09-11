# AWS Bedrock Configuration Guide

## Model ID vs Inference Profile

### ⚠️ Important Change (December 2024)

AWS Bedrock now requires using **inference profiles** instead of direct model IDs for on-demand throughput.

### ❌ Old Format (No longer works)
```properties
aws.bedrock.model-id=anthropic.claude-3-5-sonnet-20241022-v2:0
```

**Error you'll see:**
```
Invocation of model ID anthropic.claude-3-5-sonnet-20241022-v2:0 with on-demand throughput isn't supported.
Retry your request with the ID or ARN of an inference profile that contains this model.
```

### ✅ New Format (Correct)

#### Option 1: Cross-Region Inference Profile (Recommended)
```properties
# Best for production - uses multiple regions for higher availability
aws.bedrock.model-id=us.anthropic.claude-3-5-sonnet-20241022-v2:0
```

#### Option 2: Region-Specific Inference Profile
```properties
# Ties to specific region (e.g., us-east-1)
aws.bedrock.model-id=us-east-1.anthropic.claude-3-5-sonnet-20241022-v2:0
```

#### Option 3: Full ARN Format
```properties
# Full ARN (most explicit)
aws.bedrock.model-id=arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-3-5-sonnet-20241022-v2:0
```

---

## Configuration Files Updated

### 1. `application-aws.properties`
```properties
# Amazon Bedrock Configuration
# Note: Use cross-region inference profile ARN instead of direct model ID
# Format: arn:aws:bedrock:{region}::foundation-model/{model-id}
# For cross-region inference (recommended): us.anthropic.claude-3-5-sonnet-20241022-v2:0
aws.bedrock.model-id=us.anthropic.claude-3-5-sonnet-20241022-v2:0
aws.bedrock.max-tokens=4096
aws.bedrock.temperature=0.7
```

### 2. `BedrockAdapter.java`
```java
@Value("${aws.bedrock.model-id:us.anthropic.claude-3-5-sonnet-20241022-v2:0}")
private String modelId;
```

---

## Available Claude Models

### Claude 3.5 Sonnet (Recommended)
```properties
# Cross-region
aws.bedrock.model-id=us.anthropic.claude-3-5-sonnet-20241022-v2:0

# Region-specific
aws.bedrock.model-id=us-east-1.anthropic.claude-3-5-sonnet-20241022-v2:0
```

**Best for:** Complex medical reasoning, long context understanding

### Claude 3 Haiku (Faster, Cheaper)
```properties
# Cross-region
aws.bedrock.model-id=us.anthropic.claude-3-haiku-20240307-v1:0

# Region-specific
aws.bedrock.model-id=us-east-1.anthropic.claude-3-haiku-20240307-v1:0
```

**Best for:** Quick summaries, simple classifications

### Claude 3 Opus (Most Capable)
```properties
# Cross-region
aws.bedrock.model-id=us.anthropic.claude-3-opus-20240229-v1:0

# Region-specific
aws.bedrock.model-id=us-east-1.anthropic.claude-3-opus-20240229-v1:0
```

**Best for:** Highest accuracy requirements (more expensive)

---

## Inference Profile Benefits

### Cross-Region Profiles (Prefix: `us.`)
- ✅ **Higher availability** - Automatically routes to multiple regions
- ✅ **Better latency** - Uses closest available region
- ✅ **Automatic failover** - Falls back if one region is unavailable
- ✅ **Recommended for production**

### Region-Specific Profiles (Prefix: `{region}.`)
- ✅ **Data residency** - Keeps requests in specific region
- ✅ **Compliance** - Meet regional data requirements
- ⚠️ **Lower availability** - No automatic failover

---

## AWS Console Configuration

### Enabling Model Access

1. Go to **AWS Bedrock Console** → **Model access**
2. Click **"Manage model access"**
3. Enable:
   - ✅ Anthropic Claude 3.5 Sonnet
   - ✅ Anthropic Claude 3 Haiku (optional)
   - ✅ Anthropic Claude 3 Opus (optional)
4. Click **"Save changes"**

### Checking Inference Profiles

```bash
# List available inference profiles
aws bedrock list-inference-profiles --region us-east-1

# Example output:
{
  "inferenceProfileSummaries": [
    {
      "inferenceProfileName": "us.anthropic.claude-3-5-sonnet-20241022-v2:0",
      "modelId": "anthropic.claude-3-5-sonnet-20241022-v2:0",
      "description": "Cross-region inference profile for Claude 3.5 Sonnet",
      "status": "ACTIVE"
    }
  ]
}
```

---

## IAM Permissions Required

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "bedrock:InvokeModel",
        "bedrock:InvokeModelWithResponseStream"
      ],
      "Resource": [
        "arn:aws:bedrock:*::foundation-model/anthropic.claude-*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "bedrock:ListFoundationModels",
        "bedrock:GetFoundationModel",
        "bedrock:ListInferenceProfiles"
      ],
      "Resource": "*"
    }
  ]
}
```

---

## Troubleshooting

### Error: "Model not found"
**Solution:** Ensure model access is enabled in Bedrock console for your region.

### Error: "Access denied"
**Solution:** Add IAM permissions for `bedrock:InvokeModel`.

### Error: "Inference profile not supported"
**Solution:** Use cross-region profile format: `us.anthropic.claude-3-5-sonnet-20241022-v2:0`

### Error: "Throttling exception"
**Solution:**
- Increase retry backoff in configuration
- Consider provisioned throughput for high-volume applications
- Use Claude Haiku for less critical operations

---

## Testing Configuration

### Test with AWS CLI
```bash
aws bedrock-runtime invoke-model \
  --model-id us.anthropic.claude-3-5-sonnet-20241022-v2:0 \
  --region us-east-1 \
  --body '{"anthropic_version":"bedrock-2023-05-31","messages":[{"role":"user","content":"Hello"}],"max_tokens":100}' \
  output.json

cat output.json
```

### Test with Integration Tests
```bash
# Run Bedrock integration tests
./gradlew test --tests BedrockAdapterIntegrationTest
```

---

## Cost Optimization

### Model Pricing (Approximate, as of Dec 2024)

| Model | Input (per 1K tokens) | Output (per 1K tokens) | Best For |
|-------|----------------------|------------------------|----------|
| **Claude 3.5 Sonnet** | $0.003 | $0.015 | ⭐ General use |
| **Claude 3 Haiku** | $0.00025 | $0.00125 | 💰 Cost-sensitive |
| **Claude 3 Opus** | $0.015 | $0.075 | 🎯 Highest accuracy |

### Recommendations:
- Use **Haiku** for: Simple summaries, quick classifications
- Use **Sonnet** for: Complex medical reasoning (default)
- Use **Opus** for: Critical decisions requiring highest accuracy
- Configure `max-tokens` appropriately to control costs

---

## Common Issues and Fixes

### Claude Response Parsing

**Issue**: Claude returns JSON wrapped in objects rather than raw arrays.

**Example Response**:
```json
{
  "recommendations": [
    { "category": "...", "priority": "..." }
  ],
  "disclaimer": "..."
}
```

**Solution**: The `BedrockAdapter` handles both formats:
- Direct array: `[{ ... }]`
- Wrapped in object: `{ "recommendations": [{ ... }] }`

**Implementation** ([BedrockAdapter.java:558-559](src/main/java/com/elioo/healthcare/medicalreport/adapter/out/aws/BedrockAdapter.java#L558-L559)):
```java
// Claude may return recommendations as direct array or wrapped in object
JsonNode recommendationsNode = jsonNode.isArray() ? jsonNode : jsonNode.path("recommendations");
```

### Markdown Code Block Handling

Claude often wraps JSON in markdown code blocks:

```
Here are the recommendations:

```json
[...]
```
```

The `cleanJsonFromMarkdown()` method automatically strips these blocks before parsing.

---

## Migration Checklist

- [x] Update `application-aws.properties` with inference profile
- [x] Update `BedrockAdapter.java` default value
- [x] Test with AWS CLI
- [x] Run integration tests
- [x] Fix JSON response parsing
- [ ] Update production configuration
- [ ] Monitor CloudWatch metrics for errors

---

## References

- [AWS Bedrock Inference Profiles Documentation](https://docs.aws.amazon.com/bedrock/latest/userguide/inference-profiles.html)
- [Anthropic Claude on AWS Bedrock](https://docs.aws.amazon.com/bedrock/latest/userguide/model-parameters-anthropic-claude.html)
- [Bedrock API Reference](https://docs.aws.amazon.com/bedrock/latest/APIReference/welcome.html)

---

**Last Updated:** December 2024
**MedScribe AI Team**

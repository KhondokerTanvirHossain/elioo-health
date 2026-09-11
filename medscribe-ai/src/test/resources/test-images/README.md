# Test Images for OCR Integration Tests

This directory contains sample medical report images used for testing AWS Textract OCR functionality.

## Required Test Images

Place medical report images here for integration testing:

### 1. blood-test-report.png
- **Type**: Blood test report (CBC, metabolic panel, etc.)
- **Format**: PNG, JPG, or TIFF
- **Content**: Should contain test results in tabular format with:
  - Test names (e.g., "Hemoglobin", "Glucose", "Creatinine")
  - Test values (numeric)
  - Units (e.g., "mg/dL", "µmol/L")
  - Reference ranges (e.g., "Male: 59-104, Female: 45-84")

### 2. urine-test-report.png (Optional)
- **Type**: Urinalysis report
- **Format**: PNG, JPG, or TIFF

### 3. radiology-report.png (Optional)
- **Type**: Radiology imaging report (X-ray, CT, MRI findings)
- **Format**: PNG, JPG, or TIFF

## Image Requirements

### Quality
- **Resolution**: Minimum 150 DPI (300 DPI recommended)
- **Size**: Less than 10 MB
- **Format**: PNG, JPG, TIFF, or PDF

### Content
- Clear, legible text
- Proper contrast (dark text on light background)
- No significant blur or distortion
- Language: English or Bangla

## Sample Data Sources

You can obtain sample medical reports from:
1. **Public datasets**: Use anonymized medical reports from research datasets
2. **Generate synthetic reports**: Create mock reports using template generators
3. **Use provided templates**: See `test-templates/` directory (if available)

## Privacy & Security

⚠️ **IMPORTANT**:
- **Never use real patient data** in test images
- All test images must be **anonymized** or **synthetic**
- Remove all PHI (Protected Health Information):
  - Patient names
  - Date of birth
  - Medical record numbers
  - Phone numbers
  - Addresses

## Test Image Fallback

If no test images are provided, the integration test will use a minimal 1x1 pixel PNG image. This allows tests to run without errors but won't produce meaningful OCR results.

## Adding New Test Images

1. Place image file in this directory
2. Update test class to reference the new image
3. Ensure image is added to `.gitignore` if it contains any sensitive data

Example usage in test:
```java
private static final String TEST_IMAGE_PATH =
    "src/test/resources/test-images/blood-test-report.png";
```

## Example Test Image Structure

A good blood test report image should look like:

```
┌─────────────────────────────────────────────┐
│  BLOOD TEST REPORT                          │
│  Patient ID: TEST-001                       │
│  Date: 2024-01-15                          │
├─────────────────────────────────────────────┤
│ Test Name          | Value | Unit | Ref    │
├─────────────────────────────────────────────┤
│ Hemoglobin         | 14.5  | g/dL | 13-17  │
│ White Blood Count  | 7.2   | K/µL | 4-11   │
│ Platelet Count     | 250   | K/µL | 150-400│
│ Glucose (Fasting)  | 95    | mg/dL| 70-100 │
│ Creatinine         | 1.0   | mg/dL| 0.7-1.3│
└─────────────────────────────────────────────┘
```

---

*For questions about test data, contact the MedScribe AI development team.*

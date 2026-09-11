# Google Cloud Vision API - Complete Setup Guide

**For**: Developers new to Google Cloud Platform (GCP)
**Goal**: Get MedScribe AI running with GCP Vision API for Bangla OCR
**Time**: 30-45 minutes
**Difficulty**: Beginner-friendly

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Step 1: Create Google Cloud Account](#step-1-create-google-cloud-account)
3. [Step 2: Create a New Project](#step-2-create-a-new-project)
4. [Step 3: Enable Vision API](#step-3-enable-vision-api)
5. [Step 4: Create Service Account](#step-4-create-service-account)
6. [Step 5: Download Credentials](#step-5-download-credentials)
7. [Step 6: Configure MedScribe AI](#step-6-configure-medscribe-ai)
8. [Step 7: Test the Integration](#step-7-test-the-integration)
9. [Step 8: Deploy to Production](#step-8-deploy-to-production)
10. [Troubleshooting](#troubleshooting)
11. [Cost Estimation](#cost-estimation)

---

## Prerequisites

Before starting, ensure you have:

- ✅ A Google account (Gmail, Google Workspace, etc.)
- ✅ A valid credit/debit card (for GCP account verification - you won't be charged initially)
- ✅ MedScribe AI codebase cloned locally
- ✅ Java 21 installed (`java -version`)
- ✅ Gradle installed (or use `./gradlew`)
- ✅ A terminal/command prompt
- ✅ A text editor (VS Code, IntelliJ IDEA, etc.)

**Optional but recommended**:
- `gcloud` CLI tool (Google Cloud SDK) - [Installation guide](https://cloud.google.com/sdk/docs/install)
- `jq` for JSON formatting - [Installation guide](https://stedolan.github.io/jq/download/)

---

## Step 1: Create Google Cloud Account

### 1.1 Sign Up for Google Cloud

1. Go to [https://console.cloud.google.com](https://console.cloud.google.com)
2. Click **"Get started for free"** (or **"Try for Free"**)
3. Sign in with your Google account
4. Select your country: **Bangladesh** (or your location)
5. Read and accept the Terms of Service
6. Click **"Continue"**

### 1.2 Set Up Billing

1. Enter your billing information:
   - **Account type**: Individual or Business
   - **Name**: Your full name
   - **Address**: Your billing address in Bangladesh
   - **Payment method**: Credit card or debit card

2. Click **"Start my free trial"**

**🎉 You get $300 free credit valid for 90 days!**

**Important Notes**:
- You won't be charged during the free trial
- After free trial, you only pay for what you use (pay-as-you-go)
- Vision API has a generous free tier (1,000 requests/month)

### 1.3 Verify Account Activation

After setup, you should see the Google Cloud Console dashboard:

```
┌──────────────────────────────────────────────────┐
│  Google Cloud Console                            │
│  ────────────────────────────────────────────    │
│  Welcome to Google Cloud!                        │
│  Free trial credit: $300                         │
│  Days remaining: 90                              │
└──────────────────────────────────────────────────┘
```

---

## Step 2: Create a New Project

### 2.1 Why Create a Project?

In GCP, a **project** is a container for your resources (APIs, service accounts, billing, etc.). All your MedScribe AI resources will live in this project.

### 2.2 Create Project via Console

1. Click the **project dropdown** at the top (says "My First Project" or "Select a project")
2. Click **"NEW PROJECT"** button (top right)
3. Fill in project details:
   - **Project name**: `medscribe-ai-prod` (or any name you prefer)
   - **Project ID**: `medscribe-ai-prod-123456` (must be globally unique)
   - **Organization**: Leave as "No organization" (unless you have one)
   - **Location**: Leave as "No organization"
4. Click **"CREATE"**

**Wait 10-30 seconds** for project creation.

### 2.3 Verify Project Creation

1. Click the **project dropdown** again
2. You should see your new project: `medscribe-ai-prod`
3. Click on it to select it
4. The top bar should now show: `medscribe-ai-prod`

### 2.4 Note Your Project ID

**⚠️ IMPORTANT**: Copy your **Project ID** (e.g., `medscribe-ai-prod-123456`)

You'll need this later for configuration:

```bash
# Save this somewhere safe
PROJECT_ID=medscribe-ai-prod-123456
```

**Tip**: The Project ID is shown in the project dropdown and on the dashboard.

---

## Step 3: Enable Vision API

### 3.1 Why Enable the API?

By default, all Google Cloud APIs are disabled. You must explicitly enable Vision API to use it.

### 3.2 Enable via Console (Easy Way)

1. Go to [https://console.cloud.google.com/apis/library](https://console.cloud.google.com/apis/library)
2. Make sure your project is selected (top dropdown shows `medscribe-ai-prod`)
3. In the search box, type: **"Vision API"**
4. Click on **"Cloud Vision API"** (should be the first result)
5. Click the big **"ENABLE"** button

**Wait 10-30 seconds** for the API to enable.

### 3.3 Enable via gcloud CLI (Advanced Way)

If you have `gcloud` CLI installed:

```bash
# Set your project
gcloud config set project medscribe-ai-prod-123456

# Enable Vision API
gcloud services enable vision.googleapis.com

# Verify it's enabled
gcloud services list --enabled | grep vision
```

Expected output:
```
vision.googleapis.com     Cloud Vision API
```

### 3.4 Verify API is Enabled

1. Go to [https://console.cloud.google.com/apis/dashboard](https://console.cloud.google.com/apis/dashboard)
2. You should see **"Cloud Vision API"** in the list of enabled APIs
3. Click on it to see the dashboard (should show "API enabled")

**✅ Checkpoint**: Vision API is now enabled for your project!

---

## Step 4: Create Service Account

### 4.1 What is a Service Account?

A **service account** is like a robot user that your application uses to authenticate with Google Cloud. Instead of using your personal Google account, MedScribe AI will use this service account to call Vision API.

### 4.2 Create Service Account via Console

1. Go to [https://console.cloud.google.com/iam-admin/serviceaccounts](https://console.cloud.google.com/iam-admin/serviceaccounts)
2. Make sure your project is selected: `medscribe-ai-prod`
3. Click **"+ CREATE SERVICE ACCOUNT"** (top of page)

**Step 1: Service account details**
- **Service account name**: `medscribe-ai-vision`
- **Service account ID**: `medscribe-ai-vision` (auto-filled)
- **Description**: `Service account for MedScribe AI Vision API access`
- Click **"CREATE AND CONTINUE"**

**Step 2: Grant this service account access to project**
- Click the **"Select a role"** dropdown
- Search for: **"Cloud Vision"**
- Select: **"Cloud Vision API User"** (or `roles/cloudvision.user`)
- Click **"+ ADD ANOTHER ROLE"** (optional, for broader access)
- Search for: **"Service Account User"**
- Select: **"Service Account User"** (or `roles/iam.serviceAccountUser`)
- Click **"CONTINUE"**

**Step 3: Grant users access to this service account**
- Leave empty (skip this step)
- Click **"DONE"**

### 4.3 Verify Service Account Creation

You should now see your service account in the list:

```
Email: medscribe-ai-vision@medscribe-ai-prod-123456.iam.gserviceaccount.com
Display name: medscribe-ai-vision
```

**✅ Checkpoint**: Service account created with Vision API permissions!

---

## Step 5: Download Credentials

### 5.1 Create JSON Key

1. On the Service Accounts page, find your service account: `medscribe-ai-vision`
2. Click the **three dots** (⋮) on the right → **"Manage keys"**
3. Click **"ADD KEY"** dropdown → **"Create new key"**
4. Select key type: **JSON** (default)
5. Click **"CREATE"**

**A JSON file will download automatically** to your computer:

```
medscribe-ai-prod-123456-abcdef123456.json
```

**⚠️ SECURITY WARNING**:
- This file contains **secret credentials** - treat it like a password!
- **Never commit this file to Git** (add to `.gitignore`)
- **Never share this file publicly**
- Store it securely (encrypted folder, password manager, etc.)

### 5.2 Inspect the JSON Key

Open the downloaded file in a text editor. It should look like:

```json
{
  "type": "service_account",
  "project_id": "medscribe-ai-prod-123456",
  "private_key_id": "abc123def456...",
  "private_key": "-----BEGIN PRIVATE KEY-----\n...",
  "client_email": "medscribe-ai-vision@medscribe-ai-prod-123456.iam.gserviceaccount.com",
  "client_id": "123456789012345678901",
  "auth_uri": "https://accounts.google.com/o/oauth2/auth",
  "token_uri": "https://oauth2.googleapis.com/token",
  "auth_provider_x509_cert_url": "https://www.googleapis.com/oauth2/v1/certs",
  "client_x509_cert_url": "https://www.googleapis.com/robot/v1/metadata/x509/..."
}
```

**✅ Checkpoint**: JSON key downloaded and verified!

---

## Step 6: Configure MedScribe AI

### 6.1 Move Credentials to Secure Location

**Option A: Local Development (Recommended)**

```bash
# Create a secure directory
mkdir -p ~/.gcp

# Move the downloaded file
mv ~/Downloads/medscribe-ai-prod-*.json ~/.gcp/medscribe-ai-service-account.json

# Set restrictive permissions (Linux/macOS)
chmod 400 ~/.gcp/medscribe-ai-service-account.json

# Verify file exists
ls -la ~/.gcp/medscribe-ai-service-account.json
```

**Option B: Project Directory (Not recommended for production)**

```bash
# Navigate to project root
cd /path/to/medscribe-ai

# Create secrets directory (ignored by Git)
mkdir -p .secrets

# Move the file
mv ~/Downloads/medscribe-ai-prod-*.json .secrets/gcp-service-account.json

# Add to .gitignore (if not already there)
echo ".secrets/" >> .gitignore

# Verify
cat .gitignore | grep .secrets
```

### 6.2 Configure Application Properties

Navigate to your MedScribe AI project:

```bash
cd /path/to/medscribe-ai
```

The GCP configuration file already exists at:
```
medscribe-ai/src/main/resources/application-gcp.properties
```

**Edit the file** and update the following lines:

```properties
# ========================================================================
# GCP Vision API Configuration
# ========================================================================

# Provider Selection
ocr.provider=gcp

# GCP Project Configuration
gcp.project-id=medscribe-ai-prod-123456  # ← UPDATE THIS (your Project ID)

# Credentials Path
gcp.credentials-path=/Users/yourusername/.gcp/medscribe-ai-service-account.json  # ← UPDATE THIS

# Vision API Settings (default values - can customize)
gcp.vision.enabled=true
gcp.vision.min-confidence-threshold=0.80
gcp.vision.max-image-size-mb=20
gcp.vision.default-language-hints=bn,en  # Bangla + English
gcp.vision.timeout-ms=30000
```

**How to get your full credentials path**:

```bash
# Print full path
realpath ~/.gcp/medscribe-ai-service-account.json

# Or use pwd
cd ~/.gcp && pwd
# Output: /Users/tanvirhossain/.gcp
# Full path: /Users/tanvirhossain/.gcp/medscribe-ai-service-account.json
```

### 6.3 Set Environment Variables (Alternative Method)

Instead of hardcoding the path in properties, you can use environment variables:

**Linux/macOS**:
```bash
# Add to ~/.bashrc or ~/.zshrc
export GCP_PROJECT_ID=medscribe-ai-prod-123456
export GCP_CREDENTIALS_PATH=/Users/yourusername/.gcp/medscribe-ai-service-account.json
export SPRING_PROFILES_ACTIVE=gcp

# Reload
source ~/.bashrc  # or source ~/.zshrc
```

**Windows (PowerShell)**:
```powershell
$env:GCP_PROJECT_ID = "medscribe-ai-prod-123456"
$env:GCP_CREDENTIALS_PATH = "C:\Users\YourName\.gcp\medscribe-ai-service-account.json"
$env:SPRING_PROFILES_ACTIVE = "gcp"
```

Then your `application-gcp.properties` will use these variables:

```properties
gcp.project-id=${GCP_PROJECT_ID}
gcp.credentials-path=${GCP_CREDENTIALS_PATH}
```

**✅ Checkpoint**: MedScribe AI is now configured to use GCP Vision API!

---

## Step 7: Test the Integration

### 7.1 Build the Project

```bash
# Navigate to project root
cd /path/to/medscribe-ai

# Clean and build
./gradlew clean build -x test

# Expected output:
# BUILD SUCCESSFUL in 1m 30s
```

If build fails, see [Troubleshooting](#troubleshooting) section.

### 7.2 Run the Application

**Start with GCP profile**:

```bash
# Method 1: Using Gradle
./gradlew bootRun --args='--spring.profiles.active=gcp'

# Method 2: Using JAR
java -jar medscribe-ai/build/libs/medscribe-ai-*.jar --spring.profiles.active=gcp

# Method 3: Using environment variable
export SPRING_PROFILES_ACTIVE=gcp
./gradlew bootRun
```

**Look for these log messages** (indicates success):

```
INFO  --- [main] c.c.h.MedscribeAiApplication : The following profiles are active: gcp
INFO  --- [main] c.c.h.g.c.c.GcpCommonAutoConfiguration : Loaded GCP credentials from: /Users/...
INFO  --- [main] c.c.h.g.v.c.VisionAutoConfiguration : GCP Vision API auto-configuration enabled
INFO  --- [main] c.c.h.m.a.o.g.VisionAdapter : VisionAdapter initialized for OCR
INFO  --- [main] c.c.h.MedscribeAiApplication : Started MedscribeAiApplication in 12.345 seconds
```

**⚠️ If you see errors**, check:
- Project ID is correct
- Credentials path is correct and file exists
- Service account has Vision API permissions
- Vision API is enabled

### 7.3 Test with API Request

**Prepare a test image**:

1. Find a Bangla medical report image (or use a sample)
2. Convert to Base64:

```bash
# Linux/macOS
base64 -i test-report.jpg -o test-report.txt

# Or use online tool: https://base64.guru/converter/encode/image
```

3. Copy the Base64 string

**Test OCR endpoint**:

Create a file `test-ocr.http`:

```http
POST http://localhost:8086/api/v1/medical-report/ocr
Content-Type: application/json

{
  "imageBase64": "YOUR_BASE64_STRING_HERE",
  "patientId": "TEST123",
  "reportType": "BLOOD_TEST",
  "processingOptions": {
    "languageHints": ["bn", "en"]
  }
}
```

**Send request**:

```bash
# Using curl
curl -X POST http://localhost:8086/api/v1/medical-report/ocr \
  -H "Content-Type: application/json" \
  -d @test-ocr.http

# Using httpie (if installed)
http POST http://localhost:8086/api/v1/medical-report/ocr < test-ocr.http
```

**Expected response**:

```json
{
  "reportId": "RPT-...",
  "patientId": "TEST123",
  "extractedData": [
    {
      "testName": "Serum Creatinine",
      "testValue": "135.0",
      "unit": "µmol/L",
      "referenceRange": "Male: 59-104, Female: 45-84",
      "status": "ABNORMAL"
    }
  ],
  "confidence": 0.92,
  "processedAt": "2026-01-04T18:45:00Z"
}
```

**✅ Checkpoint**: GCP Vision API is working! You've successfully extracted Bangla text!

### 7.4 Verify in GCP Console

Check that API calls are being recorded:

1. Go to [https://console.cloud.google.com/apis/api/vision.googleapis.com/metrics](https://console.cloud.google.com/apis/api/vision.googleapis.com/metrics)
2. You should see **requests** in the chart (may take 1-2 minutes to appear)
3. Check **Traffic** → should show 1+ requests
4. Check **Errors** → should be 0 (if test succeeded)

**Check quota usage**:

1. Go to [https://console.cloud.google.com/apis/api/vision.googleapis.com/quotas](https://console.cloud.google.com/apis/api/vision.googleapis.com/quotas)
2. Look for **"Requests per day"**:
   - Limit: 1,000 (free tier) or higher
   - Usage: Should show 1+ requests

---

## Step 8: Deploy to Production

### 8.1 Environment-Specific Configuration

Create separate configuration files for each environment:

**Development** (`application-dev-gcp.properties`):
```properties
ocr.provider=gcp
gcp.project-id=medscribe-ai-dev
gcp.credentials-path=/etc/secrets/gcp-dev-service-account.json
gcp.vision.default-language-hints=bn,en
```

**Staging** (`application-staging-gcp.properties`):
```properties
ocr.provider=gcp
gcp.project-id=medscribe-ai-staging
gcp.credentials-path=/etc/secrets/gcp-staging-service-account.json
gcp.vision.default-language-hints=bn,en
```

**Production** (`application-prod-gcp.properties`):
```properties
ocr.provider=gcp
gcp.project-id=medscribe-ai-prod
gcp.credentials-path=/etc/secrets/gcp-prod-service-account.json
gcp.vision.default-language-hints=bn,en
gcp.vision.min-confidence-threshold=0.85
```

### 8.2 Docker Deployment

**Build Docker image**:

```bash
# Build the application
./gradlew bootJar

# Build Docker image
docker build -t medscribe-ai:1.0.0 .

# Test locally with GCP
docker run -d \
  --name medscribe-ai \
  -p 8086:8086 \
  -e SPRING_PROFILES_ACTIVE=gcp \
  -e GCP_PROJECT_ID=medscribe-ai-prod-123456 \
  -v ~/.gcp/medscribe-ai-service-account.json:/etc/gcp/service-account.json:ro \
  -e GCP_CREDENTIALS_PATH=/etc/gcp/service-account.json \
  medscribe-ai:1.0.0

# Check logs
docker logs -f medscribe-ai
```

### 8.3 Google Cloud Run (Serverless)

**Why Cloud Run?**
- Fully managed (no servers to maintain)
- Auto-scaling (scales to zero when not in use)
- Built-in GCP authentication (no JSON file needed!)
- Pay only for requests

**Deploy to Cloud Run**:

```bash
# Authenticate
gcloud auth login

# Set project
gcloud config set project medscribe-ai-prod-123456

# Enable Cloud Run API
gcloud services enable run.googleapis.com

# Build and deploy (Cloud Build)
gcloud run deploy medscribe-ai \
  --source . \
  --platform managed \
  --region us-central1 \
  --allow-unauthenticated \
  --service-account medscribe-ai-vision@medscribe-ai-prod-123456.iam.gserviceaccount.com \
  --set-env-vars "SPRING_PROFILES_ACTIVE=gcp,GCP_PROJECT_ID=medscribe-ai-prod-123456"

# Get the URL
gcloud run services describe medscribe-ai --region us-central1 --format 'value(status.url)'
```

**Advantages**:
- No need for JSON file (uses service account identity)
- Automatic HTTPS
- Global load balancing
- Built-in monitoring

### 8.4 Kubernetes Deployment

**Create Kubernetes secret**:

```bash
# Create namespace
kubectl create namespace medscribe-ai

# Create secret from JSON file
kubectl create secret generic gcp-credentials \
  --from-file=service-account.json=~/.gcp/medscribe-ai-service-account.json \
  --namespace medscribe-ai

# Verify
kubectl get secret gcp-credentials -n medscribe-ai
```

**Deploy application**:

```yaml
# k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: medscribe-ai
  namespace: medscribe-ai
spec:
  replicas: 3
  selector:
    matchLabels:
      app: medscribe-ai
  template:
    metadata:
      labels:
        app: medscribe-ai
    spec:
      containers:
      - name: medscribe-ai
        image: medscribe-ai:1.0.0
        ports:
        - containerPort: 8086
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "gcp"
        - name: GCP_PROJECT_ID
          value: "medscribe-ai-prod-123456"
        - name: GCP_CREDENTIALS_PATH
          value: /etc/gcp/service-account.json
        volumeMounts:
        - name: gcp-credentials
          mountPath: /etc/gcp
          readOnly: true
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
      volumes:
      - name: gcp-credentials
        secret:
          secretName: gcp-credentials
```

```bash
# Deploy
kubectl apply -f k8s/deployment.yaml

# Verify
kubectl get pods -n medscribe-ai
kubectl logs -f deployment/medscribe-ai -n medscribe-ai
```

### 8.5 Security Best Practices

**1. Use Workload Identity (GKE)**:

Instead of JSON files, use GKE Workload Identity:

```bash
# Create Kubernetes service account
kubectl create serviceaccount medscribe-ai-ksa \
  --namespace medscribe-ai

# Bind to GCP service account
gcloud iam service-accounts add-iam-policy-binding \
  medscribe-ai-vision@medscribe-ai-prod-123456.iam.gserviceaccount.com \
  --role roles/iam.workloadIdentityUser \
  --member "serviceAccount:medscribe-ai-prod-123456.svc.id.goog[medscribe-ai/medscribe-ai-ksa]"

# Annotate Kubernetes service account
kubectl annotate serviceaccount medscribe-ai-ksa \
  --namespace medscribe-ai \
  iam.gke.io/gcp-service-account=medscribe-ai-vision@medscribe-ai-prod-123456.iam.gserviceaccount.com
```

Update deployment to use Workload Identity (no JSON file needed):

```yaml
spec:
  template:
    spec:
      serviceAccountName: medscribe-ai-ksa  # Use K8s service account
      # No volumeMounts needed!
      containers:
      - name: medscribe-ai
        env:
        - name: GCP_PROJECT_ID
          value: "medscribe-ai-prod-123456"
        # GCP credentials will be automatically injected!
```

**2. Rotate Service Account Keys**:

```bash
# Create new key
gcloud iam service-accounts keys create new-key.json \
  --iam-account medscribe-ai-vision@medscribe-ai-prod-123456.iam.gserviceaccount.com

# Update application configuration
# (swap to new-key.json)

# Delete old key
gcloud iam service-accounts keys delete OLD_KEY_ID \
  --iam-account medscribe-ai-vision@medscribe-ai-prod-123456.iam.gserviceaccount.com
```

**3. Use Secret Manager**:

Store credentials in Google Secret Manager:

```bash
# Store JSON in Secret Manager
gcloud secrets create gcp-service-account \
  --data-file=~/.gcp/medscribe-ai-service-account.json

# Grant access to service account
gcloud secrets add-iam-policy-binding gcp-service-account \
  --member="serviceAccount:medscribe-ai-vision@medscribe-ai-prod-123456.iam.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"
```

Update application to fetch from Secret Manager (requires additional code).

---

## Troubleshooting

### Issue 1: "Invalid credentials" error

**Error**:
```
GcpConfigurationException: Failed to load credentials from /path/to/service-account.json
```

**Solutions**:

1. **Verify file exists**:
   ```bash
   ls -la ~/.gcp/medscribe-ai-service-account.json
   # Should show: -r-------- 1 user group 2345 Jan  4 10:00 ...
   ```

2. **Check file permissions**:
   ```bash
   chmod 400 ~/.gcp/medscribe-ai-service-account.json
   ```

3. **Validate JSON format**:
   ```bash
   cat ~/.gcp/medscribe-ai-service-account.json | jq .
   # Should print formatted JSON (no errors)
   ```

4. **Verify path in properties**:
   ```bash
   grep credentials-path medscribe-ai/src/main/resources/application-gcp.properties
   # Should match actual file path
   ```

### Issue 2: "Permission denied" error

**Error**:
```
GcpServiceException: The caller does not have permission (code: 403)
```

**Solutions**:

1. **Check service account has Vision API role**:
   ```bash
   gcloud projects get-iam-policy medscribe-ai-prod-123456 \
     --flatten="bindings[].members" \
     --format="table(bindings.role)" \
     --filter="bindings.members:medscribe-ai-vision@medscribe-ai-prod-123456.iam.gserviceaccount.com"
   ```
   Should show: `roles/cloudvision.user`

2. **Add role if missing**:
   ```bash
   gcloud projects add-iam-policy-binding medscribe-ai-prod-123456 \
     --member="serviceAccount:medscribe-ai-vision@medscribe-ai-prod-123456.iam.gserviceaccount.com" \
     --role="roles/cloudvision.user"
   ```

3. **Verify Vision API is enabled**:
   ```bash
   gcloud services list --enabled | grep vision
   # Should show: vision.googleapis.com
   ```

4. **Enable if not enabled**:
   ```bash
   gcloud services enable vision.googleapis.com
   ```

### Issue 3: "Project ID not found"

**Error**:
```
GcpConfigurationException: Project ID not configured
```

**Solutions**:

1. **Check environment variable**:
   ```bash
   echo $GCP_PROJECT_ID
   # Should print your project ID
   ```

2. **Check application.properties**:
   ```bash
   grep project-id medscribe-ai/src/main/resources/application-gcp.properties
   # Should show: gcp.project-id=medscribe-ai-prod-123456
   ```

3. **Verify project exists**:
   ```bash
   gcloud projects describe medscribe-ai-prod-123456
   # Should show project details
   ```

### Issue 4: "Quota exceeded" error

**Error**:
```
GcpServiceException: Quota exceeded for quota metric 'vision.googleapis.com/requests' (code: 429)
```

**Solutions**:

1. **Check current quota usage**:
   - Go to: [https://console.cloud.google.com/apis/api/vision.googleapis.com/quotas](https://console.cloud.google.com/apis/api/vision.googleapis.com/quotas)
   - Look for "Requests per day": should show current/limit

2. **Request quota increase**:
   - Click quota name → "EDIT QUOTAS"
   - Fill in form requesting higher limit
   - Wait 1-2 business days for approval

3. **Implement rate limiting**:
   ```java
   @Configuration
   public class RateLimiterConfig {
       @Bean
       public RateLimiter visionRateLimiter() {
           return RateLimiter.create(10.0);  // 10 requests/second
       }
   }
   ```

### Issue 5: OCR returns empty text

**Symptoms**:
```json
{
  "fullText": "",
  "blocks": []
}
```

**Solutions**:

1. **Verify image is valid**:
   ```bash
   # Decode and view
   base64 -d test.txt > test.jpg
   open test.jpg  # macOS
   # or
   xdg-open test.jpg  # Linux
   ```

2. **Check image format**: Only JPEG, PNG, GIF, BMP, WEBP, RAW, ICO, PDF, TIFF supported

3. **Improve image quality**:
   - Use higher resolution (300+ DPI)
   - Ensure good lighting
   - Reduce noise/blur
   - Make sure text is horizontal

4. **Check language hints**:
   ```json
   {
     "languageHints": ["bn", "en"]  // Must match document language
   }
   ```

### Issue 6: Build fails with "Module not found"

**Error**:
```
Could not find project :elioo-gcp-vision
```

**Solutions**:

1. **Verify settings.gradle includes GCP modules**:
   ```bash
   grep gcp settings.gradle
   # Should show:
   # include 'elioo-gcp-common'
   # include 'elioo-gcp-vision'
   # include 'elioo-gcp-spring-boot-starter'
   ```

2. **Clean and rebuild**:
   ```bash
   ./gradlew clean build --refresh-dependencies
   ```

3. **Check module structure**:
   ```bash
   ls -la elioo-gcp-*/build.gradle
   # Should list all 3 build.gradle files
   ```

### Get Help

If you're still stuck:

1. **Check application logs**:
   ```bash
   tail -f /tmp/logs/medscribe-ai.log
   ```

2. **Enable debug logging**:
   ```properties
   logging.level.com.elioo.healthcare.gcp=DEBUG
   logging.level.com.google.cloud.vision=DEBUG
   ```

3. **Test with gcloud CLI**:
   ```bash
   # Test authentication
   gcloud auth application-default login

   # Test Vision API directly
   gcloud ml vision detect-text test-image.jpg
   ```

4. **Contact support**:
   - Email: support@elioo.health
   - GitLab issues: https://github.com/KhondokerTanvirHossain/elioo-health/-/issues

---

## Cost Estimation

### Free Tier

**Google Cloud Vision API** offers generous free tier:
- **First 1,000 requests/month**: FREE
- Applies to all Google Cloud accounts (no time limit)

### Paid Pricing (after free tier)

| Feature | Price per 1,000 units |
|---------|----------------------|
| **DOCUMENT_TEXT_DETECTION** | $1.50 |
| **TEXT_DETECTION** | $1.50 |
| **Image Quality** | Free |

### Example Calculations

**Scenario 1: Small clinic (100 reports/day)**
- Monthly requests: 100 × 30 = 3,000
- Free tier: 1,000 requests = $0.00
- Paid: 2,000 requests = 2 × $1.50 = **$3.00/month**

**Scenario 2: Medium hospital (500 reports/day)**
- Monthly requests: 500 × 30 = 15,000
- Free tier: 1,000 requests = $0.00
- Paid: 14,000 requests = 14 × $1.50 = **$21.00/month**

**Scenario 3: Large hospital (2,000 reports/day)**
- Monthly requests: 2,000 × 30 = 60,000
- Free tier: 1,000 requests = $0.00
- Paid: 59,000 requests = 59 × $1.50 = **$88.50/month**

### Cost Optimization Tips

1. **Cache results**: Don't re-process same image
   ```java
   @Cacheable("ocr-results")
   public Mono<VisionOcrResponse> processOcr(String imageHash, String image) {
       // Only calls Vision API on cache miss
   }
   ```

2. **Batch processing**: Process multiple images in single request (not supported by Vision API, but batch by time window)

3. **Use image quality check**: Don't process low-quality images
   ```java
   visionService.validateImageQuality(image)
       .filter(ImageQualityResult::isValid)
       .flatMap(q -> visionService.detectDocumentText(request))
   ```

4. **Set quota alerts**: Get notified before hitting limits
   ```bash
   gcloud alpha billing budgets create \
     --billing-account=BILLING_ACCOUNT_ID \
     --display-name="Vision API Budget" \
     --budget-amount=10USD \
     --threshold-rule=percent=50,basis=current-spend
   ```

### Monitor Costs

**Via Console**:
1. Go to: [https://console.cloud.google.com/billing](https://console.cloud.google.com/billing)
2. Select your billing account
3. Click "Reports" → filter by "Vision API"
4. View daily/monthly costs

**Via CLI**:
```bash
gcloud billing accounts list
gcloud alpha billing accounts get-spend-data \
  --billing-account=BILLING_ACCOUNT_ID
```

---

## Summary

**🎉 Congratulations!** You've successfully:

✅ Created a Google Cloud account with $300 free credit
✅ Created a project: `medscribe-ai-prod`
✅ Enabled Cloud Vision API
✅ Created a service account with Vision API permissions
✅ Downloaded and secured JSON credentials
✅ Configured MedScribe AI to use GCP Vision
✅ Tested Bangla OCR locally
✅ Learned deployment options (Docker, Cloud Run, Kubernetes)
✅ Understood costs and quotas

**Next Steps**:

1. **Test with real Bangla medical reports**: Upload actual reports and verify OCR accuracy
2. **Fine-tune language hints**: Experiment with `["bn", "en"]` vs `["en", "bn"]` for your use case
3. **Set up monitoring**: Configure alerts for errors, quota, and costs
4. **Deploy to production**: Choose Cloud Run (easiest) or Kubernetes (most control)
5. **Implement caching**: Reduce API calls and costs
6. **Review security**: Rotate keys, use Workload Identity, audit permissions

**Resources**:
- [GCP_VISION_INTEGRATION.md](GCP_VISION_INTEGRATION.md) - Technical documentation
- [GCP Vision API Docs](https://cloud.google.com/vision/docs) - Official Google docs
- [Bangla Language Support](https://cloud.google.com/vision/docs/languages#bengali) - Language-specific guide
- [Best Practices](https://cloud.google.com/vision/docs/best-practices) - Performance tips

**Need Help?**
- Email: support@elioo.health
- GitLab: https://github.com/KhondokerTanvirHossain/elioo-health/-/issues

---

**Happy coding! 🚀**

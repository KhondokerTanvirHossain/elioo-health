# MedScribe AI - Embedded Credentials Deployment Guide

This guide explains how to deploy MedScribe AI with **GCP credentials embedded in the Docker image**. This is the simplest deployment approach where credentials are baked into the image during CI/CD build.

## 🔑 Key Changes Made

### 1. Dockerfile Updates
- **Line 62**: Added `COPY --from=builder /workspace/medscribe-ai/src/main/resources/gcp /workspace/app/gcp`
- GCP credentials are now copied into the runtime image at classpath location

### 2. Production Properties Updates
- **File**: `medscribe-ai/src/main/resources/application-prod.properties`
- **Added**:
  ```properties
  gcp.enabled=true
  gcp.project-id=<YOUR_GCP_PROJECT>
  gcp.common.credentials-path=classpath:gcp/gcp-credentials.json
  ```

## 🚀 Deployment Steps

### Step 1: Build Docker Image (CI/CD)

Your CI/CD pipeline will build the image with embedded credentials:

```bash
docker build -t medscribe-ai:v1 .
```

**What happens:**
1. Gradle builds all modules including GCP integration
2. GCP credentials from `medscribe-ai/src/main/resources/gcp/` are copied into the image
3. Credentials become available at `classpath:gcp/gcp-credentials.json`
4. Application reads credentials automatically on startup

### Step 2: Deploy to EC2

**Option A: Using Deployment Script (Recommended)**

```bash
# SSH into EC2
ssh -i "<YOUR_SSH_KEY>.pem" ec2-user@ec2-13-205-14-249.ap-south-1.compute.amazonaws.com

# Upload deployment script
scp -i "<YOUR_SSH_KEY>.pem" deploy-ec2-simple.sh ec2-user@ec2-13-205-14-249.ap-south-1.compute.amazonaws.com:/home/ec2-user/

# Set environment variables
export PGHOST="<YOUR_SERVER_IP>"
export PGDATABASE="medscribe"
export PGUSER="developer"
export PGPASSWORD="your-password"

# Run deployment script
chmod +x deploy-ec2-simple.sh
./deploy-ec2-simple.sh
```

**Option B: Manual Docker Run**

```bash
# Stop old container
docker stop medscribe-ai-container 2>/dev/null || true
docker rm medscribe-ai-container 2>/dev/null || true

# Run new container
docker run -d \
  --name medscribe-ai-container \
  --restart unless-stopped \
  -p 8086:8086 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e PGHOST="<YOUR_SERVER_IP>" \
  -e PGDATABASE="medscribe" \
  -e PGUSER="developer" \
  -e PGPASSWORD="your-password" \
  -e FEATURE_FLAGGING_SERVICE_URL="http://localhost:8087" \
  -e SPRING_ADMIN_SERVER_URL="http://localhost:8080" \
  medscribe-ai:v1

# Check health
sleep 30
curl http://localhost:8086/actuator/health
docker logs --tail 50 medscribe-ai-container
```

## 🔍 Verification

### 1. Check Application Health

```bash
curl http://localhost:8086/actuator/health
```

**Expected Response:**
```json
{
  "status": "UP",
  "components": {
    "diskSpace": {"status": "UP"},
    "ping": {"status": "UP"},
    "r2dbc": {"status": "UP"}
  }
}
```

### 2. Check GCP Credentials Loading

```bash
docker logs medscribe-ai-container | grep -i "gcp"
```

**Expected Logs:**
```
[INFO] Initializing GCP credentials
[INFO] Loading GCP credentials from file: classpath:gcp/gcp-credentials.json
[INFO] Successfully loaded GCP credentials from classpath: gcp/gcp-credentials.json
```

### 3. Test Translation API

```bash
curl -X POST http://localhost:8086/api/v1/medical-report/process-multi-image \
  -H "Content-Type: application/json" \
  -d '{
    "images": ["base64-encoded-image"],
    "patientContext": {
      "patientId": "P12345",
      "age": 45,
      "gender": "MALE"
    }
  }'
```

## 🛠️ Troubleshooting

### Issue 1: Credentials Not Found

**Error:**
```
java.io.IOException: Classpath resource not found: gcp/gcp-credentials.json
```

**Solution:**
1. Rebuild Docker image to ensure credentials are copied:
   ```bash
   docker build -t medscribe-ai:v1 .
   ```
2. Verify credentials exist in image:
   ```bash
   docker run --rm medscribe-ai:v1 ls -la /workspace/app/gcp/
   ```

### Issue 2: Database Connection Failed

**Error:**
```
Unable to connect to database
```

**Solution:**
- Verify environment variables are set correctly:
  ```bash
  docker exec medscribe-ai-container env | grep PG
  ```
- Check database is accessible from EC2:
  ```bash
  psql -h $PGHOST -U $PGUSER -d $PGDATABASE
  ```

### Issue 3: Translation Not Working

**Check GCP API Enabled:**
1. Go to Google Cloud Console
2. Navigate to "APIs & Services" → "Enabled APIs"
3. Verify these are enabled:
   - Cloud Translation API
   - Cloud Vision API

**Check Logs for GCP Errors:**
```bash
docker logs medscribe-ai-container | grep -i "translation\|gcp"
```

## 📊 Monitoring

### View Real-time Logs

```bash
docker logs -f medscribe-ai-container
```

### Filter Specific Logs

```bash
# Translation logs
docker logs -f medscribe-ai-container | grep "TRANSLATION"

# GCP logs
docker logs -f medscribe-ai-container | grep "GCP"

# Error logs
docker logs -f medscribe-ai-container | grep "ERROR"
```

### Check Resource Usage

```bash
docker stats medscribe-ai-container
```

## 🔄 Updates and Redeployment

### Update Application

```bash
# Stop and remove old container
docker stop medscribe-ai-container
docker rm medscribe-ai-container

# Pull new image from registry
docker pull your-registry/medscribe-ai:v2

# Re-run deployment script
./deploy-ec2-simple.sh
```

### Update GCP Credentials

**Option 1: Rebuild Image (Recommended)**
1. Update credentials in `medscribe-ai/src/main/resources/gcp/`
2. Commit and push to trigger CI/CD build
3. Deploy new image to EC2

**Option 2: Quick Fix with Volume Mount**
```bash
# Upload new credentials to EC2
scp new-creds.json ec2-user@ec2:/home/ec2-user/gcp-credentials/

# Run container with volume mount override
docker run -d \
  --name medscribe-ai-container \
  -v /home/ec2-user/gcp-credentials/new-creds.json:/workspace/app/gcp/gcp-credentials.json:ro \
  ... other options ...
  medscribe-ai:v1
```

## ⚖️ Embedded vs External Credentials

### Embedded Credentials (This Approach)
✅ **Pros:**
- Simplest deployment - no volume mounts needed
- Credentials always available in image
- Works consistently across environments

❌ **Cons:**
- Credentials visible in image (security risk)
- Requires image rebuild to rotate credentials
- Not suitable for public registries

### External Credentials (Alternative)
✅ **Pros:**
- Better security - credentials not in image
- Easy credential rotation without rebuild
- Can use different credentials per environment

❌ **Cons:**
- Requires volume mount or environment variables
- More complex deployment
- Need to manage credentials separately

## 🔒 Security Considerations

### 1. Protect Docker Images
- **Never push images with credentials to public registries**
- Use private Docker registry (AWS ECR, Docker Hub Private, etc.)
- Enable registry scanning for vulnerabilities

### 2. Access Control
- Restrict who can access EC2 instances
- Use IAM roles and security groups
- Enable CloudWatch logging

### 3. Credential Rotation
- Rotate GCP service account keys every 90 days
- Delete old keys from GCP Console after rotation
- Rebuild and redeploy images with new credentials

### 4. GitIgnore Configuration
Ensure `.gitignore` includes:
```
**/gcp/*.json
**/*-credentials.json
*.pem
```

## 📚 Additional Resources

- [GCP Service Account Best Practices](https://cloud.google.com/iam/docs/best-practices-service-accounts)
- [Docker Security Best Practices](https://docs.docker.com/develop/security-best-practices/)
- [Spring Boot Production Features](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)

## 🆘 Support

For issues or questions:
1. Check application logs: `docker logs medscribe-ai-container`
2. Review this deployment guide
3. Check [DEPLOYMENT_EC2.md](DEPLOYMENT_EC2.md) for alternative deployment methods
4. Contact the development team

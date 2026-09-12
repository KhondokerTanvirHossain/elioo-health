# MedScribe AI - EC2 Deployment Guide

This guide explains how to deploy MedScribe AI to AWS EC2 using Docker with proper GCP credentials management.

## 🚀 Quick Start

### Step 1: Upload GCP Credentials to EC2

```bash
# From your local machine
scp medscribe-ai/src/main/resources/gcp/gcp-credentials.json \
    ec2-user@your-ec2-ip:/home/ec2-user/gcp-credentials/
```

### Step 2: Set Environment Variables on EC2

```bash
# SSH into EC2
ssh ec2-user@your-ec2-ip

# Export database credentials
export PGHOST="your-db-host"
export PGDATABASE="medscribe"
export PGUSER="developer"
export PGPASSWORD="your-password"

# Optional: Other services
export FEATURE_FLAGGING_SERVICE_URL="http://localhost:8087"
export SPRING_ADMIN_SERVER_URL="http://localhost:8080"
```

### Step 3: Run Deployment Script

```bash
# Make script executable
chmod +x deploy-ec2.sh

# Run deployment
./deploy-ec2.sh
```

## 📋 Manual Deployment

If you prefer to run Docker manually:

### Option 1: Using Volume Mount (Recommended)

```bash
docker run -d \
  --name medscribe-ai-container \
  --restart unless-stopped \
  -p 8086:8086 \
  \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e GOOGLE_APPLICATION_CREDENTIALS=/secrets/gcp-credentials.json \
  \
  -e PGHOST="your-db-host" \
  -e PGDATABASE="medscribe" \
  -e PGUSER="developer" \
  -e PGPASSWORD="your-password" \
  \
  -v /home/ec2-user/gcp-credentials/gcp-credentials.json:/secrets/gcp-credentials.json:ro \
  \
  medscribe-ai:v1
```

### Option 2: Using Inline JSON

```bash
# Read credentials into environment variable
export GCP_CREDS=$(cat /home/ec2-user/gcp-credentials/gcp-credentials.json)

docker run -d \
  --name medscribe-ai-container \
  --restart unless-stopped \
  -p 8086:8086 \
  \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e GCP_COMMON_CREDENTIALS_JSON="$GCP_CREDS" \
  \
  -e PGHOST="your-db-host" \
  -e PGDATABASE="medscribe" \
  -e PGUSER="developer" \
  -e PGPASSWORD="your-password" \
  \
  medscribe-ai:v1
```

## 🔍 Verification

### Check Application Health

```bash
curl http://localhost:8086/actuator/health
```

Expected response:
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

### Check GCP Credentials Loading

```bash
docker logs medscribe-ai-container | grep -i "gcp"
```

Expected logs:
```
[INFO] Initializing GCP credentials
[INFO] Using GCP Application Default Credentials (ADC)
[INFO] GCP credentials loaded successfully
```

### Test Translation API

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

### Issue 1: GCP Credentials Not Found

**Error:**
```
java.io.IOException: Classpath resource not found: gcp/gcp-credentials.json
```

**Solution:**
- Ensure GCP credentials file is uploaded to EC2
- Verify volume mount path is correct
- Check file permissions: `ls -la /home/ec2-user/gcp-credentials/`

### Issue 2: Permission Denied

**Error:**
```
Failed to load GCP credentials: /secrets/gcp-credentials.json (Permission denied)
```

**Solution:**
```bash
# Fix file permissions on EC2
chmod 644 /home/ec2-user/gcp-credentials/gcp-credentials.json
```

### Issue 3: Database Connection Failed

**Error:**
```
Unable to connect to database
```

**Solution:**
- Verify database is accessible from EC2
- Check security group allows inbound traffic on PostgreSQL port (5432)
- Test connection: `psql -h $PGHOST -U $PGUSER -d $PGDATABASE`

### Issue 4: Translation API Not Working

**Check GCP API Enabled:**
1. Go to Google Cloud Console
2. Navigate to "APIs & Services" → "Enabled APIs"
3. Verify these are enabled:
   - Cloud Translation API
   - Cloud Vision API

**Check Credentials Have Permissions:**
```bash
# Inside container
curl -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  https://translation.googleapis.com/language/translate/v2/languages
```

## 📊 Monitoring

### View Real-time Logs

```bash
docker logs -f medscribe-ai-container
```

### Filter Translation Logs

```bash
docker logs -f medscribe-ai-container | grep "TRANSLATION"
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

# Pull new image
docker pull your-registry/medscribe-ai:v2

# Re-run deployment script
./deploy-ec2.sh
```

### Update GCP Credentials

```bash
# Upload new credentials
scp new-credentials.json ec2-user@your-ec2-ip:/home/ec2-user/gcp-credentials/

# Restart container
docker restart medscribe-ai-container
```

## 🔒 Security Best Practices

1. **Never commit credentials to Git:**
   ```bash
   # Add to .gitignore
   **/gcp/*.json
   **/*-credentials.json
   ```

2. **Restrict file permissions:**
   ```bash
   chmod 600 /home/ec2-user/gcp-credentials/*.json
   ```

3. **Use IAM roles for EC2 (Alternative):**
   - Attach IAM role to EC2 instance
   - Grant role permissions to access GCP services
   - Remove credentials file entirely

4. **Rotate credentials regularly:**
   - Create new service account key every 90 days
   - Delete old keys from GCP Console

## 📚 Additional Resources

- [GCP Service Account Best Practices](https://cloud.google.com/iam/docs/best-practices-service-accounts)
- [Docker Security Best Practices](https://docs.docker.com/develop/security-best-practices/)
- [Spring Boot Production-Ready Features](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)

## 🆘 Support

For issues or questions:
1. Check application logs: `docker logs medscribe-ai-container`
2. Review this deployment guide
3. Contact the development team

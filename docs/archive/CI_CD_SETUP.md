# MedScribe AI - CI/CD Setup Guide

This guide explains the automated CI/CD deployment setup using GitLab CI/CD with `.env` file configuration.

## 🎯 Overview

**Deployment Flow:**
1. Developer pushes code to `dev` branch
2. GitLab CI/CD builds Docker image
3. GitLab CI/CD pushes image to Docker Hub
4. GitLab CI/CD SSHs into dev server and runs `deploy.sh`
5. Deploy script reads `.env` file and deploys the application
6. Application uses volume-mounted GCP credentials from server

**Key Features:**
- ✅ Fully automated - no manual steps
- ✅ Configuration via `.env` file
- ✅ Credentials stored securely on server (not in image)
- ✅ Easy to update environment variables
- ✅ Works with existing credentials on server

## 📋 One-Time Server Setup

Run these commands **once** on your dev server (`<YOUR_SERVER_IP>`):

### Step 1: Upload Deploy Script

```bash
# On your local machine
scp -i "<YOUR_SSH_KEY>.pem" deploy.sh ec2-user@<YOUR_SERVER_IP>:/home/ec2-user/
```

### Step 2: Create .env File on Server

```bash
# SSH into server
ssh -i "<YOUR_SSH_KEY>.pem" ec2-user@<YOUR_SERVER_IP>

# Create .env file
cat > /home/ec2-user/.env << 'EOF'
# Database Configuration
PGHOST=<YOUR_SERVER_IP>
PGDATABASE=medscribe
PGUSER=<YOUR_DB_USER>
PGPASSWORD=<YOUR_DB_PASSWORD>

# GCP Credentials Path (already on server)
GCP_CREDENTIALS_PATH=/home/ec2-user/gcp-credentials/gcp-credentials.json

# Application Configuration
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8086

# Optional Services
FEATURE_FLAGGING_SERVICE_URL=http://localhost:8087
SPRING_ADMIN_SERVER_URL=http://localhost:8080
MEDSCRIBEAI_SERVICE_URL=http://localhost:8086
ADMIN_SERVER_USERNAME=admin
ADMIN_SERVER_PASS=<YOUR_ADMIN_PASSWORD>

# Docker Configuration
DOCKER_IMAGE=<YOUR_DOCKERHUB_USER>/medscribe-ai
IMAGE_TAG=latest
CONTAINER_NAME=medscribe-ai-container
EOF

# Make deploy script executable
chmod +x /home/ec2-user/deploy.sh
```

### Step 3: Verify GCP Credentials Exist

```bash
# Check credentials file
ls -la /home/ec2-user/gcp-credentials/gcp-credentials.json

# Should show:
# -rw-r--r-- 1 ec2-user ec2-user 2390 Jan 7 gcp-credentials.json
```

## 🚀 How CI/CD Works

### GitLab CI/CD Pipeline (`.gitlab-ci.yml`)

```yaml
stages:
  - build
  - deploy

build-job:
  stage: build
  script:
    - docker build -t <YOUR_DOCKERHUB_USER>/medscribe-ai:latest .
    - docker push <YOUR_DOCKERHUB_USER>/medscribe-ai:latest
  only:
    - dev

deploy-job:
  stage: deploy
  script:
    - ssh ec2-user@<YOUR_SERVER_IP> "bash /home/ec2-user/deploy.sh"
  only:
    - dev
```

### Deploy Script (`deploy.sh`)

The script automatically:
1. **Loads `.env` file** - Reads all configuration
2. **Verifies GCP credentials** - Checks file exists
3. **Pulls latest image** - Gets newest Docker image
4. **Stops old container** - Removes existing deployment
5. **Starts new container** - Runs with volume mount for credentials
6. **Health check** - Verifies application started
7. **Shows logs** - Displays startup logs

## 🔄 Daily Workflow (Fully Automated)

### For Developers:

```bash
# 1. Make code changes
git add .
git commit -m "feat: your feature"

# 2. Push to dev branch
git push origin dev

# 3. That's it! CI/CD does the rest automatically
```

### What Happens Automatically:

1. **GitLab CI/CD Triggers** (on push to `dev`)
2. **Build Stage** (5-10 minutes)
   - Gradle builds all modules
   - Docker image created
   - Image pushed to Docker Hub: `<YOUR_DOCKERHUB_USER>/medscribe-ai:latest`
3. **Deploy Stage** (1-2 minutes)
   - SSH into `<YOUR_SERVER_IP>`
   - Run `/home/ec2-user/deploy.sh`
   - Pull latest image
   - Stop old container
   - Start new container with volume-mounted credentials
   - Health check
4. **Deployment Complete** ✅

## 📊 Monitoring Deployment

### View CI/CD Pipeline

1. Go to GitLab project
2. Click "CI/CD" → "Pipelines"
3. See build and deploy progress in real-time

### View Application Logs on Server

```bash
# SSH into server
ssh -i "<YOUR_SSH_KEY>.pem" ec2-user@<YOUR_SERVER_IP>

# View real-time logs
docker logs -f medscribe-ai-container

# View last 100 lines
docker logs --tail 100 medscribe-ai-container

# Filter for errors
docker logs medscribe-ai-container | grep ERROR

# Filter for GCP/translation
docker logs medscribe-ai-container | grep -i "gcp\|translation"
```

### Check Application Health

```bash
# From server
curl http://localhost:8086/actuator/health

# From your machine (if security group allows)
curl http://<YOUR_SERVER_IP>:8086/actuator/health
```

## 🔧 Updating Configuration

### Update Environment Variables

```bash
# SSH into server
ssh -i "<YOUR_SSH_KEY>.pem" ec2-user@<YOUR_SERVER_IP>

# Edit .env file
nano /home/ec2-user/.env

# Restart container to apply changes
docker restart medscribe-ai-container
```

### Update Database Password

```bash
# Edit .env
nano /home/ec2-user/.env
# Change: PGPASSWORD=new-password

# Restart
docker restart medscribe-ai-container
```

### Update GCP Credentials

```bash
# Upload new credentials
scp -i "<YOUR_SSH_KEY>.pem" new-creds.json ec2-user@<YOUR_SERVER_IP>:/home/ec2-user/gcp-credentials/

# Update .env if filename changed
nano /home/ec2-user/.env
# Update: GCP_CREDENTIALS_PATH=/home/ec2-user/gcp-credentials/new-creds.json

# Restart
docker restart medscribe-ai-container
```

## 🛠️ Troubleshooting

### Issue 1: CI/CD Build Failed

**Check:**
```bash
# View GitLab pipeline logs
# Go to GitLab → CI/CD → Pipelines → Click failed job
```

**Common Causes:**
- Gradle compilation errors
- Docker build errors
- Missing environment variables in GitLab

### Issue 2: CI/CD Deploy Failed

**Check:**
```bash
# View deploy job logs in GitLab
```

**Common Causes:**
- SSH key not configured in GitLab variables
- Server unreachable
- deploy.sh not found on server

### Issue 3: Application Not Starting

**Check logs:**
```bash
ssh -i "<YOUR_SSH_KEY>.pem" ec2-user@<YOUR_SERVER_IP>
docker logs medscribe-ai-container
```

**Common Causes:**
- Database connection failed (check PGHOST, PGUSER, PGPASSWORD)
- GCP credentials not found (check GCP_CREDENTIALS_PATH)
- Port already in use

### Issue 4: GCP Credentials Not Loading

**Verify:**
```bash
# Check credentials file exists
ls -la /home/ec2-user/gcp-credentials/

# Check .env has correct path
cat /home/ec2-user/.env | grep GCP_CREDENTIALS_PATH

# Check container has volume mount
docker inspect medscribe-ai-container | grep -A 5 Mounts
```

**Expected output:**
```json
"Mounts": [
    {
        "Source": "/home/ec2-user/gcp-credentials/gcp-credentials.json",
        "Destination": "/secrets/gcp-credentials.json",
        "Mode": "ro"
    }
]
```

## 📚 File Locations

### On GitLab Repository:
- `.gitlab-ci.yml` - CI/CD pipeline configuration
- `.env` - Environment variables (committed to repo)
- `.env.example` - Template for .env
- `deploy.sh` - Deployment script (uploaded to server)
- `Dockerfile` - Docker image build instructions

### On Dev Server (`<YOUR_SERVER_IP>`):
- `/home/ec2-user/.env` - Environment configuration
- `/home/ec2-user/deploy.sh` - Deployment script
- `/home/ec2-user/gcp-credentials/` - GCP credentials directory
- `/home/ec2-user/gcp-credentials/gcp-credentials.json` - GCP credentials file

## 🔒 Security Notes

### GitLab CI/CD Variables

These must be set in GitLab → Settings → CI/CD → Variables:

| Variable | Value | Protected | Masked |
|----------|-------|-----------|--------|
| `DOCKERHUB_USER` | <YOUR_DOCKERHUB_USER> | Yes | No |
| `DOCKERHUB_PASS` | your-password | Yes | Yes |
| `EC2_SSH_KEY` | contents of <YOUR_SSH_KEY>.pem | Yes | Yes |

### .env File Security

- ✅ **DO** commit `.env` to GitLab (it's private repo)
- ✅ **DO** use GitLab protected branches
- ❌ **DON'T** commit GCP credentials JSON
- ❌ **DON'T** expose .env in public

### GCP Credentials

- ✅ Stored on server only
- ✅ Never in Docker image
- ✅ Mounted as read-only volume
- ✅ Can be rotated without rebuild

## 🆘 Getting Help

### Check Status:

```bash
# On server
ssh -i "<YOUR_SSH_KEY>.pem" ec2-user@<YOUR_SERVER_IP>

# Container status
docker ps | grep medscribe-ai

# Application health
curl http://localhost:8086/actuator/health

# Recent logs
docker logs --tail 100 medscribe-ai-container
```

### Manual Deployment:

If CI/CD fails, you can deploy manually:

```bash
# SSH into server
ssh -i "<YOUR_SSH_KEY>.pem" ec2-user@<YOUR_SERVER_IP>

# Run deploy script manually
bash /home/ec2-user/deploy.sh
```

## 📞 Support

For issues:
1. Check GitLab CI/CD pipeline logs
2. Check application logs on server
3. Verify `.env` configuration
4. Contact DevOps team

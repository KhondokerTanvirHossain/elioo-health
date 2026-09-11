# Quick Server Setup - Run These Commands Once

**Target Server:** `ec2-user@<YOUR_SERVER_IP>`

## 📦 Step 1: Upload Deploy Script

```bash
scp -i "<YOUR_SSH_KEY>.pem" deploy.sh ec2-user@<YOUR_SERVER_IP>:/home/ec2-user/
```

## 📝 Step 2: Create .env File on Server

```bash
# SSH into server
ssh -i "<YOUR_SSH_KEY>.pem" ec2-user@<YOUR_SERVER_IP>

# Create .env file
cat > /home/ec2-user/.env << 'EOF'
PGHOST=<YOUR_SERVER_IP>
PGDATABASE=medscribe
PGUSER=<YOUR_DB_USER>
PGPASSWORD=<YOUR_DB_PASSWORD>
GCP_CREDENTIALS_PATH=/home/ec2-user/gcp-credentials/gcp-credentials.json
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8086
FEATURE_FLAGGING_SERVICE_URL=http://localhost:8087
SPRING_ADMIN_SERVER_URL=http://localhost:8080
MEDSCRIBEAI_SERVICE_URL=http://localhost:8086
ADMIN_SERVER_USERNAME=admin
ADMIN_SERVER_PASS=<YOUR_ADMIN_PASSWORD>
DOCKER_IMAGE=<YOUR_DOCKERHUB_USER>/medscribe-ai
IMAGE_TAG=latest
CONTAINER_NAME=medscribe-ai-container
EOF

# Make deploy script executable
chmod +x /home/ec2-user/deploy.sh

# Verify credentials exist
ls -la /home/ec2-user/gcp-credentials/gcp-credentials.json

# Exit SSH
exit
```

## ✅ Step 3: Test Deployment

```bash
# SSH back in
ssh -i "<YOUR_SSH_KEY>.pem" ec2-user@<YOUR_SERVER_IP>

# Run deploy script
bash /home/ec2-user/deploy.sh
```

## 🎯 That's It!

From now on, just push to `dev` branch and CI/CD handles everything:

```bash
git push origin dev
```

Watch deployment: GitLab → CI/CD → Pipelines

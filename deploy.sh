#!/bin/bash

###############################################################################
# MedScribe AI - Automated Deployment Script for CI/CD
#
# This script is executed by GitLab CI/CD on the dev server.
# It reads configuration from .env file and deploys the application.
#
# Location on server: /home/ec2-user/deploy.sh
#
# Usage (by CI/CD):
#   bash /home/ec2-user/deploy.sh
###############################################################################

set -e  # Exit on error

# ============================================================================
# Load Environment Variables from .env file
# ============================================================================

ENV_FILE="/home/ec2-user/.env"

if [ -f "$ENV_FILE" ]; then
    echo "[INFO] Loading environment variables from $ENV_FILE"
    export $(grep -v '^#' $ENV_FILE | xargs)
else
    echo "[ERROR] Environment file not found: $ENV_FILE"
    echo "[ERROR] Please create .env file with required variables"
    exit 1
fi

# ============================================================================
# Configuration (loaded from .env)
# ============================================================================

DOCKER_IMAGE="${DOCKER_IMAGE:-<YOUR_DOCKERHUB_USER>/medscribe-ai}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
CONTAINER_NAME="${CONTAINER_NAME:-medscribe-ai-container}"
GCP_CREDENTIALS_PATH="${GCP_CREDENTIALS_PATH:-/home/ec2-user/gcp-credentials/gcp-credentials.json}"

# ============================================================================
# Functions
# ============================================================================

log_info() {
    echo "[INFO] $1"
}

log_error() {
    echo "[ERROR] $1" >&2
}

log_success() {
    echo "[SUCCESS] $1"
}

# ============================================================================
# Deployment Steps
# ============================================================================

log_info "====================================================================="
log_info "Starting MedScribe AI Deployment"
log_info "====================================================================="
log_info "Docker Image: ${DOCKER_IMAGE}:${IMAGE_TAG}"
log_info "Container Name: ${CONTAINER_NAME}"
log_info "GCP Credentials: ${GCP_CREDENTIALS_PATH}"
log_info "====================================================================="

# Step 1: Check if GCP credentials exist
log_info "Step 1: Verifying GCP credentials..."
if [ ! -f "$GCP_CREDENTIALS_PATH" ]; then
    log_error "GCP credentials file not found at: $GCP_CREDENTIALS_PATH"
    log_error "Please ensure credentials are uploaded to the server"
    exit 1
fi
log_success "GCP credentials found"

# Step 2: Pull latest Docker image
log_info "Step 2: Pulling latest Docker image..."
docker pull ${DOCKER_IMAGE}:${IMAGE_TAG}
log_success "Docker image pulled successfully"

# Step 3: Stop and remove existing container
log_info "Step 3: Stopping existing container (if exists)..."
if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    docker stop ${CONTAINER_NAME} || true
    docker rm ${CONTAINER_NAME} || true
    log_success "Existing container stopped and removed"
else
    log_info "No existing container found"
fi

# Step 4: Remove unused Docker images to save space
log_info "Step 4: Cleaning up old Docker images..."
docker image prune -f
log_success "Docker cleanup completed"

# Step 5: Run new container
log_info "Step 5: Starting new container..."
docker run -d \
    --name ${CONTAINER_NAME} \
    --restart unless-stopped \
    -p ${SERVER_PORT}:${SERVER_PORT} \
    \
    -e SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE} \
    -e GOOGLE_APPLICATION_CREDENTIALS=/secrets/gcp-credentials.json \
    -e GCP_CREDENTIALS_PATH=/secrets/gcp-credentials.json \
    \
    -e PGHOST="${PGHOST}" \
    -e PGDATABASE="${PGDATABASE}" \
    -e PGUSER="${PGUSER}" \
    -e PGPASSWORD="${PGPASSWORD}" \
    \
    -e FEATURE_FLAGGING_SERVICE_URL="${FEATURE_FLAGGING_SERVICE_URL}" \
    -e SPRING_ADMIN_SERVER_URL="${SPRING_ADMIN_SERVER_URL}" \
    -e MEDSCRIBEAI_SERVICE_URL="${MEDSCRIBEAI_SERVICE_URL}" \
    -e ADMIN_SERVER_USERNAME="${ADMIN_SERVER_USERNAME}" \
    -e ADMIN_SERVER_PASS="${ADMIN_SERVER_PASS}" \
    \
    -v ${GCP_CREDENTIALS_PATH}:/secrets/gcp-credentials.json:ro \
    \
    ${DOCKER_IMAGE}:${IMAGE_TAG}

log_success "Container started successfully"

# Step 6: Wait for application startup
log_info "Step 6: Waiting for application to start (30 seconds)..."
sleep 30

# Step 7: Health check
log_info "Step 7: Checking application health..."
HEALTH_URL="http://localhost:${SERVER_PORT}/actuator/health"

if curl -f ${HEALTH_URL} &> /dev/null; then
    log_success "Application is healthy!"
    curl ${HEALTH_URL} 2>/dev/null | head -20
else
    log_error "Health check failed!"
    log_error "Showing last 100 lines of logs:"
    docker logs --tail 100 ${CONTAINER_NAME}
    exit 1
fi

# Step 8: Show summary
log_info "====================================================================="
log_info "Deployment Summary"
log_info "====================================================================="
log_info "Container Name: ${CONTAINER_NAME}"
log_info "Image: ${DOCKER_IMAGE}:${IMAGE_TAG}"
log_info "Port: ${SERVER_PORT}"
log_info "Health URL: ${HEALTH_URL}"
log_info "API Endpoint: http://localhost:${SERVER_PORT}/api/v1/medical-report/process-multi-image"
log_info ""
log_info "Useful Commands:"
log_info "  View logs:      docker logs -f ${CONTAINER_NAME}"
log_info "  Stop container: docker stop ${CONTAINER_NAME}"
log_info "  Start container: docker start ${CONTAINER_NAME}"
log_info "  Restart:        docker restart ${CONTAINER_NAME}"
log_info "====================================================================="

log_success "Deployment completed successfully!"

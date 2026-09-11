#!/bin/bash

###############################################################################
# MedScribe AI - Simple EC2 Docker Deployment Script
#
# This script deploys MedScribe AI to EC2 with embedded GCP credentials.
# GCP credentials are already bundled in the Docker image.
#
# Prerequisites:
# 1. Docker installed on EC2
# 2. Docker image built and available: medscribe-ai:v1
# 3. PostgreSQL database accessible from EC2
#
# Usage:
#   ./deploy-ec2-simple.sh
###############################################################################

set -e  # Exit on error

# ============================================================================
# Configuration Variables
# ============================================================================

# Docker image details
IMAGE_NAME="medscribe-ai"
IMAGE_TAG="v1"
CONTAINER_NAME="medscribe-ai-container"

# Application configuration
SPRING_PROFILE="prod"
SERVER_PORT="8086"

# Database configuration (replace with your actual values or use environment variables)
PGHOST="${PGHOST:-localhost}"
PGDATABASE="${PGDATABASE:-medscribe}"
PGUSER="${PGUSER:-developer}"
PGPASSWORD="${PGPASSWORD:-changeme}"

# Optional: Feature flagging service
FEATURE_FLAGGING_SERVICE_URL="${FEATURE_FLAGGING_SERVICE_URL:-http://localhost:8087}"

# Optional: Spring Boot Admin
SPRING_ADMIN_SERVER_URL="${SPRING_ADMIN_SERVER_URL:-http://localhost:8080}"
MEDSCRIBEAI_SERVICE_URL="${MEDSCRIBEAI_SERVICE_URL:-http://localhost:8086}"
ADMIN_SERVER_USERNAME="${ADMIN_SERVER_USERNAME:-admin}"
ADMIN_SERVER_PASS="${ADMIN_SERVER_PASS:-changeme}"

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

check_docker() {
    log_info "Checking Docker..."

    if ! command -v docker &> /dev/null; then
        log_error "Docker is not installed. Please install Docker first."
        exit 1
    fi

    log_success "Docker is available"
}

stop_existing_container() {
    if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
        log_info "Stopping existing container: $CONTAINER_NAME"
        docker stop $CONTAINER_NAME || true
        docker rm $CONTAINER_NAME || true
        log_success "Existing container stopped and removed"
    else
        log_info "No existing container found"
    fi
}

run_container() {
    log_info "Starting MedScribe AI container with embedded GCP credentials..."

    docker run -d \
        --name $CONTAINER_NAME \
        --restart unless-stopped \
        -p $SERVER_PORT:$SERVER_PORT \
        \
        -e SPRING_PROFILES_ACTIVE=$SPRING_PROFILE \
        \
        -e PGHOST="$PGHOST" \
        -e PGDATABASE="$PGDATABASE" \
        -e PGUSER="$PGUSER" \
        -e PGPASSWORD="$PGPASSWORD" \
        \
        -e FEATURE_FLAGGING_SERVICE_URL="$FEATURE_FLAGGING_SERVICE_URL" \
        \
        -e SPRING_ADMIN_SERVER_URL="$SPRING_ADMIN_SERVER_URL" \
        -e MEDSCRIBEAI_SERVICE_URL="$MEDSCRIBEAI_SERVICE_URL" \
        -e ADMIN_SERVER_USERNAME="$ADMIN_SERVER_USERNAME" \
        -e ADMIN_SERVER_PASS="$ADMIN_SERVER_PASS" \
        \
        $IMAGE_NAME:$IMAGE_TAG

    log_success "Container started successfully"
}

check_health() {
    log_info "Waiting for application to start (30 seconds)..."
    sleep 30

    log_info "Checking application health..."

    if curl -f http://localhost:$SERVER_PORT/actuator/health &> /dev/null; then
        log_success "Application is healthy!"
        curl http://localhost:$SERVER_PORT/actuator/health | jq . || cat
    else
        log_error "Application health check failed"
        log_error "Check logs with: docker logs $CONTAINER_NAME"
        exit 1
    fi
}

show_logs() {
    log_info "Showing last 50 lines of logs..."
    docker logs --tail 50 $CONTAINER_NAME
}

print_summary() {
    echo ""
    echo "========================================================================"
    echo "MedScribe AI Deployment Summary"
    echo "========================================================================"
    echo "Container Name: $CONTAINER_NAME"
    echo "Image: $IMAGE_NAME:$IMAGE_TAG"
    echo "Port: $SERVER_PORT"
    echo "Profile: $SPRING_PROFILE"
    echo "GCP Credentials: Embedded in Docker image (classpath:gcp/...)"
    echo ""
    echo "Useful Commands:"
    echo "  View logs:      docker logs -f $CONTAINER_NAME"
    echo "  Stop container: docker stop $CONTAINER_NAME"
    echo "  Start container: docker start $CONTAINER_NAME"
    echo "  Health check:   curl http://localhost:$SERVER_PORT/actuator/health"
    echo "  API endpoint:   http://localhost:$SERVER_PORT/api/v1/medical-report/process-multi-image"
    echo "========================================================================"
}

# ============================================================================
# Main Execution
# ============================================================================

main() {
    log_info "Starting MedScribe AI deployment with embedded credentials..."

    check_docker
    stop_existing_container
    run_container
    check_health
    show_logs
    print_summary

    log_success "Deployment completed successfully!"
}

# Run main function
main

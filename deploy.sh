#!/usr/bin/env bash
# MedScribe AI — server-side deploy. Invoked by GitHub Actions over SSH:
#   IMAGE_TAG=<git sha> bash /home/ec2-user/deploy.sh
# Requires: docker, a prior `docker login ghcr.io`, and $ENV_FILE (mode 600).
set -euo pipefail

IMAGE="${IMAGE:-ghcr.io/khondokertanvirhossain/elioo-health/medscribe-ai}"
IMAGE_TAG="${IMAGE_TAG:?IMAGE_TAG (git sha) is required}"
ENV_FILE="${ENV_FILE:-/home/ec2-user/medscribe.env}"
CONTAINER_NAME="${CONTAINER_NAME:-medscribe-ai}"
PORT=8086
HEALTH_URL="http://localhost:${PORT}/actuator/health"

log() { printf '[deploy] %s\n' "$*"; }

[ -f "$ENV_FILE" ] || { log "ERROR: $ENV_FILE not found. Copy medscribe.env.example there and fill it in."; exit 1; }

# GCP credentials: mount if the path in the env file exists on this host
GCP_HOST_PATH="$(grep -E '^GCP_CREDENTIALS_HOST_PATH=' "$ENV_FILE" | cut -d= -f2- || true)"
GCP_MOUNT=()
if [ -n "$GCP_HOST_PATH" ] && [ -f "$GCP_HOST_PATH" ]; then
  GCP_MOUNT=(-v "$GCP_HOST_PATH:/secrets/gcp-credentials.json:ro")
  log "GCP credentials will be mounted from $GCP_HOST_PATH"
else
  log "WARNING: no GCP credentials file; OCR/translation will be degraded"
fi

# Reverse proxy: join the edge network Caddy (n8n stack) lives on, so it can reach us as "medscribe-ai:8086"
EDGE_NETWORK="${EDGE_NETWORK:-n8n_edge}"
NETWORK_OPT=()
if docker network inspect "$EDGE_NETWORK" >/dev/null 2>&1; then
  NETWORK_OPT=(--network "$EDGE_NETWORK")
  log "Attaching to network $EDGE_NETWORK (Caddy reverse proxy)"
else
  log "WARNING: network $EDGE_NETWORK not found; container only reachable on host port ${PORT}"
fi

log "Pulling ${IMAGE}:${IMAGE_TAG}"
docker pull "${IMAGE}:${IMAGE_TAG}"

if docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER_NAME"; then
  log "Removing existing container $CONTAINER_NAME"
  docker rm -f "$CONTAINER_NAME" >/dev/null
fi

log "Starting $CONTAINER_NAME"
docker run -d \
  --name "$CONTAINER_NAME" \
  --restart unless-stopped \
  --memory=1200m \
  "${NETWORK_OPT[@]}" \
  -p "${PORT}:${PORT}" \
  --env-file "$ENV_FILE" \
  -e GCP_CREDENTIALS_PATH=/secrets/gcp-credentials.json \
  "${GCP_MOUNT[@]}" \
  "${IMAGE}:${IMAGE_TAG}" >/dev/null

log "Waiting for health at $HEALTH_URL"
for i in $(seq 1 45); do
  if curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then
    log "Healthy after $((i*2))s: $(curl -fsS "$HEALTH_URL")"
    docker image prune -f >/dev/null || true
    exit 0
  fi
  sleep 2
done

log "ERROR: container did not become healthy in 90s. Last 50 log lines:"
docker logs --tail 50 "$CONTAINER_NAME" || true
exit 1

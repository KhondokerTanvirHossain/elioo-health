#!/usr/bin/env bash
# MedScribe AI — server-side deploy. Invoked by GitHub Actions over SSH:
#   IMAGE_TAG=<git sha> bash /home/ec2-user/deploy.sh
# Requires: docker, a prior `docker login ghcr.io`, and $ENV_FILE (mode 600).
#
# DEPLOY_FUNCTIONS_ONLY=1 makes `source deploy.sh` define the functions and stop (scripts/test_deploy_prune.sh).
set -euo pipefail

log() { printf '[deploy] %s\n' "$*"; }

# prune_repo <image-repo> <keep-count>
#
# Removes local tags of exactly <image-repo>, keeping the newest <keep-count> by creation time. Never removes
# the image of a running container (matched by the reference it was started with AND by image id), and never
# touches another repository: the box is shared with n8n and bddrug. Removes by repo:tag, never by image id,
# because one id can carry several tags. A removal the daemon refuses (a stopped container still holds the
# image) is logged and skipped; pruning never fails the deploy.
#
# Why it runs BEFORE the pull: tagged images are never "dangling", so the old `docker image prune -f` after a
# healthy start removed nothing of ours. 40 medscribe-ai tags left 233 MB free on 2026-10-21, and a pull that
# runs out of disk fails mid-deploy, after the decision to deploy has been made.
prune_repo() {
  local repo="$1" keep="$2"
  case "$keep" in ''|*[!0-9]*) log "prune: keep count '$keep' is not a number; nothing removed"; return 0 ;; esac

  local running_refs running_cids running_ids=""
  running_refs="$(docker ps --format '{{.Image}}' 2>/dev/null || true)"
  running_cids="$(docker ps -q --no-trunc 2>/dev/null || true)"
  if [ -n "$running_cids" ]; then
    # shellcheck disable=SC2086 # one argument per container id
    running_ids="$(docker inspect --format '{{.Image}}' $running_cids 2>/dev/null || true)"
  fi

  local n=0 removed=0 r tag id
  # lines are "<repository> <tag> <id> <YYYY-MM-DD> <HH:MM:SS> <offset> <zone>", sorted newest first
  while read -r r tag id _; do
    n=$((n + 1))
    if [ "$n" -le "$keep" ]; then continue; fi
    if printf '%s\n' "$running_refs" | grep -qxF "$repo:$tag" || printf '%s\n' "$running_ids" | grep -qxF "$id"; then
      log "prune: keeping $tag (a running container uses it)"
      continue
    fi
    if docker rmi "$repo:$tag" >/dev/null 2>&1; then
      removed=$((removed + 1))
      log "prune: removed $tag"
    else
      log "prune: could not remove $tag (still in use?); left in place"
    fi
  done < <(docker images --no-trunc --format '{{.Repository}} {{.Tag}} {{.ID}} {{.CreatedAt}}' 2>/dev/null \
             | awk -v r="$repo" '$1 == r && $2 != "<none>"' | sort -r -k4,5)
  log "prune: $removed old tag(s) of $repo removed; newest $keep and any running image kept"
}

if [ "${DEPLOY_FUNCTIONS_ONLY:-}" = 1 ]; then return 0 2>/dev/null || exit 0; fi

IMAGE="${IMAGE:-ghcr.io/khondokertanvirhossain/elioo-health/medscribe-ai}"
IMAGE_TAG="${IMAGE_TAG:?IMAGE_TAG (git sha) is required}"
ENV_FILE="${ENV_FILE:-/home/ec2-user/medscribe.env}"
CONTAINER_NAME="${CONTAINER_NAME:-medscribe-ai}"
PORT=8086
HEALTH_URL="http://localhost:${PORT}/actuator/health"

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

prune_repo "$IMAGE" 3
log "Disk free on /: $(df -Ph / 2>/dev/null | awk 'NR == 2 {print $4}')"

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
  ${NETWORK_OPT[@]+"${NETWORK_OPT[@]}"} \
  -p "${PORT}:${PORT}" \
  --env-file "$ENV_FILE" \
  -e GCP_CREDENTIALS_PATH=/secrets/gcp-credentials.json \
  ${GCP_MOUNT[@]+"${GCP_MOUNT[@]}"} \
  "${IMAGE}:${IMAGE_TAG}" >/dev/null

log "Waiting for health at $HEALTH_URL"
for i in $(seq 1 45); do
  if curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then
    log "Healthy after $((i*2))s: $(curl -fsS "$HEALTH_URL")"
    exit 0
  fi
  sleep 2
done

log "ERROR: container did not become healthy in 90s. Last 50 log lines:"
docker logs --tail 50 "$CONTAINER_NAME" || true
exit 1

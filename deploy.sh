#!/usr/bin/env bash
# MedScribe AI — server-side deploy. Invoked by GitHub Actions over SSH:
#   WEB_IMAGE_REQUIRED=1 IMAGE_TAG=<git sha> bash /home/ec2-user/deploy.sh
# Manually (a rollback): IMAGE_TAG=<git sha> bash ~/deploy.sh
# Requires: docker, a prior `docker login ghcr.io`, and $ENV_FILE (mode 600).
#
# Two containers, one sha (DR-40 A2): Java (medscribe-ai) is swapped first and must be healthy; only then is
# medioo-web (Next.js) swapped, by deploy_web. A sha with no medioo-web image (every sha before W1b) deploys Java
# only and leaves whatever medioo-web is running alone; WEB_IMAGE_REQUIRED=1 (set by CI, which has just pushed that
# image) turns a missing web image into a failure instead. Tested by scripts/test_deploy_sh.sh.
#
# DEPLOY_FUNCTIONS_ONLY=1 makes `source deploy.sh` define the functions and stop (scripts/test_deploy_prune.sh,
# scripts/test_deploy_sh.sh). It is honoured only when the file is SOURCED: executing deploy.sh with it set fails,
# so a stray export can never turn a deploy into a silent no-op that exits 0.
#
# MIN_FREE_KB (default 1572864, i.e. 1.5 GB, about 3x the Java image): after pruning both repositories, the deploy
# refuses to pull below this much free space on / and exits non-zero while the old containers keep serving. The one
# floor covers both pulls: the web image is a fraction of the Java one. docs/RUNBOOK.md -> Disk.
set -euo pipefail

log() { printf '[deploy] %s\n' "$*"; }

# prune_repo <image-repo> <keep-count> [<protected-tag>]
#
# Removes local tags of exactly <image-repo>, keeping the newest <keep-count> by creation time (date AND time of
# day; the tags are git shas, so their alphabetical order means nothing). Never removes <protected-tag>, the tag
# about to be deployed: on a rollback to an older sha, the known-good image already on disk must not be removed
# and then pulled again. Never removes the image of a running container: a container protects an image if it
# was started by that reference OR runs that image id. The id rule covers a container started by id; the
# reference rule is what still protects it when `docker inspect` gives no usable id. Never touches another
# repository: the box is shared with n8n and bddrug. Removes by repo:tag, never by image id,
# because one id can carry several tags. A removal the daemon refuses (a stopped container still holds the
# image) is logged and skipped; pruning never fails the deploy.
#
# Why it runs BEFORE the pull: tagged images are never "dangling", so the old `docker image prune -f` after a
# healthy start removed nothing of ours. 40 medscribe-ai tags left 233 MB free on 2026-10-21, and a pull that
# runs out of disk fails mid-deploy, after the decision to deploy has been made.
prune_repo() {
  local repo="$1" keep="$2" protect="${3:-}"
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
    if [ -n "$protect" ] && [ "$tag" = "$protect" ]; then
      log "prune: keeping $tag (the tag being deployed)"
      continue
    fi
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
  log "prune: $removed old tag(s) of $repo removed; newest $keep, the tag being deployed and any running image kept"
}

# require_free_kb <path> <min-kb>: fails unless the filesystem holding <path> has at least <min-kb> KB free.
# The box is shared: a pull into a full disk would take bddrug's Postgres, n8n and Caddy's certificate storage
# down with it, while our old container survives. A figure that cannot be read counts as too little.
require_free_kb() {
  local path="$1" min="$2" free
  free="$(df -Pk "$path" 2>/dev/null | awk 'NR == 2 {print $4}')" || true
  case "$free" in
    ''|*[!0-9]*) log "ERROR: could not read the free space on $path; refusing to pull"; return 1 ;;
  esac
  if [ "$free" -lt "$min" ]; then
    log "ERROR: only ${free} KB free on $path (floor ${min} KB); refusing to pull. The running container is untouched."
    return 1
  fi
  log "Disk free on $path: ${free} KB (floor ${min} KB)"
}

WEB_IMAGE="${WEB_IMAGE:-ghcr.io/khondokertanvirhossain/elioo-health/medioo-web}"
WEB_CONTAINER_NAME="${WEB_CONTAINER_NAME:-medioo-web}"

# deploy_web <tag>: swaps the medioo-web container to <tag>. Called only once Java is healthy.
#
# - Prunes the web repository to 3 tags (never <tag>, never the running image).
# - If `docker pull` fails (every sha before W1b has no web image, so a rollback to one lands here): logs, leaves
#   the running medioo-web as it is, and returns 0; unless WEB_IMAGE_REQUIRED=1, when it returns 1. CI sets it: the
#   same job has just pushed this image, so a failed pull there is an auth or registry fault, not a pre-W1b sha.
# - Runs it on n8n_edge with no published port (nothing reaches it except through Caddy, and no route does until
#   W1c), no --env-file and no mounts (Node never holds Java's secrets: it gets the two variables it needs), capped
#   at 320 MB, with rotated logs (the box is shared and low on disk; Next writes a stack trace per server error).
# - Waits up to 60 s for /app/healthz, fetched inside the container, to answer for THIS sha; on timeout prints the
#   last 50 log lines and returns 1. The failed container is left in place for inspection; Java is not touched.
#
# Every step checks its own status: the caller runs it as `deploy_web ... || exit 1`, where `set -e` is off.
deploy_web() {
  local tag="$1" err
  # the main body prunes both repositories before the free-space floor and records it; once per run is enough
  [ "${WEB_PRUNED_FOR:-}" = "$tag" ] || prune_repo "$WEB_IMAGE" 3 "$tag"

  log "Pulling ${WEB_IMAGE}:${tag}"
  if ! err="$(docker pull "${WEB_IMAGE}:${tag}" 2>&1 >/dev/null)"; then
    err="${err%%$'\n'*}"
    if [ "${WEB_IMAGE_REQUIRED:-}" = 1 ]; then
      log "ERROR: web image ${tag} could not be pulled and WEB_IMAGE_REQUIRED=1 (${err}). Java is deployed and healthy; $WEB_CONTAINER_NAME is unchanged."
      return 1
    fi
    log "web image ${tag} not found; Java-only deploy (${err}). $WEB_CONTAINER_NAME, if running, is unchanged."
    return 0
  fi

  local edge="${EDGE_NETWORK:-n8n_edge}" net=()
  if docker network inspect "$edge" >/dev/null 2>&1; then
    net=(--network "$edge")
  else
    log "WARNING: network $edge not found; $WEB_CONTAINER_NAME can neither reach Java nor be reached by Caddy"
  fi

  if docker ps -a --format '{{.Names}}' | grep -qx "$WEB_CONTAINER_NAME"; then
    log "Removing existing container $WEB_CONTAINER_NAME"
    docker rm -f "$WEB_CONTAINER_NAME" >/dev/null || { log "ERROR: could not remove $WEB_CONTAINER_NAME"; return 1; }
  fi

  log "Starting $WEB_CONTAINER_NAME"
  if ! docker run -d \
      --name "$WEB_CONTAINER_NAME" \
      --restart unless-stopped \
      --memory=320m \
      --log-opt max-size=10m --log-opt max-file=3 \
      ${net[@]+"${net[@]}"} \
      -e JAVA_INTERNAL_URL=http://medscribe-ai:8086 \
      -e "GIT_SHA=${tag}" \
      "${WEB_IMAGE}:${tag}" >/dev/null; then
    log "ERROR: could not start $WEB_CONTAINER_NAME"
    return 1
  fi

  # The image HEALTHCHECK's fetch, stricter: the answer must be medioo-web AND carry the sha just started (the
  # container's own GIT_SHA), so nothing but the new process can pass it.
  local probe="fetch('http://127.0.0.1:3000/app/healthz').then(r=>r.ok?r.json():Promise.reject(r.status)).then(j=>process.exit(j.service==='medioo-web'&&j.sha===process.env.GIT_SHA?0:1)).catch(()=>process.exit(1))"
  local i
  log "Waiting for $WEB_CONTAINER_NAME /app/healthz"
  for i in $(seq 1 30); do
    if docker exec "$WEB_CONTAINER_NAME" node -e "$probe" >/dev/null 2>&1; then
      log "$WEB_CONTAINER_NAME healthy after $((i*2))s (sha ${tag})"
      return 0
    fi
    sleep 2
  done

  log "ERROR: $WEB_CONTAINER_NAME did not become healthy in 60s. Java is deployed and untouched. Last 50 log lines:"
  docker logs --tail 50 "$WEB_CONTAINER_NAME" 2>&1 || true
  return 1
}

if [ "${DEPLOY_FUNCTIONS_ONLY:-}" = 1 ]; then
  if [ "${BASH_SOURCE[0]}" != "$0" ]; then return 0; fi
  log "ERROR: DEPLOY_FUNCTIONS_ONLY=1 is set but deploy.sh was executed, not sourced; refusing to deploy"
  exit 1
fi

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

MIN_FREE_KB="${MIN_FREE_KB:-1572864}"
case "$MIN_FREE_KB" in ''|*[!0-9]*) log "ERROR: MIN_FREE_KB '$MIN_FREE_KB' is not a number"; exit 1 ;; esac

# both repositories, before the floor is read: what either frees counts for both pulls
prune_repo "$IMAGE" 3 "$IMAGE_TAG"
prune_repo "$WEB_IMAGE" 3 "$IMAGE_TAG"
WEB_PRUNED_FOR="$IMAGE_TAG"
require_free_kb / "$MIN_FREE_KB" || exit 1

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
JAVA_HEALTHY=0
for i in $(seq 1 45); do
  if curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then
    # only the top-level status: the full body (disk, paths, database details) would land in the public CI log
    log "Healthy after $((i*2))s: $(curl -fsS "$HEALTH_URL" 2>/dev/null | grep -o '"status":"[A-Z_]*"' | head -1 || true)"
    JAVA_HEALTHY=1
    break
  fi
  sleep 2
done

if [ "$JAVA_HEALTHY" != 1 ]; then
  log "ERROR: container did not become healthy in 90s; $WEB_CONTAINER_NAME not touched. Last 50 log lines:"
  docker logs --tail 50 "$CONTAINER_NAME" || true
  exit 1
fi

# Java first, then Node (DR-40 A2): a web failure turns the deploy red but never costs the Java deploy
deploy_web "$IMAGE_TAG" || exit 1
exit 0

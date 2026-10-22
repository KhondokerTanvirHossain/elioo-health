#!/usr/bin/env bash
# MedScribe AI — server-side deploy. Invoked by GitHub Actions over SSH:
#   WEB_IMAGE_REQUIRED=1 IMAGE_TAG=<git sha> bash /home/ec2-user/deploy.sh
# Manually (a rollback): IMAGE_TAG=<git sha> bash ~/deploy.sh
# Requires: docker, a prior `docker login ghcr.io`, and $ENV_FILE (mode 600).
#
# Two containers, one sha (DR-40 A2): Java (medscribe-ai) is swapped first and must be healthy; only then is
# medioo-web (Next.js) swapped, by deploy_web. A sha GHCR has no medioo-web image for (every sha before W1b) deploys
# Java only and leaves whatever medioo-web is running alone; WEB_IMAGE_REQUIRED=1 (set by CI, which has just pushed
# that image) turns a missing web image into a failure instead. Any other web pull error fails the deploy. A web
# swap that fails puts the previous medioo-web back. Tested by scripts/test_deploy_sh.sh (fakes) and
# scripts/test_deploy_web_docker.sh (the real image and daemon). Needs coreutils `timeout` (the box has it).
#
# Java is published on the box's 127.0.0.1:8086 only (plan W1c): from outside, everything goes through Caddy, which
# reaches it as medscribe-ai:8086 on n8n_edge. Routes floor (W1c): once a path is routed to medioo-web, an IMAGE_TAG
# that does not descend from the sha recorded in ROUTES_FLOOR_FILE is refused before anything changes;
# ROUTES_FLOOR_FORCE=1 overrides (routes_floor_check below; docs/RUNBOOK.md -> Rollback).
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
WEB_HEALTH_DEADLINE_S="${WEB_HEALTH_DEADLINE_S:-60}"   # the whole health wait, wall clock
WEB_PROBE_TIMEOUT_S="${WEB_PROBE_TIMEOUT_S:-5}"        # one try: docker exec, a node start and the fetch (2 s of it)

# The health probe, run inside the container as `node -e "$WEB_PROBE" <tag>`. It passes only when /app/healthz
# answers 2xx JSON with service "medioo-web", sha == <tag> (the GIT_SHA the container was started with) AND
# build == <tag> (the BUILD_SHA baked into the image when CI built it). <tag> is the literal deploy.sh holds, passed
# as an argument, never read from the container's own environment: a probe that compares the container's answer
# with the container's env agrees with itself whatever is running. The build field is what makes it a check of the
# IMAGE: an image tagged <tag> but built from another sha fails. The fetch has its own 2 s deadline and exitCode
# starts at 1, so a hung server, or a path that reaches neither exit, fails rather than passing or stalling.
WEB_PROBE="process.exitCode=1;const t=process.argv[1];fetch('http://127.0.0.1:3000/app/healthz',{signal:AbortSignal.timeout(2000)}).then(r=>r.ok?r.json():Promise.reject(r.status)).then(j=>process.exit(t&&j&&j.service==='medioo-web'&&j.sha===t&&j.build===t?0:1)).catch(()=>process.exit(1))"

# The catalog-only pages (plans W2c, W4): pages that exist only in a build made with MEDIOO_CATALOG=1, the component
# catalog and the shell's board page. The one list: the catalog probe below fetches every page in it, and
# medioo-web's routes.built.test.ts and catalog-off.spec.ts read it from this line, so a page added here is probed,
# left out of NEXT_PAGES and checked as a 404 in the production build, and a page left out of it fails the first.
WEB_CATALOG_PAGES="/app/catalog-test /app/shell-test"

# The catalog probe, run inside the container as `node -e "$WEB_CATALOG_PROBE" <page>...` (WEB_CATALOG_PAGES) once
# WEB_PROBE has passed. The image that ships must answer every catalog-only page 404 with no catalog state in the body.
# It is measured on the container that ships because that is the only artefact the claim is about. The pages are
# fetched together, each with its own 2 s deadline, so the probe stays inside one try's bound. It prints what it saw
# on stdout and has three outcomes:
#   exit 0, "status=404 states=no"                every page is a 404 without catalog states: pass
#   exit 1, "<page> status=<n> states=<yes|no>"   a definitive answer that is not that (a 200, a 404 that renders
#                                                 states), on that page: a property of the image, so it fails at once,
#                                                 never retried; it wins over a page that gave no answer
#   exit 3, "<page> no-answer <error name>"       a page gave no answer within 2 s (TimeoutError) or nothing listens
#                                                 (TypeError): the first React render in a new container can be slow,
#                                                 so it is retried
# exitCode starts at 3, so a path that reaches no exit counts as no answer: retried, then failed at the deadline. With
# no page at all it prints "no-pages" and exits 1: a probe of nothing would pass whatever the image serves.
WEB_CATALOG_PROBE="process.exitCode=3;const p=process.argv.slice(1);if(!p.length){console.log('no-pages');process.exit(1)}Promise.allSettled(p.map(u=>fetch('http://127.0.0.1:3000'+u,{signal:AbortSignal.timeout(2000)}).then(r=>r.text().then(b=>({st:r.status,s:b.includes('data-catalog-state')}))))).then(rs=>{rs.forEach((x,i)=>{if(x.status==='fulfilled'&&(x.value.st!==404||x.value.s)){console.log(p[i]+' status='+x.value.st+' states='+(x.value.s?'yes':'no'));process.exit(1)}});rs.forEach((x,i)=>{if(x.status==='rejected'){const e=x.reason;console.log(p[i]+' no-answer '+((e&&e.name)||'error'));process.exit(3)}});console.log('status=404 states=no');process.exit(0)})"

# web_start <tag> <network>: the one place medioo-web's `docker run` arguments are written (new and put-back alike).
# On <network> with no published port (nothing reaches it except through Caddy), no --env-file and no mounts (Node
# never holds Java's secrets: it gets the two variables it needs), capped at 320 MB, with rotated logs (the box is
# shared and low on disk; Next writes a stack trace per server error).
web_start() {
  docker run -d \
    --name "$WEB_CONTAINER_NAME" \
    --restart unless-stopped \
    --memory=320m \
    --log-opt max-size=10m --log-opt max-file=3 \
    --network "$2" \
    -e JAVA_INTERNAL_URL=http://medscribe-ai:8086 \
    -e "GIT_SHA=$1" \
    "${WEB_IMAGE}:$1" >/dev/null
}

# web_wait_healthy <tag>: 0 once WEB_PROBE passes for <tag> and then WEB_CATALOG_PROBE passes over every page of
# WEB_CATALOG_PAGES. Each try is bounded by WEB_PROBE_TIMEOUT_S (a hung exec or node cannot stall it), the whole wait by
# WEB_HEALTH_DEADLINE_S of wall clock and by 30 tries, both probes sharing that budget. Once healthz has passed it is
# not probed again. The catalog probe's definitive answer (exit 1 with "<page> status=…") is not retried: a served
# catalog page is a property of the image, not a slow start. Anything else from it (no answer, a timed-out or refused
# exec) is retried, and logged as what it was, naming the page that gave no answer.
web_wait_healthy() {
  local tag="$1" i=0 start=$SECONDS healthy=0 out rc seen="" page="" said="" p
  local -a pages
  read -ra pages <<< "$WEB_CATALOG_PAGES"
  for p in "${pages[@]}"; do said="${said:+$said, }$p is a 404"; done
  while [ "$i" -lt 30 ] && [ $((SECONDS - start)) -lt "$WEB_HEALTH_DEADLINE_S" ]; do
    i=$((i + 1))
    if [ "$healthy" = 0 ]; then
      if ! timeout "$WEB_PROBE_TIMEOUT_S" docker exec "$WEB_CONTAINER_NAME" node -e "$WEB_PROBE" "$tag" >/dev/null 2>&1; then
        sleep 2
        continue
      fi
      healthy=1
    fi
    out="$(timeout "$WEB_PROBE_TIMEOUT_S" docker exec "$WEB_CONTAINER_NAME" node -e "$WEB_CATALOG_PROBE" "${pages[@]}" 2>/dev/null)"
    rc=$?
    out="${out%%$'\n'*}"
    case "$rc:$out" in
      0:*)
        log "$WEB_CONTAINER_NAME healthy after $((SECONDS - start))s, try $i (sha ${tag}); $said"
        return 0 ;;
      "1:/"*" status="*)
        log "ERROR: $WEB_CONTAINER_NAME (sha ${tag}) answers ${out%% *} with something other than a 404 without catalog states (${out#* }): an image built with MEDIOO_CATALOG=1 never ships (catalog probe)"
        return 1 ;;
      1:no-pages)
        log "ERROR: WEB_CATALOG_PAGES names no page, so the catalog probe would pass whatever $WEB_CONTAINER_NAME (sha ${tag}) serves (catalog probe)"
        return 1 ;;
    esac
    case "$rc:$out" in
      124:*) page="$WEB_CATALOG_PAGES"; seen="no-answer: exec timed out after ${WEB_PROBE_TIMEOUT_S}s" ;;
      *:/*) page="${out%% *}"; seen="${out#* }" ;;
      *) page="$WEB_CATALOG_PAGES"; seen="${out:-no output, exit $rc}" ;;
    esac
    log "$WEB_CONTAINER_NAME catalog probe got no answer from $page (${seen}), try $i; retrying"
    sleep 2
  done
  if [ "$healthy" = 1 ]; then
    log "ERROR: $WEB_CONTAINER_NAME (sha ${tag}) passed /app/healthz but ${page:-$WEB_CATALOG_PAGES} gave no answer in ${WEB_HEALTH_DEADLINE_S}s (last: ${seen}) (catalog probe)"
  fi
  return 1
}

# web_restore <previous image ref> <failed tag> <network>: puts the previous medioo-web back after a failed swap. The
# container is stateless, so unlike Java it can be restored without a person: from W1c Caddy routes paths to it, and
# one bad web build must not leave them down until someone acts. With no previous one of ours (the first deploy),
# the failed container is left in place to inspect.
web_restore() {
  local prev="$1" failed="$2" edge="$3" ptag=""
  case "$prev" in "${WEB_IMAGE}:"*) ptag="${prev##*:}" ;; esac
  if [ -z "$ptag" ] || [ "$ptag" = "$failed" ]; then
    log "No previous $WEB_CONTAINER_NAME to put back${prev:+ (was $prev)}; the failed one is left in place: docker logs $WEB_CONTAINER_NAME"
    return 0
  fi
  log "Putting the previous $WEB_CONTAINER_NAME ($ptag) back"
  if docker ps -a --format '{{.Names}}' | grep -qx "$WEB_CONTAINER_NAME"; then
    docker rm -f "$WEB_CONTAINER_NAME" >/dev/null || { log "ERROR: could not remove the failed $WEB_CONTAINER_NAME; nothing put back"; return 0; }
  fi
  if ! web_start "$ptag" "$edge"; then
    log "ERROR: could not start the previous $WEB_CONTAINER_NAME ($ptag); none is running"
    return 0
  fi
  if web_wait_healthy "$ptag"; then
    log "The previous $WEB_CONTAINER_NAME ($ptag) is back and healthy"
  else
    log "ERROR: the previous $WEB_CONTAINER_NAME ($ptag) is back but did not pass its health and catalog probes either (see the lines above)"
  fi
}

# deploy_web <tag>: swaps the medioo-web container to <tag>. Called only once Java is healthy.
#
# - Prunes the web repository to 3 tags (never <tag>, never the running image).
# - Pulls <tag>. If the pull fails and <tag> is already on disk, uses that copy (a logged-out manual rollback to a
#   tag the prune kept). Otherwise only GHCR's missing-tag answer ("manifest unknown" / "not found": every sha
#   before W1b) skips the web step: it logs that, leaves the running medioo-web as it is and returns 0; unless
#   WEB_IMAGE_REQUIRED=1 (CI, which has just pushed this image), when it returns 1. Any other pull error (denied,
#   unauthorized, rate limit, network, no space) is a fault and returns 1 whatever WEB_IMAGE_REQUIRED says: a green
#   run that left the web at another sha would read as a successful rollback.
# - No edge network: returns 1 before the running medioo-web is touched. Anywhere else the new one would pass its
#   in-container probe while neither Caddy nor it could reach the other side.
# - Replaces the container (web_start) and waits for this tag's /app/healthz (web_wait_healthy). If the run fails or
#   the wait times out: prints the last 50 log lines, puts the previous medioo-web back (web_restore), returns 1.
#   Java is never touched.
#
# Every step checks its own status: the caller runs it as `deploy_web ... || exit 1`, where `set -e` is off.
deploy_web() {
  local tag="$1" err line edge="${EDGE_NETWORK:-n8n_edge}" prev
  # the main body prunes both repositories before the free-space floor and records it; once per run is enough
  [ "${WEB_PRUNED_FOR:-}" = "$tag" ] || prune_repo "$WEB_IMAGE" 3 "$tag"

  log "Pulling ${WEB_IMAGE}:${tag}"
  if ! err="$(docker pull "${WEB_IMAGE}:${tag}" 2>&1 >/dev/null)"; then
    line="${err%%$'\n'*}"
    if docker image inspect "${WEB_IMAGE}:${tag}" >/dev/null 2>&1; then
      log "web pull failed (${line}); using the local ${tag}"
    elif printf '%s\n' "$err" | grep -qiE 'manifest unknown|not found'; then
      if [ "${WEB_IMAGE_REQUIRED:-}" = 1 ]; then
        log "ERROR: web image ${tag} not found and WEB_IMAGE_REQUIRED=1 (${line}). Java is deployed and healthy; $WEB_CONTAINER_NAME is unchanged."
        return 1
      fi
      log "web image ${tag} not found; Java-only deploy (${line}). $WEB_CONTAINER_NAME, if running, is unchanged."
      return 0
    else
      log "ERROR: could not pull web image ${tag}: ${line}. Not a missing tag, so not skipped. Java is deployed and healthy; $WEB_CONTAINER_NAME is unchanged."
      return 1
    fi
  fi

  if ! docker network inspect "$edge" >/dev/null 2>&1; then
    log "ERROR: network $edge not found; $WEB_CONTAINER_NAME would be unreachable (Caddy could not reach it, nor it Java). Java is deployed and healthy; $WEB_CONTAINER_NAME is unchanged."
    return 1
  fi

  prev="$(docker inspect -f '{{.Config.Image}}' "$WEB_CONTAINER_NAME" 2>/dev/null || true)"
  if docker ps -a --format '{{.Names}}' | grep -qx "$WEB_CONTAINER_NAME"; then
    log "Removing existing container $WEB_CONTAINER_NAME${prev:+ ($prev)}"
    docker rm -f "$WEB_CONTAINER_NAME" >/dev/null || { log "ERROR: could not remove $WEB_CONTAINER_NAME"; return 1; }
  fi

  log "Starting $WEB_CONTAINER_NAME"
  if ! web_start "$tag" "$edge"; then
    log "ERROR: could not start $WEB_CONTAINER_NAME (sha ${tag}). Java is deployed and untouched."
    web_restore "$prev" "$tag" "$edge"
    return 1
  fi

  log "Waiting for $WEB_CONTAINER_NAME /app/healthz"
  if web_wait_healthy "$tag"; then return 0; fi

  log "ERROR: $WEB_CONTAINER_NAME did not become healthy in ${WEB_HEALTH_DEADLINE_S}s (sha ${tag}). Java is deployed and untouched. Last 50 log lines:"
  docker logs --tail 50 "$WEB_CONTAINER_NAME" 2>&1 || true
  web_restore "$prev" "$tag" "$edge"
  return 1
}

# routes_floor_check <tag>: 0 when <tag> may be deployed under the routes floor (DR-40 A2, plan W1c).
#
# Caddy routing is box state, not tied to IMAGE_TAG: once a path is routed to medioo-web, a sha from before that
# flip would deploy code that does not serve it (or a Java that lacks the /app/api a flipped page calls). Each flip
# records its merge sha in ROUTES_FLOOR_FILE (default /home/ec2-user/medioo-routes-floor; first token of the first
# non-comment line, the rest is free text such as the route and the date; RUNBOOK -> Routing a path to medioo-web).
# A <tag> that is not that sha or a descendant of it is refused: revert the routes first (RUNBOOK -> Rollback).
#
# The box has no clone, so ancestry comes from GitHub's compare API (the repository is public; one unauthenticated
# call per deploy): <floor>...<tag> answering "ahead" or "identical" means <tag> descends from the floor. "behind" or
# "diverged" is refused, and so is anything that cannot be checked (the API unreachable, a floor file that does not
# hold a sha, no jq): a check that cannot run must not read as a pass. ROUTES_FLOOR_FORCE=1 overrides every refusal,
# loudly. Its own name, not FORCE: scripts/draft-labels.sh reads FORCE=1, and a generic name left exported in an
# operator's shell on this shared box would silently turn the floor into a warning.
# The call asks for page 2 of one commit per page: GitHub sends the changed-files list (with patches) only on page 1,
# and the top-level status on every page, so the answer stays small however long the range grows (measured
# 2026-10-21: 297 KB -> 41 KB for a 4-commit range). --max-time 30, and one retry on a failed call: GitHub's blips
# must not stop every deploy, while two failures in a row still refuse.
# No floor file means no route has flipped yet: nothing to enforce. Runs before anything on the box is changed.
ROUTES_FLOOR_FILE="${ROUTES_FLOOR_FILE:-/home/ec2-user/medioo-routes-floor}"
ROUTES_COMPARE_API="${ROUTES_COMPARE_API:-https://api.github.com/repos/KhondokerTanvirHossain/elioo-health/compare}"
routes_floor_check() {
  local tag="$1" floor="" why="" answer status=""
  if [ ! -e "$ROUTES_FLOOR_FILE" ]; then
    log "Routes floor: no routes floor recorded ($ROUTES_FLOOR_FILE); no route has flipped to medioo-web, nothing to enforce"
    return 0
  fi
  floor="$(grep -vE '^[[:space:]]*(#|$)' "$ROUTES_FLOOR_FILE" 2>/dev/null | head -1 | awk '{print $1}')" || true
  if ! printf '%s' "$floor" | grep -qE '^[0-9a-f]{7,40}$'; then
    why="the routes floor file $ROUTES_FLOOR_FILE does not start with a git sha"
  elif ! printf '%s' "$tag" | grep -qE '^[0-9a-f]{7,40}$'; then
    why="IMAGE_TAG $tag is not a git sha, so it cannot be checked against the routes floor $floor"
  elif [ "$tag" = "$floor" ]; then
    log "Routes floor: $tag is the routes floor itself"
    return 0
  elif ! command -v jq >/dev/null 2>&1; then
    why="could not check $tag against the routes floor $floor (jq is not installed)"
  elif ! answer="$(curl -fsS --max-time 30 -H 'Accept: application/vnd.github+json' "$ROUTES_COMPARE_API/$floor...$tag?per_page=1&page=2" 2>&1)" \
       && { log "Routes floor: the GitHub compare call failed (${answer%%$'\n'*}); retrying once"; sleep 5;
            ! answer="$(curl -fsS --max-time 30 -H 'Accept: application/vnd.github+json' "$ROUTES_COMPARE_API/$floor...$tag?per_page=1&page=2" 2>&1)"; }; then
    why="could not check $tag against the routes floor $floor (GitHub compare API, twice: ${answer%%$'\n'*})"
  else
    # the top-level field, parsed: the answer nests a "status" in every file entry
    status="$(printf '%s' "$answer" | jq -r 'if type == "object" then (.status // "") else "" end' 2>/dev/null)" || status=""
    case "$status" in
      ahead|identical)
        log "Routes floor: $tag descends from the routes floor $floor (GitHub: $status)"
        return 0 ;;
      behind|diverged)
        why="IMAGE_TAG $tag is not a descendant of the routes floor $floor (GitHub: $status). Revert the routes flipped since then and record the older floor first (docs/RUNBOOK.md -> Rollback)" ;;
      *)
        why="could not check $tag against the routes floor $floor (GitHub compare API answered no status)" ;;
    esac
  fi
  if [ "${ROUTES_FLOOR_FORCE:-}" = 1 ]; then
    log "WARNING: ROUTES_FLOOR_FORCE=1: deploying although $why"
    return 0
  fi
  log "ERROR: $why. Nothing on the box was changed. ROUTES_FLOOR_FORCE=1 overrides."
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
# 127.0.0.1, not localhost: Java is published on IPv4 loopback only (below), and localhost can resolve to ::1 first
HEALTH_URL="http://127.0.0.1:${PORT}/actuator/health"

[ -f "$ENV_FILE" ] || { log "ERROR: $ENV_FILE not found. Copy medscribe.env.example there and fill it in."; exit 1; }

# before the prune, the pulls and the swap: a refused tag leaves the box exactly as it was
routes_floor_check "$IMAGE_TAG" || exit 1

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
  log "WARNING: network $EDGE_NETWORK not found; Caddy cannot reach the container, only the box itself on 127.0.0.1:${PORT}"
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

# Java off the public port (DR-40 A1, plan W1c): published on the box's loopback only, for deploy.sh's health wait and
# the RUNBOOK's admin calls. Everything from outside goes through Caddy, which reaches medscribe-ai:8086 on n8n_edge.
log "Starting $CONTAINER_NAME"
docker run -d \
  --name "$CONTAINER_NAME" \
  --restart unless-stopped \
  --memory=1200m \
  ${NETWORK_OPT[@]+"${NETWORK_OPT[@]}"} \
  -p "127.0.0.1:${PORT}:${PORT}" \
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

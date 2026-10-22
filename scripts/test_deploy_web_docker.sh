#!/usr/bin/env bash
#
# deploy.sh's deploy_web against a REAL Docker daemon and the REAL medioo-web image, built from medioo-web/Dockerfile
# here. scripts/test_deploy_sh.sh covers the decisions against fakes; this covers what fakes cannot: that the image
# builds, that the probe passes against the real Next server, that `docker inspect` of the running container shows the
# settings deploy.sh claims, that an image built from another sha is refused and the previous container put back, and
# that the shipped image has no component catalog (deploy.sh's WEB_CATALOG_PROBE passes in it, also when it is started
# with MEDIOO_CATALOG=1 at run time, and fails against a stand-in that serves one: plan W2c).
# Runs in CI's build job, so a Dockerfile or probe break fails a PR instead of the first deploy after merge.
#
#   bash scripts/test_deploy_web_docker.sh
#
# Only `docker pull` is shimmed (the image is local; it answers like GHCR: present = pulled, absent = manifest unknown).
# Everything else is the real daemon. Names are private to this test (repository, container, network), so it never
# touches medioo-web, n8n_edge or another repository on the machine it runs on, and cleans up after itself.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPLOY="${DEPLOY_SH:-$ROOT/deploy.sh}"
REAL_DOCKER="$(command -v docker)" || { echo "FAIL: no docker"; exit 1; }
WORK=$(mktemp -d)
SUFFIX="$$-$RANDOM"
REPO="medioo-web-deploytest"
NAME="medioo-web-deploytest-$SUFFIX"
NET="medioo-deploytest-edge-$SUFFIX"
SERVES="medioo-web-catalogtest-$SUFFIX"  # a throwaway node container that serves a catalog (the able-to-fail direction)
RUNTIME="medioo-web-runtimeflag-$SUFFIX" # the shipped image started with MEDIOO_CATALOG=1 at run time
TAG="a1b2c3d$SUFFIX"   # the sha the image is built from
OTHER="ffff000$SUFFIX" # the same image under another tag: its baked sha is not this one
FAILED=0
fail() { echo "FAIL: $1"; FAILED=1; }

cleanup() {
  "$REAL_DOCKER" rm -f "$NAME" >/dev/null 2>&1
  "$REAL_DOCKER" rm -f "$SERVES" >/dev/null 2>&1
  "$REAL_DOCKER" rm -f "$RUNTIME" >/dev/null 2>&1
  "$REAL_DOCKER" network rm "$NET" >/dev/null 2>&1
  "$REAL_DOCKER" rmi "$REPO:$TAG" "$REPO:$OTHER" >/dev/null 2>&1
  rm -rf "$WORK"
}
trap cleanup EXIT

mkdir -p "$WORK/bin"
cat > "$WORK/bin/docker" <<SHIM
#!/usr/bin/env bash
if [ "\$1" = pull ]; then
  echo "docker pull \$2" >> "$WORK/pulls.log"
  "$REAL_DOCKER" image inspect "\$2" >/dev/null 2>&1 && exit 0
  echo "Error response from daemon: manifest unknown" >&2; exit 1
fi
exec "$REAL_DOCKER" "\$@"
SHIM
chmod +x "$WORK/bin/docker"
# shellcheck source=deploy_test_fakes.sh
source "$ROOT/scripts/deploy_test_fakes.sh"
write_timeout_if_missing "$WORK/bin"   # macOS only; the box and the CI runner have coreutils timeout

echo "building medioo-web with BUILD_SHA=$TAG"
"$REAL_DOCKER" build -q --build-arg "BUILD_SHA=$TAG" -t "$REPO:$TAG" "$ROOT/medioo-web" >/dev/null \
  || { echo "FAIL: docker build medioo-web failed"; exit 1; }
"$REAL_DOCKER" tag "$REPO:$TAG" "$REPO:$OTHER"
"$REAL_DOCKER" network create "$NET" >/dev/null || { echo "FAIL: could not create $NET"; exit 1; }

# run_web <tag> [env...]: deploy_web from deploy.sh, sourced, with only `docker pull` shimmed
run_web() {
  local tag="$1"; shift
  env PATH="$WORK/bin:$PATH" DEPLOY_FUNCTIONS_ONLY=1 WEB_IMAGE="$REPO" WEB_CONTAINER_NAME="$NAME" EDGE_NETWORK="$NET" "$@" \
    bash -c 'source "$1" && deploy_web "$2"' _ "$DEPLOY" "$tag"
}
insp() { "$REAL_DOCKER" inspect -f "$1" "$NAME" 2>&1; }

# 1. the first deploy: healthy for this sha, and the running container is exactly what deploy.sh says it is
run_web "$TAG" > "$WORK/out1.log" 2>&1 || fail "deploy_web $TAG failed: $(tail -5 "$WORK/out1.log" | tr '\n' '|')"
grep -q "$NAME healthy after .*(sha $TAG)" "$WORK/out1.log" || fail "no healthy line for $TAG: $(tail -5 "$WORK/out1.log" | tr '\n' '|')"
grep -qxF "docker pull $REPO:$TAG" "$WORK/pulls.log" || fail "deploy_web never pulled $REPO:$TAG"
[ "$(insp '{{.Config.Image}}')" = "$REPO:$TAG" ] || fail "running image is $(insp '{{.Config.Image}}'), expected $REPO:$TAG"
[ "$(insp '{{json .HostConfig.PortBindings}}')" = "{}" ] || fail "medioo-web publishes ports: $(insp '{{json .HostConfig.PortBindings}}')"
[ "$(insp '{{.HostConfig.PublishAllPorts}}')" = "false" ] || fail "medioo-web publishes all ports"
[ -z "$("$REAL_DOCKER" port "$NAME" 2>&1)" ] || fail "docker port prints: $("$REAL_DOCKER" port "$NAME" 2>&1)"
[ "$(insp '{{.HostConfig.Memory}}')" = 335544320 ] || fail "memory limit is $(insp '{{.HostConfig.Memory}}'), expected 320m (335544320)"
[ "$(insp '{{.HostConfig.RestartPolicy.Name}}')" = unless-stopped ] || fail "restart policy is $(insp '{{.HostConfig.RestartPolicy.Name}}')"
[ "$(insp '{{index .HostConfig.LogConfig.Config "max-size"}}')" = 10m ] || fail "log max-size is $(insp '{{json .HostConfig.LogConfig}}')"
[ "$(insp '{{index .HostConfig.LogConfig.Config "max-file"}}')" = 3 ] || fail "log max-file is $(insp '{{json .HostConfig.LogConfig}}')"
[ "$(insp '{{len .Mounts}}')" = 0 ] || fail "medioo-web has mounts: $(insp '{{json .Mounts}}')"
[ "$(insp '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}')" = "$NET " ] \
  || fail "networks are [$(insp '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}')], expected only $NET"
# the environment deploy.sh added: the container's env minus the image's own, derived, not listed by hand
"$REAL_DOCKER" image inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$REPO:$TAG" | sed '/^$/d' | LC_ALL=C sort > "$WORK/env.image"
insp '{{range .Config.Env}}{{println .}}{{end}}' | sed '/^$/d' | LC_ALL=C sort > "$WORK/env.container"
[ -s "$WORK/env.image" ] || fail "the image has no env at all: the diff below would be meaningless"
ADDED=$(LC_ALL=C comm -13 "$WORK/env.image" "$WORK/env.container" | tr '\n' ' ')
[ "$ADDED" = "GIT_SHA=$TAG JAVA_INTERNAL_URL=http://medscribe-ai:8086 " ] \
  || fail "deploy.sh added env [$ADDED], expected exactly GIT_SHA=$TAG and JAVA_INTERNAL_URL"
grep -qxF "BUILD_SHA=$TAG" "$WORK/env.image" || fail "the image does not carry BUILD_SHA=$TAG: $(tr '\n' ' ' < "$WORK/env.image")"
# the real server's answer, field by field
HZ=$("$REAL_DOCKER" exec "$NAME" node -e "fetch('http://127.0.0.1:3000/app/healthz').then(r=>r.text()).then(t=>process.stdout.write(t))" 2>&1)
[ "$HZ" = "{\"service\":\"medioo-web\",\"sha\":\"$TAG\",\"build\":\"$TAG\"}" ] || fail "/app/healthz answered $HZ"

# 1b. (W2c) the component catalog is not in the image that ships: deploy.sh's own catalog probe, run in the real
#    container, exits 0 (deploy_web above already required it; this runs it on its own so a failure names it)
CATALOG_PROBE=$(DEPLOY_FUNCTIONS_ONLY=1 bash -c 'source "$1" >/dev/null 2>&1 && printf %s "$WEB_CATALOG_PROBE"' _ "$DEPLOY")
[ -n "$CATALOG_PROBE" ] || fail "deploy.sh defines no WEB_CATALOG_PROBE"
# the pages come from deploy.sh's own WEB_CATALOG_PAGES line (plan W4), passed as deploy.sh passes them: a probe run
# with no page prints "no-pages" and exits 1, so a list restated here could drift from the one that ships
CATALOG_PAGES=$(DEPLOY_FUNCTIONS_ONLY=1 bash -c 'source "$1" >/dev/null 2>&1 && printf %s "$WEB_CATALOG_PAGES"' _ "$DEPLOY")
read -ra PAGES <<< "$CATALOG_PAGES"
[ "${#PAGES[@]}" -gt 0 ] || fail "deploy.sh's WEB_CATALOG_PAGES names no page: every probe below would be vacuous"
FIRST_PAGE="${PAGES[0]:-}"
SAW=$("$REAL_DOCKER" exec "$NAME" node -e "$CATALOG_PROBE" "${PAGES[@]}" 2>/dev/null); RC=$?
[ "$RC:$SAW" = "0:status=404 states=no" ] \
  || fail "the catalog probe failed inside the real image over ${PAGES[*]}: exit $RC '$SAW'"
# ... and the same probe can fail on a real daemon: the image's own base, serving what a catalog build serves on
# 127.0.0.1:3000 (a 200 whose body renders catalog states), makes it exit non-zero
BASE=$(awk '$1 == "FROM" && $NF == "run" { print $2 }' "$ROOT/medioo-web/Dockerfile")
[ -n "$BASE" ] || fail "no run stage base in medioo-web/Dockerfile"
"$REAL_DOCKER" run -d --name "$SERVES" "$BASE" node -e \
  "require('http').createServer((q,s)=>{s.writeHead(200,{'content-type':'text/html'});s.end('<main data-catalog-states=\"13\"><section data-catalog-state=\"StatusChip/crit-high\"></section></main>')}).listen(3000,'127.0.0.1')" \
  >/dev/null || fail "could not start the catalog-serving container"
# Guard: the stand-in really answers the catalog path with a 200 and a state, or the probe's failure below would be
# the container's absence (an exec into nothing also exits non-zero) and prove nothing.
SERVED=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
  SERVED=$("$REAL_DOCKER" exec "$SERVES" node -e "fetch('http://127.0.0.1:3000/app/catalog-test').then(r=>r.text().then(b=>process.stdout.write(r.status+' '+b.includes('data-catalog-state'))))" 2>/dev/null)
  [ "$SERVED" = "200 true" ] && break
  sleep 1
done
if [ "$SERVED" != "200 true" ]; then
  fail "the catalog-serving stand-in never answered /app/catalog-test with 200 and a state (got '$SERVED')"
else
  # not merely non-zero: the definitive outcome (exit 1, what it saw on stdout), the one deploy.sh never retries
  SAW=$("$REAL_DOCKER" exec "$SERVES" node -e "$CATALOG_PROBE" "${PAGES[@]}" 2>/dev/null); RC=$?
  [ "$RC" = 0 ] && fail "the catalog probe passed against a server that serves the catalog: it cannot fail"
  [ "$RC:$SAW" = "1:$FIRST_PAGE status=200 states=yes" ] \
    || fail "the catalog probe against a served catalog gave exit $RC '$SAW', expected exit 1 '$FIRST_PAGE status=200 states=yes'"
fi

# 1c. (W2c) the flag is a build-time fact, not a runtime one: the same shipped image, started with MEDIOO_CATALOG=1 in
#    its runtime environment, still answers /app/catalog-test 404 without states. next.config.ts's `env` inlining is
#    what makes this true; if Next stopped inlining it, only deploy.sh's two -e flags would stand in the way.
"$REAL_DOCKER" run -d --name "$RUNTIME" -e MEDIOO_CATALOG=1 -e "GIT_SHA=$TAG" "$REPO:$TAG" >/dev/null \
  || fail "could not start $REPO:$TAG with MEDIOO_CATALOG=1"
"$REAL_DOCKER" exec "$RUNTIME" printenv MEDIOO_CATALOG 2>/dev/null | grep -qx 1 \
  || fail "the runtime-flag container does not hold MEDIOO_CATALOG=1: the check below would not test it"
UP=""
for _ in $(seq 1 30); do
  UP=$("$REAL_DOCKER" exec "$RUNTIME" node -e "fetch('http://127.0.0.1:3000/app/healthz').then(r=>process.stdout.write(String(r.status))).catch(()=>{})" 2>/dev/null)
  [ "$UP" = 200 ] && break
  sleep 1
done
if [ "$UP" != 200 ]; then
  fail "$REAL_DOCKER run with MEDIOO_CATALOG=1 never answered /app/healthz (got '$UP')"
else
  SAW=$("$REAL_DOCKER" exec "$RUNTIME" node -e "$CATALOG_PROBE" "${PAGES[@]}" 2>/dev/null); RC=$?
  [ "$RC" = 0 ] || fail "with MEDIOO_CATALOG=1 at run time the shipped image serves the catalog: probe exit $RC '$SAW'"
fi

# 2. the same image under another tag: GIT_SHA says OTHER, the image says TAG. The probe refuses it, and the
#    previous container (TAG) is put back and healthy
run_web "$OTHER" WEB_HEALTH_DEADLINE_S=15 > "$WORK/out2.log" 2>&1 && fail "deploy_web $OTHER exited 0 with an image built from $TAG"
grep -q "did not become healthy in 15s (sha $OTHER)" "$WORK/out2.log" || fail "no timeout line for $OTHER: $(tail -5 "$WORK/out2.log" | tr '\n' '|')"
grep -q "previous $NAME ($TAG) is back and healthy" "$WORK/out2.log" || fail "the previous $TAG was not put back healthy: $(tail -5 "$WORK/out2.log" | tr '\n' '|')"
[ "$(insp '{{.Config.Image}}')" = "$REPO:$TAG" ] || fail "after the failed swap the running image is $(insp '{{.Config.Image}}'), expected $REPO:$TAG"

if [ "$FAILED" -ne 0 ]; then exit 1; fi
echo "deploy-web-docker: 4 cases passed (real image, real daemon)"

#!/usr/bin/env bash
#
# Shared fakes for the deploy.sh tests (scripts/test_deploy_prune.sh, scripts/test_deploy_sh.sh): a `docker`,
# `curl`, `df` and `sleep` that record every call, in order, to $FAKE_DIR/calls.log and answer from fixture files
# and env switches. No daemon, no network, no dependence on this machine's disk or clock. Sourced, not executed:
#
#   source scripts/deploy_test_fakes.sh && write_deploy_fakes "$BIN"
#
# One set of fakes for both tests, so the two cannot drift into disagreeing about what docker answers.
#
# The container side is stateful, like the daemon, so a script that gets the order wrong fails here the way it
# would on the box: `rm` of a missing name, `run --name` of a name still in use, and `exec` into a container that
# is not there all fail. The medioo-web health probe is not matched as text: `docker exec <c> node -e <probe> <args>`
# RUNS the probe, with this machine's node, against a stub /app/healthz (healthz-stub.cjs, preloaded) that answers
# for the container as it was started. A probe that ignores the sha, the image or the service fails on behaviour.

# write_deploy_fakes <bin-dir>: writes the fakes into <bin-dir> (to be put first on PATH)
write_deploy_fakes() {
  local bin="$1"
  mkdir -p "$bin"
  cat > "$bin/docker" <<'FAKE'
#!/usr/bin/env bash
# Records every call, one per line, then answers from fixture files in $FAKE_DIR:
#   images.txt   "<repository> <tag> <id> <created...>", any order, every repository (a successful pull adds one)
#   running.txt  "<container id> <image ref it was started with> <image id>", one per running container (prune's view)
#   names.txt    "<container name> [<image ref>]", the containers `docker ps -a` lists (default: just medscribe-ai).
#                `rm` removes a line, `run --name` adds one; container.<name>.env holds its -e values.
# FAKE_RMI_FAIL=<ref>        that rmi is refused, as when a stopped container still holds the image
# FAKE_INSPECT_FAIL=1        `docker inspect` prints nothing and fails
# FAKE_NETWORK=1             the edge network exists
# FAKE_PULL_FAIL=<ref>       pulling exactly <ref> fails, with "Error response from daemon: $FAKE_PULL_ERR"
# FAKE_PULL_ERR=<message>    default "manifest unknown" (GHCR's answer for a tag it does not have)
# FAKE_RUN_FAIL=<ref|repo>   `docker run` of that ref, or of any tag of that repository, fails
# FAKE_EXEC_HANG=1           `docker exec` never returns (only a timeout around it ends it)
# The stub /app/healthz (see healthz-stub.cjs) answers 200 {"service":"medioo-web","sha":<the container's GIT_SHA>,
# "build":<its image's tag, the sha baked into the image>} unless:
# FAKE_WEB_HEALTH=<n|fail>   the first <n> probes, or all of them, find nothing listening (connection refused)
# FAKE_WEB_BAD_REF=<ref>     a container started from <ref> never answers (another ref, e.g. the one put back, does)
# FAKE_HEALTHZ_STATUS=<code|hang>  that HTTP status, or a server that accepts and never answers
# FAKE_HEALTHZ_SERVICE / FAKE_HEALTHZ_SHA / FAKE_HEALTHZ_BUILD   override that field of the answer
# FAKE_HEALTHZ_BODY=<text>   the whole body, verbatim (e.g. not JSON)
# `docker run` writes its arguments, one per line, to run.<name>.args, where <name> is the image's repository
# basename (run.medscribe-ai.args, run.medioo-web.args), so each container's argument boundaries can be checked.
printf 'docker %s\n' "$*" >> "$FAKE_DIR/calls.log"
args="$*"
N="$FAKE_DIR/names.txt"
[ -f "$N" ] || echo medscribe-ai > "$N"
has_name() { awk -v n="$1" '$1 == n {f = 1} END {exit !f}' "$N"; }
case "$1" in
  images)
    cat "$FAKE_DIR/images.txt" ;;
  ps)
    if [[ "$args" == *'{{.Names}}'* ]]; then awk '{print $1}' "$N"
    elif [[ "$args" == *'{{.Image}}'* ]]; then awk '{print $2}' "$FAKE_DIR/running.txt"
    elif [[ " $args " == *' -q '* ]]; then awk '{print $1}' "$FAKE_DIR/running.txt"
    fi ;;
  inspect)
    [ -n "${FAKE_INSPECT_FAIL:-}" ] && { echo "Error: inspect failed" >&2; exit 1; }
    shift
    if [[ "$*" == *'{{.Config.Image}}'* ]]; then   # by container name: the image reference it was started with
      for a in "$@"; do
        case "$a" in --format|-f|'{{.Config.Image}}') continue ;; esac
        has_name "$a" || { echo "Error: No such object: $a" >&2; exit 1; }
        awk -v n="$a" '$1 == n {print $2}' "$N"
      done
      exit 0
    fi
    for a in "$@"; do
      case "$a" in --format|-f) continue ;; '{{.Image}}') continue ;; esac
      awk -v c="$a" '$1==c {print $3}' "$FAKE_DIR/running.txt"
    done ;;
  image)
    # `docker image inspect <ref>`: succeeds only for a tag on disk
    [ "${2:-}" = inspect ] || exit 0
    ref="${*: -1}"
    awk -v ref="$ref" '($1 ":" $2) == ref {f = 1} END {exit !f}' "$FAKE_DIR/images.txt" \
      || { echo "Error: No such image: $ref" >&2; exit 1; } ;;
  rmi)
    if [ -n "${FAKE_RMI_FAIL:-}" ] && [ "$2" = "$FAKE_RMI_FAIL" ]; then
      echo "Error response from daemon: conflict: image is being used by stopped container" >&2; exit 1
    fi
    # stateful, like the daemon: a removed tag is gone from the next `docker images`
    awk -v ref="$2" '($1 ":" $2) != ref' "$FAKE_DIR/images.txt" > "$FAKE_DIR/images.txt.new" \
      && mv "$FAKE_DIR/images.txt.new" "$FAKE_DIR/images.txt" ;;
  rm)
    shift; force=0
    [ "${1:-}" = -f ] && { force=1; shift; }
    for c in "$@"; do
      if has_name "$c"; then
        awk -v n="$c" '$1 != n' "$N" > "$N.new" && mv "$N.new" "$N"
        rm -f "$FAKE_DIR/container.$c.env"
      else
        # as Docker 25 (the box) and 28 do: `rm` of a missing name exits 1; `rm -f` prints the same error, exits 0
        echo "Error response from daemon: No such container: $c" >&2
        [ "$force" = 1 ] || exit 1
      fi
    done ;;
  network)
    [ -n "${FAKE_NETWORK:-}" ] || exit 1 ;;
  pull)
    if [ -n "${FAKE_PULL_FAIL:-}" ] && [ "$2" = "$FAKE_PULL_FAIL" ]; then
      echo "Error response from daemon: ${FAKE_PULL_ERR:-manifest unknown}" >&2; exit 1
    fi
    awk -v ref="$2" '($1 ":" $2) == ref {f = 1} END {exit !f}' "$FAKE_DIR/images.txt" \
      || echo "${2%:*} ${2##*:} sha256:pulled 2026-10-21 10:00:00 +0000 UTC" >> "$FAKE_DIR/images.txt" ;;
  run)
    shift
    last="${*: -1}"; base="${last##*/}"; base="${base%%:*}"
    printf '%s\n' "$@" > "$FAKE_DIR/run.$base.args"
    name=$(awk 'prev == "--name" {print; exit} {prev = $0}' "$FAKE_DIR/run.$base.args")
    if [ -n "${FAKE_RUN_FAIL:-}" ] && { [ "$last" = "$FAKE_RUN_FAIL" ] || [ "${last%:*}" = "$FAKE_RUN_FAIL" ]; }; then
      echo "docker: Error response from daemon: failed to create task for container" >&2; exit 125
    fi
    if [ -n "$name" ] && has_name "$name"; then
      echo "docker: Error response from daemon: Conflict. The container name \"/$name\" is already in use" >&2; exit 125
    fi
    if [ -n "$name" ]; then
      echo "$name $last" >> "$N"
      awk 'prev == "-e" {print} {prev = $0}' "$FAKE_DIR/run.$base.args" > "$FAKE_DIR/container.$name.env"
    fi ;;
  exec)
    shift; c="$1"; shift
    has_name "$c" || { echo "Error response from daemon: No such container: $c" >&2; exit 1; }
    [ -n "${FAKE_EXEC_HANG:-}" ] && exec /bin/sleep 120
    { [ "${1:-}" = node ] && [ "${2:-}" = -e ]; } || { echo "fake docker: only 'exec <c> node -e <code>' is modelled" >&2; exit 2; }
    code="$3"; shift 3
    ref=$(awk -v n="$c" '$1 == n {print $2}' "$N")
    envf="$FAKE_DIR/container.$c.env"
    gs=$(sed -n 's/^GIT_SHA=//p' "$envf" 2>/dev/null | tail -1)
    n=$(( $(cat "$FAKE_DIR/exec.count" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$FAKE_DIR/exec.count"
    status=200
    case "${FAKE_WEB_HEALTH:-0}" in
      fail) status=refused ;;
      *) [ "$n" -gt "${FAKE_WEB_HEALTH:-0}" ] || status=refused ;;
    esac
    [ -n "${FAKE_WEB_BAD_REF:-}" ] && [ "$ref" = "$FAKE_WEB_BAD_REF" ] && status=refused
    [ "$status" = 200 ] && [ -n "${FAKE_HEALTHZ_STATUS:-}" ] && status="$FAKE_HEALTHZ_STATUS"
    body="${FAKE_HEALTHZ_BODY:-$(printf '{"service":"%s","sha":"%s","build":"%s"}' \
      "${FAKE_HEALTHZ_SERVICE:-medioo-web}" "${FAKE_HEALTHZ_SHA:-${gs:-unknown}}" "${FAKE_HEALTHZ_BUILD:-${ref##*:}}")}"
    # the process sees the container's environment (its -e values and the image's baked BUILD_SHA), not the test's
    exec env -i PATH="$PATH" $(cat "$envf" 2>/dev/null) BUILD_SHA="${ref##*:}" \
      FAKE_HZ_STATUS="$status" FAKE_HZ_BODY="$body" \
      node --require "$(dirname "$0")/healthz-stub.cjs" -e "$code" "$@" ;;
  logs)
    echo "fake container log line" ;;
  *) ;;         # logout and the rest: recorded, succeed
esac
exit 0
FAKE
  cat > "$bin/healthz-stub.cjs" <<'FAKE'
// Preloaded by the fake `docker exec ... node -e <probe>`: stands in for the Next server inside the container.
// fetch of exactly http://127.0.0.1:3000/app/healthz answers FAKE_HZ_STATUS with FAKE_HZ_BODY; any other URL is 404;
// "refused" is nothing listening; "hang" accepts and never answers (only the caller's AbortSignal ends it, and an
// interval keeps the event loop alive the way an open socket does).
const status = process.env.FAKE_HZ_STATUS || "200";
const body = process.env.FAKE_HZ_BODY || "";
delete process.env.FAKE_HZ_STATUS;
delete process.env.FAKE_HZ_BODY;
globalThis.fetch = (url, opts = {}) => {
  if (status === "refused") return Promise.reject(new TypeError("fetch failed: connect ECONNREFUSED 127.0.0.1:3000"));
  if (status === "hang") {
    return new Promise((_, reject) => {
      const keepAlive = setInterval(() => {}, 1000);
      opts.signal?.addEventListener("abort", () => { clearInterval(keepAlive); reject(opts.signal.reason); });
    });
  }
  if (String(url) !== "http://127.0.0.1:3000/app/healthz") return Promise.resolve(new Response("not found", { status: 404 }));
  return Promise.resolve(new Response(body, { status: Number(status), headers: { "content-type": "application/json" } }));
};
FAKE
  cat > "$bin/curl" <<'FAKE'
#!/usr/bin/env bash
# answers like Spring's health endpoint with show-details=always: the details must never reach the log
# FAKE_CURL_FAIL=1: Java never becomes healthy
printf 'curl %s\n' "$*" >> "$FAKE_DIR/calls.log"
[ -n "${FAKE_CURL_FAIL:-}" ] && exit 7
echo '{"status":"UP","components":{"diskSpace":{"status":"UP","details":{"total":20959212,"free":5452595,"path":"/app/."}},"r2dbc":{"status":"UP","details":{"database":"PostgreSQL"}}}}'
FAKE
  cat > "$bin/df" <<'FAKE'
#!/usr/bin/env bash
# FAKE_DF_KB: the "Available" KB on / (default 10 GB); "none" prints nothing and fails
printf 'df %s\n' "$*" >> "$FAKE_DIR/calls.log"
[ "${FAKE_DF_KB:-}" = none ] && exit 1
echo "Filesystem     1024-blocks     Used Available Capacity Mounted on"
echo "/dev/nvme0n1p1    20959212 10000000 ${FAKE_DF_KB:-10485760}      52% /"
FAKE
  cat > "$bin/sleep" <<'FAKE'
#!/usr/bin/env bash
# recorded, returns at once: the health waits are counted by their calls, not by the wall clock
printf 'sleep %s\n' "$*" >> "$FAKE_DIR/calls.log"
FAKE
  chmod +x "$bin/docker" "$bin/curl" "$bin/df" "$bin/sleep"
  write_timeout_if_missing "$bin"
}

# write_timeout_if_missing <bin-dir>: deploy.sh bounds each health try with coreutils `timeout`, which the box
# (Amazon Linux) and the CI runner have and macOS does not. Where it is missing, a real one (not a fake: it does
# kill the command) is written, with the same contract: run the command, TERM it after <s> seconds, exit 124.
write_timeout_if_missing() {
  local bin="$1"
  command -v timeout >/dev/null 2>&1 && return 0
  mkdir -p "$bin"
  cat > "$bin/timeout" <<'SHIM'
#!/usr/bin/env bash
exec perl -e '
  my $s = shift; my $pid = fork; die "fork: $!" unless defined $pid;
  if (!$pid) { exec @ARGV or exit 127 }
  $SIG{ALRM} = sub { kill "TERM", $pid; waitpid $pid, 0; exit 124 };
  alarm $s; waitpid $pid, 0; exit(($? & 127) ? 128 + ($? & 127) : $? >> 8);
' "$@"
SHIM
  chmod +x "$bin/timeout"
}

#!/usr/bin/env bash
#
# Shared fakes for the deploy.sh tests (scripts/test_deploy_prune.sh, scripts/test_deploy_sh.sh): a `docker`,
# `curl`, `df` and `sleep` that record every call, in order, to $FAKE_DIR/calls.log and answer from fixture files
# and env switches. No daemon, no network, no dependence on this machine's disk or clock. Sourced, not executed:
#
#   source scripts/deploy_test_fakes.sh && write_deploy_fakes "$BIN"
#
# One set of fakes for both tests, so the two cannot drift into disagreeing about what docker answers.

# write_deploy_fakes <bin-dir>: writes the fakes into <bin-dir> (to be put first on PATH)
write_deploy_fakes() {
  local bin="$1"
  mkdir -p "$bin"
  cat > "$bin/docker" <<'FAKE'
#!/usr/bin/env bash
# Records every call, one per line, then answers from fixture files in $FAKE_DIR:
#   images.txt   "<repository> <tag> <id> <created...>", any order, every repository
#   running.txt  "<container id> <image ref it was started with> <image id>", one per running container
#   names.txt    container names `docker ps -a` lists (default: just medscribe-ai)
# FAKE_RMI_FAIL=<ref>        that rmi is refused, as when a stopped container still holds the image
# FAKE_INSPECT_FAIL=1        `docker inspect` prints nothing and fails
# FAKE_NETWORK=1             the edge network exists
# FAKE_PULL_FAIL=<ref>       pulling exactly <ref> fails as GHCR answers for a tag it does not have
# FAKE_WEB_HEALTH=<n|fail>   `docker exec` (the medioo-web healthz fetch) fails the first <n> times, or always
# `docker run` writes its arguments, one per line, to run.<name>.args, where <name> is the image's repository
# basename (run.medscribe-ai.args, run.medioo-web.args), so each container's argument boundaries can be checked.
printf 'docker %s\n' "$*" >> "$FAKE_DIR/calls.log"
args="$*"
case "$1" in
  images)
    cat "$FAKE_DIR/images.txt" ;;
  ps)
    if [[ "$args" == *'{{.Names}}'* ]]; then
      if [ -f "$FAKE_DIR/names.txt" ]; then cat "$FAKE_DIR/names.txt"; else echo medscribe-ai; fi
    elif [[ "$args" == *'{{.Image}}'* ]]; then awk '{print $2}' "$FAKE_DIR/running.txt"
    elif [[ " $args " == *' -q '* ]]; then awk '{print $1}' "$FAKE_DIR/running.txt"
    fi ;;
  inspect)
    [ -n "${FAKE_INSPECT_FAIL:-}" ] && { echo "Error: inspect failed" >&2; exit 1; }
    shift
    for a in "$@"; do
      case "$a" in --format|-f) continue ;; '{{.Image}}') continue ;; esac
      awk -v c="$a" '$1==c {print $3}' "$FAKE_DIR/running.txt"
    done ;;
  rmi)
    if [ -n "${FAKE_RMI_FAIL:-}" ] && [ "$2" = "$FAKE_RMI_FAIL" ]; then
      echo "Error response from daemon: conflict: image is being used by stopped container" >&2; exit 1
    fi
    # stateful, like the daemon: a removed tag is gone from the next `docker images`
    awk -v ref="$2" '($1 ":" $2) != ref' "$FAKE_DIR/images.txt" > "$FAKE_DIR/images.txt.new" \
      && mv "$FAKE_DIR/images.txt.new" "$FAKE_DIR/images.txt" ;;
  network)
    [ -n "${FAKE_NETWORK:-}" ] || exit 1 ;;
  pull)
    if [ -n "${FAKE_PULL_FAIL:-}" ] && [ "$2" = "$FAKE_PULL_FAIL" ]; then
      echo "Error response from daemon: manifest unknown" >&2; exit 1
    fi ;;
  run)
    shift
    last="${*: -1}"; name="${last##*/}"; name="${name%%:*}"
    printf '%s\n' "$@" > "$FAKE_DIR/run.$name.args" ;;
  exec)
    case "${FAKE_WEB_HEALTH:-0}" in
      fail) exit 1 ;;
      *) n=$(( $(cat "$FAKE_DIR/exec.count" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$FAKE_DIR/exec.count"
         [ "$n" -gt "${FAKE_WEB_HEALTH:-0}" ] || exit 1 ;;
    esac ;;
  logs)
    echo "fake container log line" ;;
  *) ;;         # rm, logout: recorded, succeed
esac
exit 0
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
}

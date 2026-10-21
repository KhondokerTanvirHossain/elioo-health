#!/usr/bin/env bash
#
# Tests for scripts/check-framework-jars.sh, against throwaway BOOT-INF/lib directories of empty files named like
# jars (the check reads names only, which is what the Dockerfile copies into /workspace/app/lib).
#
#   scripts/test_check_framework_jars.sh
#
# Both directions: one Framework version at or above 6.2.19 passes; every way the shipped lib can be wrong fails,
# naming the jar. And the vacuous case: a directory with no Framework jars at all fails rather than passing.
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/check-framework-jars.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FAILED=0

fail() { echo "FAIL: $1"; FAILED=1; }

# lib <name> <framework version> [extra jar ...]: the five required modules plus the others, all at one version
lib() {
  local dir="$WORK/$1" v="$2"; shift 2
  mkdir -p "$dir"
  for m in aop beans context core expression jcl jdbc r2dbc tx web webflux; do : > "$dir/spring-$m-$v.jar"; done
  # Boot, Data and Security jars carry their own version lines and are not Framework modules
  : > "$dir/spring-boot-3.4.2.jar"; : > "$dir/spring-boot-autoconfigure-3.4.2.jar"
  : > "$dir/spring-data-r2dbc-3.4.2.jar"; : > "$dir/spring-security-core-6.4.2.jar"
  : > "$dir/reactor-core-3.7.2.jar"
  for extra in "$@"; do : > "$dir/$extra"; done
  echo "$dir"
}

passes() { # <case> <dir>
  local out; out=$(sh "$SCRIPT" "$2" 2>&1) || fail "$1: refused a good lib: $out"
}

refuses() { # <case> <dir> <text the refusal must name>
  local out
  if out=$(sh "$SCRIPT" "$2" 2>&1); then
    fail "$1: passed, expected a refusal naming $3"
  else
    printf '%s' "$out" | grep -qF "$3" || fail "$1: refused, but not naming $3: $out"
  fi
}

passes "all 6.2.19" "$(lib good 6.2.19)"
passes "a later 6.2.x, one version" "$(lib later 6.2.21)"
passes "context-support is a Framework module too" "$(lib support 6.2.19 spring-context-support-6.2.19.jar)"

refuses "all 6.2.2 (the bomProperty-only build)" "$(lib old 6.2.2)" "spring-webflux-6.2.2.jar"
D=$(lib onestale 6.2.19); rm "$D/spring-webflux-6.2.19.jar"; : > "$D/spring-webflux-6.2.2.jar"
refuses "one module left on 6.2.2" "$D" "spring-webflux-6.2.2.jar"
D=$(lib dup 6.2.19 spring-web-6.2.2.jar)
refuses "a second, older copy of a module" "$D" "spring-web-6.2.2.jar"
D=$(lib mixed 6.2.19); rm "$D/spring-webflux-6.2.19.jar"; : > "$D/spring-webflux-6.2.21.jar"
refuses "two versions, both above the floor" "$D" "spring-webflux-6.2.21.jar"
D=$(lib seven 6.2.19 spring-messaging-7.0.1.jar)
refuses "a stray 7.0 module" "$D" "spring-messaging-7.0.1.jar"
D=$(lib nor2dbc 6.2.19); rm "$D/spring-r2dbc-6.2.19.jar"
refuses "a required module missing" "$D" "spring-r2dbc"
mkdir -p "$WORK/empty"
refuses "an empty lib (the sweep must not pass vacuously)" "$WORK/empty" "spring-core"
refuses "a lib directory that does not exist" "$WORK/nowhere" "not a directory"

if [ "$FAILED" -eq 0 ]; then echo "check-framework-jars: all cases pass"; fi
exit "$FAILED"

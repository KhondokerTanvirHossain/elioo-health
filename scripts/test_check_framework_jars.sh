#!/usr/bin/env bash
#
# Tests for scripts/check-framework-jars.sh, against throwaway BOOT-INF/lib directories of real (tiny) jars, each
# with the manifest its real counterpart carries: the check decides what a Framework jar is from the manifest, not
# the file name, so the fixtures must carry one.
#
#   scripts/test_check_framework_jars.sh
#
# Runs on CI's runner and again inside the Dockerfile's builder image (dash, mawk, its unzip), the runtime the check
# really runs in. Needs bash and python3 (to write the jars).
#
# Every refusal is asserted on the RULE that refused, by its own message, never on the jar name alone: the mix rule
# lists every jar, so "the name is in the output" holds even when the floor rule did nothing. Each case says which
# rules must fire and which must not.
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/check-framework-jars.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FAILED=0

fail() { echo "FAIL: $1"; FAILED=1; }

FLOOR_RULE="below 6.2.19 in"
MIX_RULE="on 2 versions in"
MISSING_RULE="missing from"
NO_MANIFEST_RULE="no readable manifest"

# jars <dir> <spec>...: writes each jar. Specs:
#   F:<title>:<version>[:<file>]   a Framework module's manifest (Automatic-Module-Name is the title with '.' for '-')
#   P:<file>:<title>:<module>:<version>   another project's manifest (Boot, Data, Security)
#   L:<title>:<version>:<file>     a Framework manifest stored AFTER a class entry, not first
#   N:<file>                       a jar with a class and no manifest at all
jars() {
  local dir="$1"; shift
  mkdir -p "$dir"
  python3 -I - "$dir" "$@" <<'PY'
import sys, zipfile, os
d, specs = sys.argv[1], sys.argv[2:]
def manifest(title, module, version):
    # CRLF, and a long header wrapped onto a continuation line, as real manifests have
    deps = "jdk.unsupported,org.jboss.vfs,some.very.long.module.name.that.forces.the.line.past.seventy.two"
    lines = ["Manifest-Version: 1.0", "Implementation-Title: " + title, "Implementation-Version: " + version,
             "Automatic-Module-Name: " + module, "Created-By: 17.0.19 (Oracle Corporation)",
             ("Dependencies: " + deps)[:70], " " + ("Dependencies: " + deps)[70:]]
    return ("\r\n".join(lines) + "\r\n\r\n").encode()
for s in specs:
    p = s.split(":")
    kind = p[0]
    if kind == "F":
        title, version = p[1], p[2]
        name = p[3] if len(p) > 3 else f"{title}-{version}.jar"
        entries = [("META-INF/MANIFEST.MF", manifest(title, title.replace("-", "."), version)), ("x/A.class", b"\0")]
    elif kind == "P":
        name, title, module, version = p[1], p[2], p[3], p[4]
        entries = [("META-INF/MANIFEST.MF", manifest(title, module, version)), ("x/A.class", b"\0")]
    elif kind == "L":
        title, version, name = p[1], p[2], p[3]
        entries = [("x/A.class", b"\0"), ("META-INF/MANIFEST.MF", manifest(title, title.replace("-", "."), version))]
    elif kind == "N":
        name, entries = p[1], [("x/A.class", b"\0")]
    else:
        sys.exit("unknown spec " + s)
    with zipfile.ZipFile(os.path.join(d, name), "w") as z:
        for n, b in entries: z.writestr(n, b)
PY
}

# lib <name> <framework version> [extra spec ...]: eleven Framework modules at one version, plus Boot, Data, Security
# and reactor jars with their own manifests, plus the extras
lib() {
  local dir="$WORK/$1" v="$2"; shift 2
  local specs=()
  for m in aop beans context core expression jcl jdbc r2dbc tx web webflux; do specs+=("F:spring-$m:$v"); done
  specs+=("P:spring-boot-3.4.2.jar:Spring Boot:spring.boot:3.4.2"
          "P:spring-boot-autoconfigure-3.4.2.jar:Spring Boot AutoConfigure:spring.boot.autoconfigure:3.4.2"
          "P:spring-data-r2dbc-3.4.2.jar:Spring Data R2DBC:spring.data.r2dbc:3.4.2"
          # Spring Security's manifest has the Framework shape exactly (title spring-security-core, module
          # spring.security.core); it is another project with its own version line, and must not count
          "P:spring-security-core-6.4.2.jar:spring-security-core:spring.security.core:6.4.2"
          "P:reactor-core-3.7.2.jar:reactor-core:reactor.core:3.7.2"
          "N:jakarta.annotation-api-2.1.1.jar")
  jars "$dir" "${specs[@]}" "$@" || { echo "FAIL: could not write the fixture jars for $1" >&2; exit 1; }
  echo "$dir"
}

# run <dir>: OUT = the check's output, RC = its exit code
run() { OUT=$(sh "$SCRIPT" "$1" 2>&1); RC=$?; }

has_line() { # <text> [<text on the same line>]
  printf '%s\n' "$OUT" | grep -F -- "$1" | grep -qF -- "${2:-$1}"
}

passes() { # <case> <dir>
  run "$2"
  [ "$RC" -eq 0 ] || fail "$1: refused a good lib: $OUT"
}

# refuses <case> <dir> <rule> [<jar the rule's own line must name>] [! <rule that must NOT fire>]...
refuses() {
  local name="$1" dir="$2" rule="$3"; shift 3
  local jar=""
  if [ $# -gt 0 ] && [ "$1" != "!" ]; then jar="$1"; shift; fi
  run "$dir"
  if [ "$RC" -eq 0 ]; then fail "$name: passed, expected the rule '$rule' to refuse it: $OUT"; return; fi
  if [ -n "$jar" ]; then
    has_line "$rule" "$jar" || fail "$name: no '$rule' line naming $jar: $OUT"
  else
    has_line "$rule" || fail "$name: refused, but not by the rule '$rule': $OUT"
  fi
  while [ $# -gt 1 ] && [ "$1" = "!" ]; do
    has_line "$2" && fail "$name: the rule '$2' fired too, it must not: $OUT"
    shift 2
  done
}

# ---- passes
passes "all 6.2.19" "$(lib good 6.2.19)"
passes "a later 6.2.x, one version" "$(lib later 6.2.21)"
passes "7.0.0, one version (above the floor on the major)" "$(lib seven-only 7.0.0)"
passes "context-support at the same version" "$(lib support 6.2.19 F:spring-context-support:6.2.19)"
passes "a Framework manifest stored after other entries is read" "$(lib late 6.2.19 L:spring-oxm:6.2.19:spring-oxm-6.2.19.jar)"

# ---- the floor, one version throughout (no mix, so only the floor rule can refuse)
refuses "all 6.2.2 (the bomProperty-only build)" "$(lib old 6.2.2)" "$FLOOR_RULE" "spring-webflux-6.2.2.jar" ! "$MIX_RULE"
refuses "all 6.2.18 (one below the floor)" "$(lib p18 6.2.18)" "$FLOOR_RULE" "spring-web-6.2.18.jar" ! "$MIX_RULE"
refuses "all 6.2.9 (the string-compare trap: '6.2.9' > '6.2.19')" "$(lib p9 6.2.9)" "$FLOOR_RULE" "spring-web-6.2.9.jar" ! "$MIX_RULE"
refuses "all 6.1.30 (a high patch on a lower minor)" "$(lib m1 6.1.30)" "$FLOOR_RULE" "spring-web-6.1.30.jar" ! "$MIX_RULE"
refuses "all 5.3.39 (a lower major)" "$(lib major5 5.3.39)" "$FLOOR_RULE" "spring-web-5.3.39.jar" ! "$MIX_RULE"

# ---- one stale jar beside a good lib: the floor rule must name it, and the mix rule fires too
D=$(lib onestale 6.2.19); rm "$D/spring-webflux-6.2.19.jar"; jars "$D" F:spring-webflux:6.2.2
refuses "one module left on 6.2.2" "$D" "$FLOOR_RULE" "spring-webflux-6.2.2.jar"
refuses "one module left on 6.2.2, also a mix" "$D" "$MIX_RULE"
refuses "a second, older copy of a module" "$(lib dup 6.2.19 F:spring-web:6.2.2)" "$FLOOR_RULE" "spring-web-6.2.2.jar"
refuses "context-support is counted (stale beside 6.2.19)" "$(lib support-old 6.2.19 F:spring-context-support:6.2.2)" \
  "$FLOOR_RULE" "spring-context-support-6.2.2.jar"
refuses "spring-core-test is counted (a module no list named)" "$(lib coretest 6.2.19 F:spring-core-test:6.2.2)" \
  "$FLOOR_RULE" "spring-core-test-6.2.2.jar"
refuses "a renamed copy is counted by its manifest" "$(lib renamed 6.2.19 F:spring-web:6.2.2:web-repack.jar)" \
  "$FLOOR_RULE" "web-repack.jar"
refuses "a stale manifest stored after other entries is read" \
  "$(lib late-old 6.2.19 L:spring-web:6.2.2:spring-web-6.2.2.jar)" "$FLOOR_RULE" "spring-web-6.2.2.jar"

# ---- a mix with every jar above the floor: only the mix rule may refuse
D=$(lib mixed 6.2.19); rm "$D/spring-webflux-6.2.19.jar"; jars "$D" F:spring-webflux:6.2.21
refuses "two versions, both above the floor" "$D" "$MIX_RULE" ! "$FLOOR_RULE"
refuses "a stray 7.0 module" "$(lib seven 6.2.19 F:spring-messaging:7.0.1)" "$MIX_RULE" ! "$FLOOR_RULE"

# ---- presence: the sweep must not pass vacuously
D=$(lib nor2dbc 6.2.19); rm "$D/spring-r2dbc-6.2.19.jar"
refuses "a required module missing" "$D" "$MISSING_RULE" "spring-r2dbc" ! "$FLOOR_RULE" ! "$MIX_RULE"
# not a required module, so the presence rule cannot be what refuses it
D=$(lib unnamed 6.2.19); rm "$D/spring-tx-6.2.19.jar"; jars "$D" N:spring-tx-6.2.19.jar
refuses "a Spring jar without a readable manifest" "$D" "$NO_MANIFEST_RULE" "spring-tx-6.2.19.jar" \
  ! "$MISSING_RULE" ! "$FLOOR_RULE" ! "$MIX_RULE"
mkdir -p "$WORK/empty"
refuses "an empty lib" "$WORK/empty" "$MISSING_RULE" "spring-core"
refuses "a lib directory that does not exist" "$WORK/nowhere" "not a directory"

if [ "$FAILED" -eq 0 ]; then echo "check-framework-jars: all cases pass"; fi
exit "$FAILED"

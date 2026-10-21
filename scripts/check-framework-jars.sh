#!/bin/sh
#
# Refuses a BOOT-INF/lib whose Spring Framework jars are not one single version at or above 6.2.19.
#
#   sh scripts/check-framework-jars.sh <BOOT-INF/lib directory>
#
# Run by the Dockerfile on the jar the image is built from, right after it is extracted: this is the guard on the
# artefact that ships, built by the image's own Gradle from the files the Dockerfile copies (not CI's checkout).
# CI's build job runs the Dockerfile's builder stage on every PR, so a refusal here fails the PR, not main's deploy.
# 6.2.2, Boot 3.4.2's own Framework, has spring-framework#34178 (fixed by #34388 in 6.2.4): a multipart press of
# several photos can hang forever with no error. build.gradle overrides the version; this proves it arrived.
#
# What a Framework jar is comes from the jar, never from a list of module names: every jar in the lib is opened and
# counts when its manifest's Automatic-Module-Name is its Implementation-Title with '.' for '-' ("spring-webflux",
# "spring.webflux"), the shape every Framework module carries, so a module no one listed, a renamed copy or a
# manifest stored late is still seen. Boot and Data title themselves "Spring Boot ...", "Spring Data ..." and do not
# match. Spring Security's (and Spring AMQP's) manifests do have the Framework shape and carry their own version line,
# so they are set aside by title prefix (OTHER_PROJECTS); a project missing from it counts as Framework and fails as
# a mix, loudly, never as a silent pass. The same rule as frameworkJar() in ShippedSpringFrameworkVersionTest.
#
# Refusals, each its own line so a test can tell which rule fired:
#   "spring-core missing from <lib>"                  a required module absent (an empty sweep never passes)
#   "no readable manifest in <lib>/<jar>"             a spring-* jar the sweep cannot classify
#   "below 6.2.19 in <lib>: <jar> (<title> <version>)" one line per jar under the floor
#   "on <n> versions in <lib> (must be one): ..."     Spring requires all its modules on one version
set -eu

FLOOR_MAJOR=6 FLOOR_MINOR=2 FLOOR_PATCH=19
FLOOR="$FLOOR_MAJOR.$FLOOR_MINOR.$FLOOR_PATCH"
REQUIRED='spring-core spring-web spring-webflux spring-r2dbc spring-jdbc'
# title prefixes of other Spring projects whose manifests have the Framework shape (checked on their jars)
OTHER_PROJECTS='spring-security-|spring-amqp'

LIB=${1:-}
if [ -z "$LIB" ] || [ ! -d "$LIB" ]; then
  echo "check-framework-jars: '$LIB' is not a directory" >&2
  exit 2
fi
if ! command -v unzip >/dev/null 2>&1; then
  echo "check-framework-jars: unzip not found, cannot read the jars' manifests" >&2
  exit 2
fi

# manifest_fields <jar>: "title|module|version" from META-INF/MANIFEST.MF (CRLF stripped, continuation lines
# unfolded); fails when the jar has no readable manifest
manifest_fields() {
  unzip -p "$1" META-INF/MANIFEST.MF 2>/dev/null | tr -d '\r' | awk '
    function keep(l,  k) {
      seen = 1; k = l; sub(/:.*/, "", k); sub(/^[^:]*:[ ]?/, "", l)
      if (k == "Implementation-Title") t = l
      else if (k == "Automatic-Module-Name") m = l
      else if (k == "Implementation-Version") v = l
    }
    /^ / { line = line substr($0, 2); next }
    { if (line != "") keep(line); line = $0 }
    END { if (line != "") keep(line); if (!seen) exit 1; printf "%s|%s|%s\n", t, m, v }'
}

FOUND=$(mktemp)
trap 'rm -f "$FOUND"' EXIT
status=0

for j in "$LIB"/*.jar; do
  [ -f "$j" ] || continue
  f=${j##*/}
  if ! fields=$(manifest_fields "$j"); then
    case $f in
      spring-*)
        echo "check-framework-jars: no readable manifest in $LIB/$f (a Spring jar the sweep cannot classify)" >&2
        status=1 ;;
    esac
    continue
  fi
  title=${fields%%|*}; rest=${fields#*|}; module=${rest%%|*}; version=${rest#*|}
  case $title in spring-*) ;; *) continue ;; esac
  [ -n "$version" ] || continue
  [ "$module" = "$(printf '%s' "$title" | tr '-' '.')" ] || continue
  if printf '%s\n' "$title" | grep -qE "^($OTHER_PROJECTS)"; then continue; fi
  printf '%s|%s|%s\n' "$version" "$title" "$f" >> "$FOUND"
done

for m in $REQUIRED; do
  if ! cut -d'|' -f2 "$FOUND" | grep -qx "$m"; then
    echo "check-framework-jars: $m missing from $LIB" >&2
    status=1
  fi
done

below=$(awk -F'|' -v a="$FLOOR_MAJOR" -v b="$FLOOR_MINOR" -v c="$FLOOR_PATCH" -v lib="$LIB" -v floor="$FLOOR" '
  { split($1, v, /[.-]/); x = v[1] + 0; y = v[2] + 0; z = v[3] + 0
    if (x < a || (x == a && y < b) || (x == a && y == b && z < c))
      printf "check-framework-jars: Spring Framework jar below %s in %s: %s (%s %s)\n", floor, lib, $3, $2, $1 }' "$FOUND")
if [ -n "$below" ]; then
  printf '%s\n' "$below" >&2
  status=1
fi

VERSIONS=$(cut -d'|' -f1 "$FOUND" | sort -u)
count=$(printf '%s\n' "$VERSIONS" | grep -c . || true)
if [ "$count" -gt 1 ]; then
  echo "check-framework-jars: Spring Framework jars on $count versions in $LIB (must be one):" $VERSIONS >&2
  sort -t'|' -k3 "$FOUND" | awk -F'|' '{ printf "  %s (%s %s)\n", $3, $2, $1 }' >&2
  status=1
fi

if [ "$status" -eq 0 ]; then
  echo "check-framework-jars: $(grep -c . "$FOUND") Spring Framework jars (by manifest), all $VERSIONS"
fi
exit "$status"

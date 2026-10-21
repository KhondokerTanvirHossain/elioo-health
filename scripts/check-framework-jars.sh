#!/bin/sh
#
# Refuses a BOOT-INF/lib whose Spring Framework jars are not one single version at or above 6.2.19.
#
#   sh scripts/check-framework-jars.sh <BOOT-INF/lib directory>
#
# Run by the Dockerfile on the jar the image is built from, right after it is extracted, so the check covers the
# artefact that ships, built by the image's own Gradle from the files the Dockerfile copies (not CI's checkout).
# 6.2.2, Boot 3.4.2's own Framework, has spring-framework#34178 (fixed by #34388 in 6.2.4): a multipart press of
# several photos can hang forever with no error. build.gradle overrides the version; this proves it arrived.
#
# A Framework module is spring-<module>-<version>.jar for the modules listed below; Boot, Data and Security jars
# carry their own version lines and are not matched. Spring requires all its modules on one version, so a mix fails
# even when every part is above the floor. A lib with no core/web/webflux/r2dbc/jdbc fails rather than passing.
set -eu

FLOOR_MAJOR=6 FLOOR_MINOR=2 FLOOR_PATCH=19
MODULES='aop|aspects|beans|context|context-indexer|context-support|core|expression|instrument|jcl|jdbc|jms|messaging|orm|oxm|r2dbc|test|tx|web|webflux|webmvc|websocket'
REQUIRED='core web webflux r2dbc jdbc'

LIB=${1:-}
if [ -z "$LIB" ] || [ ! -d "$LIB" ]; then
  echo "check-framework-jars: '$LIB' is not a directory" >&2
  exit 2
fi

JARS=$(ls "$LIB" | grep -E "^spring-($MODULES)-[0-9][^/]*\.jar\$" || true)

status=0
for m in $REQUIRED; do
  if ! printf '%s\n' "$JARS" | grep -qE "^spring-$m-[0-9]"; then
    echo "check-framework-jars: spring-$m missing from $LIB" >&2
    status=1
  fi
done

# version = the name with the module prefix and .jar removed
VERSIONS=$(printf '%s\n' "$JARS" | sed -nE "s/^spring-($MODULES)-([0-9][^/]*)\.jar\$/\2/p" | sort -u)

below=$(printf '%s\n' "$JARS" | sed -nE "s/^spring-($MODULES)-([0-9][^/]*)\.jar\$/\2 &/p" | awk \
  -v a="$FLOOR_MAJOR" -v b="$FLOOR_MINOR" -v c="$FLOOR_PATCH" '
  { split($1, v, /[.-]/); x = v[1] + 0; y = v[2] + 0; z = v[3] + 0
    if (x < a || (x == a && y < b) || (x == a && y == b && z < c)) print $2 }')
if [ -n "$below" ]; then
  echo "check-framework-jars: Spring Framework jars below $FLOOR_MAJOR.$FLOOR_MINOR.$FLOOR_PATCH in $LIB:" >&2
  printf '  %s\n' $below >&2
  status=1
fi

count=$(printf '%s\n' "$VERSIONS" | grep -c . || true)
if [ "$count" -gt 1 ]; then
  echo "check-framework-jars: Spring Framework jars on $count versions in $LIB (must be one):" >&2
  printf '  %s\n' $JARS >&2
  status=1
fi

if [ "$status" -eq 0 ]; then
  echo "check-framework-jars: $(printf '%s\n' "$JARS" | grep -c .) Spring Framework jars, all $VERSIONS"
fi
exit "$status"

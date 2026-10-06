#!/usr/bin/env bash
#
# Refuse any image under src/test/resources that is not a declared synthetic fixture.
#
#   scripts/check-test-fixtures.sh
#
# WHY THIS EXISTS. Two real patient medical records sat in
# medscribe-ai/src/test/resources/test-images/ from the repository's first public commit until 2026-10-06 —
# named patients, hospital identifiers, clinical results. In the SAME directory, in the SAME commit, a
# README said in bold: "Never use real patient data in test images... Remove all PHI."
#
# The rule existed. It was written and broken in one commit, and nothing noticed for three weeks.
#
# A rule in prose asks every contributor to read it, remember it and apply it under deadline. A check asks
# nothing. This is the check. See DR-36.
#
# Adding a fixture is deliberate: put its path in ALLOWED below, in the same commit that adds the file, and
# say in the commit message where it came from. The reviewer then sees a one-line diff that says "a new
# image is entering the test corpus" instead of a binary blob nobody opens.
set -uo pipefail

# Synthetic fixtures only: invented names, invented values, invented facilities. Never a real document,
# however it was obtained — a medical record found on the public web is still a real person's record.
ALLOWED_FILE="$(dirname "$0")/../.test-fixtures-allowlist"

PATTERN='src/test/resources/.*\.(png|jpg|jpeg|gif|webp|bmp|tif|tiff|pdf|heic|heif|avif)$'

# No mapfile/readarray: macOS ships bash 3.2, and a guard that only runs on the CI runner is a guard that
# lets a contributor commit the thing locally and find out later.
FOUND=$(git ls-files | grep -iE "$PATTERN" | sort || true)
ALLOWED=""
[ -f "$ALLOWED_FILE" ] && ALLOWED=$(grep -vE '^[[:space:]]*(#|$)' "$ALLOWED_FILE" | sed 's/[[:space:]]*$//' | sort || true)

found_count=$(printf '%s\n' "$FOUND" | grep -c . || true)
allowed_count=$(printf '%s\n' "$ALLOWED" | grep -c . || true)

violations=""
while IFS= read -r f; do
  [ -z "$f" ] && continue
  if ! printf '%s\n' "$ALLOWED" | grep -qxF "$f"; then
    violations="${violations}${f}
"
  fi
done <<EOF
$FOUND
EOF

# An allowlist entry whose file is gone is a stale entry: it would silently re-admit a path if someone later
# recreated it with different content. Report it, but do not fail the build on tidiness alone.
while IFS= read -r a; do
  [ -z "$a" ] && continue
  [ -f "$a" ] || echo "note: allowlist entry no longer exists, please remove it — $a"
done <<EOF
$ALLOWED
EOF

violation_count=$(printf '%s' "$violations" | grep -c . || true)
if [ "$violation_count" -gt 0 ]; then
  echo
  echo "REFUSING: $violation_count image(s) under src/test/resources are not declared synthetic fixtures:"
  printf '%s' "$violations" | sed 's/^/  /' 
  cat <<'MSG'

Two real patient medical records reached this repository exactly this way, and a README telling people not
to do it did not stop them.

If the file is SYNTHETIC — invented patient, invented values, invented facility — add its path to
.test-fixtures-allowlist in the same commit, and say in the commit message where it came from.

If it is a REAL document, it does not belong here at all. That includes a real record found on the public
web: already-public does not make it synthetic, and a repository that ships one teaches the next contributor
that it is acceptable.
MSG
  exit 1
fi

echo "test fixtures: $found_count image(s) under src/test/resources, all declared (allowlist: $allowed_count)"

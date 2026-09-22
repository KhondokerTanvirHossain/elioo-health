#!/usr/bin/env bash
#
# Draft labels for an UNLABELLED corpus: run extraction once and write each result as a DRAFT label in the
# expected/ format, for a human to verify field by field.
#
#   scripts/draft-labels.sh [corpus-dir]        # default docs/testset/batch2
#
# Why this exists separately from evaluate-extraction.sh: that script refuses to run a document with no
# label, which is correct — a run that scores nothing is a broken harness, not a result. But a dense lab
# report is too much to hand-label from scratch, so the model drafts and the human corrects. The two jobs
# need different tools; weakening the eval harness's guard to do both would remove the check that makes its
# numbers mean anything.
#
# EVERY FILE THIS WRITES IS A DRAFT. "verified": false is written into each one, and the scorer refuses to
# score a file that does not say true. Scoring a model against its own unchecked output measures nothing —
# it would report the model's agreement with itself as accuracy.
#
# Prints counts and cost only, never a value or any document text: the drafts hold the content and they are
# git-ignored with the corpus.
set -euo pipefail

TESTSET="${1:-docs/testset/batch2}"
BASE_URL="${BASE_URL:-http://localhost:8086}"
TOKEN="${BAYMAX_ADMIN_TOKEN:-local-dev-token}"
POLL_SECONDS="${POLL_SECONDS:-180}"
OUT="$TESTSET/expected"

[ -d "$TESTSET" ] || { echo "no corpus at $TESTSET" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
mkdir -p "$OUT"

api() { curl -sS -H "X-Baymax-Admin-Token: $TOKEN" "$@"; }
now_ms() { python3 -c 'import time;print(int(time.time()*1000))'; }

echo "setting up a scratch family..."
FAMILY=$(api -H 'Content-Type: application/json' \
  -d "{\"whatsapp_number\":\"+8809$(date +%H%M%S)01\",\"owner_name\":\"draft\",\"terms_accepted\":true}" \
  "$BASE_URL/api/v1/baymax/families" | jq -r .id)
[ "$FAMILY" != "null" ] || { echo "could not create the family" >&2; exit 1; }

PATIENT=$(api -H 'Content-Type: application/json' \
  -d '{"name":"draft","age":60,"sex":"unknown","proxy_consent":true}' \
  "$BASE_URL/api/v1/baymax/families/$FAMILY/patients" | jq -r .id)

# the free tier stops at 3 documents a month; this corpus is larger
if [ -n "${PGHOST:-}" ]; then
  PGPASSWORD="${PGPASSWORD:-}" psql -h "$PGHOST" -p "${PGPORT:-5433}" -U "${PGUSER:-medscribe}" \
    -d "${PGDATABASE:-medscribe}" -qtc \
    "update baymax.family_account set plan='family' where id='$FAMILY'" >/dev/null 2>&1 || true
fi

WRITTEN=0; RETAKEN=0; FAILED=0
for FILE in "$TESTSET"/*.jpg "$TESTSET"/*.jpeg "$TESTSET"/*.jfif "$TESTSET"/*.png "$TESTSET"/*.webp "$TESTSET"/*.pdf; do
  [ -e "$FILE" ] || continue
  BASE=$(basename "$FILE"); STEM="${BASE%.*}"

  START=$(now_ms)
  DOC=$(api -F "patient_id=$PATIENT" -F "file=@$FILE" "$BASE_URL/api/v1/baymax/documents" | jq -r .document_id)
  STATUS="PROCESSING"
  for _ in $(seq 1 "$POLL_SECONDS"); do
    RESULT=$(api "$BASE_URL/api/v1/baymax/documents/$DOC")
    STATUS=$(echo "$RESULT" | jq -r .status)
    case "$STATUS" in DONE|NEEDS_RETAKE|FAILED) break ;; esac
    sleep 1
  done
  END=$(now_ms)

  echo "$RESULT" | python3 scripts/draft_label.py "$OUT/$STEM.json" "$STEM" "$((END - START))"
  case "$STATUS" in
    DONE) WRITTEN=$((WRITTEN + 1)) ;;
    NEEDS_RETAKE) RETAKEN=$((RETAKEN + 1)) ;;
    *) FAILED=$((FAILED + 1)) ;;
  esac
  printf '.'
done
echo

echo
echo "drafts written to $OUT/"
echo "  DONE          $WRITTEN"
echo "  NEEDS_RETAKE  $RETAKEN"
echo "  FAILED        $FAILED"
echo
echo 'EVERY FILE IS A DRAFT ("verified": false). The scorer refuses to score until a human sets it to true.'

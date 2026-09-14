#!/usr/bin/env bash
#
# BMX-2 evaluation: run the test corpus through the extraction pipeline and score it against the
# hand-labelled expectations in docs/testset/expected/.
#
#   scripts/evaluate-extraction.sh [run-name]
#
# Prints counts, cost, latency and model only. Never a patient name, a value or any document text:
# per-document detail goes to docs/testset/report-<run>.json, which is git-ignored with the corpus.
#
# The three runs the PO asked for, each one a separate invocation with different settings:
#
#   BAYMAX_EXTRACT_SEND_IMAGES=false                                      scripts/evaluate-extraction.sh text-only
#   BAYMAX_EXTRACT_SEND_IMAGES=true                                       scripts/evaluate-extraction.sh vision-cheap
#   BAYMAX_EXTRACT_SEND_IMAGES=true LLM_PROVIDER=anthropic \
#     BAYMAX_EXTRACT_STRONG_MODEL_THRESHOLD=1.0                           scripts/evaluate-extraction.sh strong
#
# The last one forces escalation on every document by putting the threshold above any confidence.
set -euo pipefail

RUN_NAME="${1:-run}"
BASE_URL="${BASE_URL:-http://localhost:8086}"
TESTSET="${TESTSET:-docs/testset}"
REPORT="$TESTSET/report-$RUN_NAME.json"
TOKEN="${BAYMAX_ADMIN_TOKEN:-local-dev-token}"
POLL_SECONDS="${POLL_SECONDS:-180}"

[ -d "$TESTSET" ] || { echo "no corpus at $TESTSET"; exit 1; }
command -v jq >/dev/null || { echo "jq is required"; exit 1; }

api() { curl -sS -H "X-Baymax-Admin-Token: $TOKEN" "$@"; }

# --- a family and a patient to own the run, on the paid plan so the free tier does not stop us ----
echo "setting up a scratch family..."
FAMILY=$(api -H 'Content-Type: application/json' \
  -d "{\"whatsapp_number\":\"+8809$(date +%H%M%S)00\",\"owner_name\":\"eval\",\"terms_accepted\":true}" \
  "$BASE_URL/api/v1/baymax/families" | jq -r .id)
[ "$FAMILY" != "null" ] || { echo "could not create the family"; exit 1; }

PATIENT=$(api -H 'Content-Type: application/json' \
  -d '{"name":"eval","age":60,"sex":"unknown","proxy_consent":true}' \
  "$BASE_URL/api/v1/baymax/families/$FAMILY/patients" | jq -r .id)

if [ -n "${PGHOST:-}" ]; then
  PGPASSWORD="${PGPASSWORD:-}" psql -h "$PGHOST" -p "${PGPORT:-5433}" -U "${PGUSER:-medscribe}" \
    -d "${PGDATABASE:-medscribe}" -qtc \
    "update baymax.family_account set plan='family' where id='$FAMILY'" >/dev/null 2>&1 || true
fi

# --- run every labelled document ------------------------------------------------------------------
echo '{"run":"'"$RUN_NAME"'","documents":[]}' > "$REPORT"
TOTAL=0; LABELLED=0

for FILE in "$TESTSET"/*.jpg "$TESTSET"/*.jfif "$TESTSET"/*.pdf; do
  [ -e "$FILE" ] || continue
  BASE=$(basename "$FILE"); STEM="${BASE%.*}"
  EXPECTED="$TESTSET/expected/$STEM.json"
  TOTAL=$((TOTAL + 1))
  [ -f "$EXPECTED" ] || continue
  LABELLED=$((LABELLED + 1))

  START=$(date +%s%3N 2>/dev/null || python3 -c 'import time;print(int(time.time()*1000))')
  DOC=$(api -F "patient_id=$PATIENT" -F "file=@$FILE" "$BASE_URL/api/v1/baymax/documents" | jq -r .document_id)

  STATUS="PROCESSING"
  for _ in $(seq 1 "$POLL_SECONDS"); do
    RESULT=$(api "$BASE_URL/api/v1/baymax/documents/$DOC")
    STATUS=$(echo "$RESULT" | jq -r .status)
    case "$STATUS" in DONE|NEEDS_RETAKE|FAILED) break ;; esac
    sleep 1
  done
  END=$(date +%s%3N 2>/dev/null || python3 -c 'import time;print(int(time.time()*1000))')

  python3 scripts/score_extraction.py "$REPORT" "$STEM" "$EXPECTED" "$((END - START))" <<< "$RESULT"
  printf '.'
done
echo

# --- cost comes from the cost log, not from a guess -------------------------------------------------
COST_JSON=$(api "$BASE_URL/api/v1/baymax/admin/metrics/weekly" | tail -n +2 || true)

python3 scripts/score_extraction.py --summary "$REPORT" "$TOTAL" "$LABELLED"
echo
echo "per-document detail (git-ignored): $REPORT"

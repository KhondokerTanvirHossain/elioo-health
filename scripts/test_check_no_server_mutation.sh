#!/usr/bin/env bash
#
# Both directions for scripts/check-no-server-mutation.sh, against throwaway fixture trees: what must pass passes,
# each thing it exists to catch fails and names the file, and an empty tree fails rather than passing vacuously.
#
#   scripts/test_check_no_server_mutation.sh
set -uo pipefail

CHECK="$(cd "$(dirname "$0")" && pwd)/check-no-server-mutation.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
failures=0

# case <name> <expect: pass|fail> <message that must appear, or ""> then fixture files as path=content pairs
case_() {
  local name=$1 expect=$2 needle=$3
  shift 3
  local dir="$TMP/$name"
  mkdir -p "$dir"
  for pair in "$@"; do
    local path=${pair%%=*}
    mkdir -p "$dir/$(dirname "$path")"
    printf '%s\n' "${pair#*=}" > "$dir/$path"
  done
  local out status
  out=$("$CHECK" "$dir" 2>&1)
  status=$?
  if { [ "$expect" = pass ] && [ $status -ne 0 ]; } || { [ "$expect" = fail ] && [ $status -eq 0 ]; }; then
    echo "FAIL [$name]: expected $expect, exit $status"; echo "$out"; failures=$((failures + 1)); return
  fi
  if [ -n "$needle" ] && ! printf '%s' "$out" | grep -qF -- "$needle"; then
    echo "FAIL [$name]: output does not name '$needle'"; echo "$out"; failures=$((failures + 1)); return
  fi
  echo "ok   [$name] ($expect)"
}

CLEAN_PAGE='export default function Page() { return null; }'
GET_FETCH='const r = await fetch(`${process.env.JAVA_INTERNAL_URL}/app/api/me`, { method: "GET", headers: { cookie } });'

case_ clean pass "OK:" app/page.tsx="$CLEAN_PAGE" app/healthz/route.ts='export function GET() { return Response.json({}); }'
case_ java-get pass "OK:" api/java.ts="$GET_FETCH"
case_ java-head pass "OK:" api/java.ts='await fetch(process.env.JAVA_INTERNAL_URL + "/x", { method: "HEAD" });'
case_ java-default-method pass "OK:" api/java.ts='await fetch(process.env.JAVA_INTERNAL_URL + "/x");'

case_ empty fail "scanned zero source files"
case_ only-css fail "scanned zero source files" app/globals.css='body { margin: 0 }'
case_ use-server-single fail "app/actions.ts:1" app/page.tsx="$CLEAN_PAGE" app/actions.ts="'use server';"
case_ use-server-double fail "app/actions.ts:2" app/actions.ts='export async function save() {
  "use server";
}'
case_ post-handler fail "app/api/x/route.ts:1" app/api/x/route.ts='export async function POST(req: Request) { return new Response(null); }'
case_ delete-const fail "app/api/x/route.ts:1" app/api/x/route.ts='export const DELETE = async () => new Response(null);'
case_ java-post fail "api/java.ts:1" api/java.ts='await fetch(`${process.env.JAVA_INTERNAL_URL}/app/api/x`, { method: "POST" });'
case_ java-lowercase-put fail "api/java.ts:1" api/java.ts="await fetch(process.env.JAVA_INTERNAL_URL + '/x', { method: 'put' });"
case_ java-variable-method fail "api/java.ts:2" api/java.ts='const m = verb;
await fetch(process.env.JAVA_INTERNAL_URL + "/x", { method: m });'

# Rule 5: only api/java.ts names JAVA_INTERNAL_URL, so rule 3 sees every Java call in one file.
case_ onlyJavaTsNamesTheUrl pass "OK:" api/java.ts="$GET_FETCH" app/page.tsx="$CLEAN_PAGE"
case_ aSecondFileNamingTheUrlFails fail "app/x.ts" api/java.ts="$GET_FETCH" app/x.ts='export const u = process.env.JAVA_INTERNAL_URL;'
case_ aJavaTsElsewhereFails fail "lib/java.ts" lib/java.ts="$GET_FETCH"
case_ aUnitTestMayStubTheUrl pass "OK:" api/java.ts="$GET_FETCH" api/java.test.ts='vi.stubEnv("JAVA_INTERNAL_URL", "http://java.test");'
case_ aTestLookingNameIsNotATest fail "api/java.testing.ts" api/java.ts="$GET_FETCH" api/java.testing.ts='export const u = process.env.JAVA_INTERNAL_URL;'

# A method on a fetch that does not name Java (the browser's own call, say) is not this rule's business.
case_ other-post pass "OK:" lib/browser.ts='await fetch("/app/api/x", { method: "POST" });'
# A word that merely contains POST is not a handler.
case_ postal pass "OK:" app/x.ts='export function POSTAL() { return 1; }'

if [ $failures -ne 0 ]; then
  echo "$failures case(s) failed"
  exit 1
fi
echo "all cases passed"

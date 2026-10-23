#!/usr/bin/env bash
#
# Tests for scripts/caddy-streaming-check.sh (PO ruling P3): a streamed medioo-web response, through a real Caddy
# running the DR-42 replica of the repo's Caddyfile.medioo, still reaches the browser part by part, in zstd and in
# gzip. Real Caddy, real Node, nothing beyond 127.0.0.1 (and the pinned image pulls). Needs docker, or
# CADDY_STREAMING_LOCAL=1 (passed through) for the `caddy` on PATH.
#
#   bash scripts/test_caddy_streaming.sh
#
# Both directions:
# - the repo's file: every check passes, and there are a whole number of rounds of them (2 paths x 2 encodings per
#   Caddy version), so a check that asked nothing cannot pass;
# - a file whose preview handle buffers its responses (`response_buffers 1MiB`, which holds a response until it has
#   read 1 MiB or the end) fails, on the preview path alone, in both encodings and every Caddy version, and says the
#   first part was held back: the check fails ON the defect, and the default handle beside it still passes;
# - a file without the block's `encode zstd gzip` is refused by the replica before Caddy starts: no claim about
#   streaming is made of a setup that is not the measured one.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHECK="$ROOT/scripts/caddy-streaming-check.sh"
CADDYFILE="$ROOT/docs/infra/Caddyfile.medioo"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FAILED=0
CASES=0
fail() { echo "FAIL: $1"; FAILED=1; }
run() { CASES=$((CASES + 1)); bash "$CHECK" "$1" > "$WORK/out.log" 2>&1; }
out() { tr '\n' '|' < "$WORK/out.log" | cut -c1-900; }
count() { grep -c "$1" "$WORK/out.log"; }
checks() { sed -n 's/^caddy-streaming-check: \([0-9]*\) checks, .*/\1/p' "$WORK/out.log"; }

# 1. the repo's file: every check passes, in whole rounds of four
if run "$CADDYFILE"; then :; else fail "repo: exited non-zero: $(out)"; fi
n=$(checks)
if [ -z "$n" ] || [ "$n" -lt 4 ] || [ $((n % 4)) != 0 ]; then
  fail "repo: $n checks, expected a positive multiple of 4 (2 paths x 2 encodings per Caddy): $(out)"
fi
[ "$(count '^PASS ')" = "$n" ] || fail "repo: $(count '^PASS ') PASS lines for $n checks: $(out)"
[ "$(count '^FAIL ')" = 0 ] || fail "repo: FAIL lines: $(out)"
[ "$(count '^PASS /app/preview/stream-check zstd: ')" = $((n / 4)) ] || fail "repo: no zstd PASS on the preview path per Caddy: $(out)"
[ "$(count '^PASS /app/preview/stream-check gzip: ')" = $((n / 4)) ] || fail "repo: no gzip PASS on the preview path per Caddy: $(out)"

# 2. a preview handle that buffers: fails on that path alone, in both encodings, naming the hold-back
perl -0pe 's/(handle \@medioo_preview \{\n\t\treverse_proxy medioo-web:3000)\n/$1 {\n\t\t\tresponse_buffers 1MiB\n\t\t}\n/' \
  "$CADDYFILE" > "$WORK/buffered.Caddyfile"
if cmp -s "$CADDYFILE" "$WORK/buffered.Caddyfile"; then
  fail "buffered: the edit changed nothing in Caddyfile.medioo, so the case would test the repo file again"
else
  if run "$WORK/buffered.Caddyfile"; then fail "buffered: exited 0: $(out)"; fi
  n=$(checks)
  want=$(( ${n:-0} / 2 ))
  [ "$want" -gt 0 ] || fail "buffered: no checks ran: $(out)"
  [ "$(count '^FAIL /app/preview/stream-check \(zstd\|gzip\): the first part was held back until the end, not streamed ')" = "$want" ] \
    || fail "buffered: expected $want 'held back' FAIL lines on the preview path: $(out)"
  [ "$(count '^FAIL ')" = "$want" ] || fail "buffered: $(count '^FAIL ') FAIL lines, expected exactly $want: $(out)"
  [ "$(count '^PASS /app/stream-check ')" = "$want" ] || fail "buffered: the default handle did not still pass: $(out)"
fi

# 3. no `encode zstd gzip`: refused before Caddy starts
perl -0pe 's/\n\tencode zstd gzip\n/\n/' "$CADDYFILE" > "$WORK/no-encode.Caddyfile"
if cmp -s "$CADDYFILE" "$WORK/no-encode.Caddyfile"; then
  fail "no-encode: the edit changed nothing in Caddyfile.medioo"
else
  if run "$WORK/no-encode.Caddyfile"; then fail "no-encode: exited 0: $(out)"; fi
  grep -qF "the DR-42 replica refused" "$WORK/out.log" || fail "no-encode: not refused by the replica: $(out)"
  grep -q '^PASS ' "$WORK/out.log" && fail "no-encode: a check ran: $(out)"
fi

if [ "$FAILED" -ne 0 ]; then exit 1; fi
echo "caddy-streaming: $CASES cases passed"

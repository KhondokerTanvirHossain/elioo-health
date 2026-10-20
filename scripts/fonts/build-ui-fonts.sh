#!/usr/bin/env bash
# Builds Medioo's two self-hosted fallback fonts (SIL OFL 1.1). Run by hand from the repo root; outputs are committed.
# local() names come first in baymax.css, so most Android phones never download these (spec §8).
set -euo pipefail
OUT="baymax/src/main/resources/baymax/ui"
GF="47d54ca1ebb14fd864695f1d39dc734f39017c33"   # github.com/google/fonts main, 2026-10-19
BN_URL="https://raw.githubusercontent.com/google/fonts/$GF/ofl/notosansbengali/NotoSansBengali%5Bwdth%2Cwght%5D.ttf"
LA_URL="https://raw.githubusercontent.com/google/fonts/$GF/ofl/notosans/NotoSans%5Bwdth%2Cwght%5D.ttf"
BN_OFL_URL="https://raw.githubusercontent.com/google/fonts/$GF/ofl/notosansbengali/OFL.txt"
LA_OFL_URL="https://raw.githubusercontent.com/google/fonts/$GF/ofl/notosans/OFL.txt"
BN_SHA="dcd42978094e584a849c84a51450eeac40c8826057d566ea6d4b9627a403a05a"
LA_SHA="bfb7bb691513f12e734dc346c03a03f784912432d7e3fa8e56efcf906fe86b3d"
# fontTools stamps head.modified with "now" unless this is set; pinned, so two runs write the same bytes.
export SOURCE_DATE_EPOCH=1792368000   # 2026-10-19T00:00:00Z
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
python3 -m venv "$WORK/venv"
"$WORK/venv/bin/pip" install -q fonttools==4.53.1 brotli==1.1.0
curl -fsSL "$BN_URL" -o "$WORK/bn.ttf"; echo "$BN_SHA  $WORK/bn.ttf" | shasum -a 256 -c -
curl -fsSL "$LA_URL" -o "$WORK/la.ttf"; echo "$LA_SHA  $WORK/la.ttf" | shasum -a 256 -c -
# The licence travels with the fonts; AssetHandler keeps it private (in the jar, never served). The two OFL files differ
# only in their copyright line, so one file carries both lines and the licence text once.
curl -fsSL "$BN_OFL_URL" -o "$WORK/bn-ofl.txt"; curl -fsSL "$LA_OFL_URL" -o "$WORK/la-ofl.txt"
[ "$(tail -n +2 "$WORK/bn-ofl.txt")" = "$(tail -n +2 "$WORK/la-ofl.txt")" ] || { echo "the two OFL texts differ beyond line 1" >&2; exit 1; }
{ head -n 1 "$WORK/bn-ofl.txt"; head -n 1 "$WORK/la-ofl.txt"; tail -n +2 "$WORK/bn-ofl.txt"; } > "$OUT/OFL-Noto.txt"
"$WORK/venv/bin/fonttools" varLib.instancer "$WORK/bn.ttf" wdth=100 wght=400:700 -q -o "$WORK/bn-i.ttf"
"$WORK/venv/bin/fonttools" varLib.instancer "$WORK/la.ttf" wdth=100 wght=400:700 -q -o "$WORK/la-i.ttf"
# Bengali block + danda + ZWNJ/ZWJ + dotted circle; every layout feature, because Bangla shaping needs them all
"$WORK/venv/bin/pyftsubset" "$WORK/bn-i.ttf" --unicodes="U+0964-0965,U+0980-09FE,U+200C-200D,U+25CC" \
  --layout-features='*' --flavor=woff2 --no-hinting --desubroutinize --output-file="$OUT/noto-sans-bengali.woff2"
"$WORK/venv/bin/pyftsubset" "$WORK/la-i.ttf" --unicodes="U+0020-007E,U+00A0-00FF,U+00B5,U+2013-2014,U+2018-201D,U+2022,U+2026,U+2192,U+2212" \
  --layout-features='*' --flavor=woff2 --no-hinting --desubroutinize --output-file="$OUT/noto-sans-latin.woff2"
# Every code point a Bangla page can show must be in the Bengali subset's cmap (FontCoverageTest checks the CSS side).
"$WORK/venv/bin/python" - "$OUT/noto-sans-bengali.woff2" baymax/src/main/resources/baymax <<'PY'
import sys, glob
from fontTools.ttLib import TTFont
cmap = TTFont(sys.argv[1]).getBestCmap()
need = set()
for f in glob.glob(sys.argv[2] + "/ui*_bn.properties") + [sys.argv[2] + "/messages_bn.properties"]:
    for ch in open(f, encoding="utf-8").read():
        if 0x0980 <= ord(ch) <= 0x09FF or ch in "।॥‌‍":
            need.add(ord(ch))
assert need, "no Bangla code points found in the bundles: the check would pass vacuously"
missing = sorted(c for c in need if c not in cmap)
assert not missing, "missing from the Bengali subset: " + " ".join("U+%04X" % c for c in missing)
print("bengali subset covers", len(need), "code points")
PY
ls -l "$OUT"/noto-sans-*.woff2   # measured 2026-10-20: 69,756 and 27,788 bytes
# sha256 of the committed outputs (2026-10-20, two runs identical); a re-run that prints others changed the inputs or tools:
#   960239c1daab6c3a93965b39fadf9d0e5603a4e2a0c3ce0726978bdc36ed8417  noto-sans-bengali.woff2
#   b578a795cf74ff1130e26d7702e199a9ae830974b7e34ed4d8c7d76d51f7af12  noto-sans-latin.woff2
shasum -a 256 "$OUT"/noto-sans-*.woff2

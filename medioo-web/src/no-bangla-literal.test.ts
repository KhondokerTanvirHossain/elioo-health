import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

// Global Constraints: no Bangla literal (U+0980–U+09FF) in medioo-web/src outside generated copy. All copy comes from
// the ui-*/messages_* bundles; W2b generates it and replaces this test with its lint, which must cover every directory
// the build imports from. Until then copy/probe.bn.json is the one deliberate exception, outside src/ and pinned
// verbatim to the .properties by probe-copy.test.ts.
const SRC = __dirname;
const BANGLA = /[\u0980-\u09FF]/u;

function filesUnder(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    return entry.isDirectory() ? filesUnder(path) : [path];
  });
}

describe("medioo-web/src", () => {
  const files = filesUnder(SRC);

  it("has files to check", () => {
    expect(files.length).toBeGreaterThan(0);
    expect(files.map((f) => relative(SRC, f))).toContain("middleware.ts");
  });

  it("contains no Bangla literal", () => {
    const offenders = files.flatMap((file) =>
      readFileSync(file, "utf8")
        .split("\n")
        .flatMap((line, i) => (BANGLA.test(line) ? [`${relative(SRC, file)}:${i + 1}`] : [])),
    );
    expect(offenders).toEqual([]);
  });
});

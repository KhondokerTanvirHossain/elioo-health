import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import copy from "../../../../copy/probe.bn.json";

// All copy comes from the .properties files (Global Constraints). The probe's copy is a hand-made stopgap until W2b's
// codegen, so this pins every string to the shipped key it claims to be: no invented or drifted Bangla.
const UI = join(__dirname, "../../../../../baymax/src/main/resources/baymax");

function properties(file: string): Map<string, string> {
  const map = new Map<string, string>();
  for (const line of readFileSync(join(UI, file), "utf8").split("\n")) {
    if (line.startsWith("#") || !line.includes("=")) continue;
    const at = line.indexOf("=");
    map.set(line.slice(0, at).trim(), line.slice(at + 1));
  }
  return map;
}

describe("probe copy", () => {
  const shipped = new Map([...properties("ui-shell_bn.properties"), ...properties("ui_bn.properties")]);
  const keys = Object.keys(copy).filter((k) => !k.startsWith("_"));

  it("has strings to check", () => {
    expect(keys.length).toBeGreaterThan(0);
  });

  it.each(keys)("%s is the shipped string, verbatim", (key) => {
    expect(shipped.has(key)).toBe(true);
    expect(copy[key as keyof typeof copy]).toBe(shipped.get(key));
  });
});

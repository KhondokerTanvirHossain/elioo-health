import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

// The NOW and THIS_WEEK names live only in their components (plan W2a, Global Constraints). The built-CSS test sees
// which selector uses --now; this sees which component asks for the class, so className="status-chip--crit-high" on a
// non-NOW element, or fill="var(--now)" on an SVG, is caught where a CSS check cannot see it. Component tests outside
// the allowed directories refer to the classes through NOW_ALLOWED / WEEK_ALLOWED, never as literals.
const src = join(dirname(fileURLToPath(import.meta.url)), "..");

const RULES: { names: string[]; allowed: RegExp[] }[] = [
  {
    names: ["--now", "now-banner", "verdict-card--now", "status-chip--crit"],
    allowed: [/^src\/components\/safety\//, /^src\/components\/StatusChip\//, /^src\/styles\//, /^src\/lint\//],
  },
  {
    names: ["--week", "verdict-card--week"],
    allowed: [/^src\/components\/safety\//, /^src\/styles\//, /^src\/lint\//],
  },
];

/** Each NOW or THIS_WEEK name a file outside its allowed directories holds, as `<file> <name>`. */
function scanFile(rel: string, text: string): string[] {
  const found: string[] = [];
  for (const rule of RULES) {
    if (rule.allowed.some((a) => a.test(rel))) continue;
    for (const name of rule.names) if (text.includes(name)) found.push(`${rel} ${name}`);
  }
  return found;
}

function sources(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) out.push(...sources(path));
    else if (/\.(ts|tsx)$/.test(entry)) out.push(path);
  }
  return out;
}

describe("NOW and THIS_WEEK class names in TypeScript", () => {
  it("nowAndWeekNamesLiveOnlyInTheirComponents", () => {
    const scanned = sources(src).map((path) => relative(join(src, ".."), path).split(sep).join("/"));
    const offenders = scanned.flatMap((rel) => scanFile(rel, readFileSync(join(src, "..", rel), "utf8")));
    expect(offenders).toEqual([]);
    // The scan read the tree it claims to, and read files outside the allowed directories.
    expect(scanned).toContain("src/app/layout.tsx");
    expect(scanned).toContain("src/app/app/probe/page.tsx");
  });

  it("aProbeOutsideTheComponentsIsReported", () => {
    const probe = '<span className="status-chip--crit-high" /><svg><path fill="var(--now)" /></svg>';
    expect(scanFile("src/components/Probe/Probe.tsx", probe)).toEqual([
      "src/components/Probe/Probe.tsx --now",
      "src/components/Probe/Probe.tsx status-chip--crit",
    ]);
    expect(scanFile("src/components/Probe/Probe.tsx", '<div className="verdict-card--week" />')).toEqual([
      "src/components/Probe/Probe.tsx --week",
      "src/components/Probe/Probe.tsx verdict-card--week",
    ]);
    // StatusChip may name its crit class, never the THIS_WEEK one; safety/ may name both.
    expect(scanFile("src/components/StatusChip/StatusChip.tsx", probe)).toEqual([]);
    expect(scanFile("src/components/StatusChip/StatusChip.tsx", "verdict-card--week")).toEqual([
      "src/components/StatusChip/StatusChip.tsx --week",
      "src/components/StatusChip/StatusChip.tsx verdict-card--week",
    ]);
    expect(scanFile("src/components/safety/NowBanner/NowBanner.tsx", `${probe} verdict-card--week`)).toEqual([]);
  });
});

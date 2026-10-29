import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

// The NOW and THIS_WEEK names live only in their components (plan W2a, Global Constraints). The built-CSS test sees
// which selector uses --now; this sees which component asks for the class, so className="status-chip--crit-high" on a
// non-NOW element, or fill="var(--now)" on an SVG, is caught where a CSS check cannot see it. Component tests outside
// the allowed directories refer to the classes through NOW_ALLOWED / WEEK_ALLOWED, never as literals.
const src = join(dirname(fileURLToPath(import.meta.url)), "..");

// A token name ends where an identifier character would continue it, as in the CSS checks: --now, --now-ink and
// --on-now are NOW tokens, --nowrap is not; --week-soft is THIS_WEEK, a BEM modifier like day-cell--weekend is not.
// The plain class strings are matched as written, except that `now-banner` right after `api/` is B3's path segment
// (fixtures/api/now-banner/ and /app/api/now-banner), not the class. Only that segment is exempt: `now-banner` after
// any other slash (`styles/now-banner`, `x/now-banner__call`) is still reported.
// MonthCalendar's colour modifiers (PO ruling R2, DR-48) end in --now / --week like a token; they are not the tokens,
// and have their own rule below, so the token patterns skip exactly them. UrgencyTag's (PO ruling P5) likewise.
const CAL_MARK = "month-calendar__(?:alert|key)";
const TAG = "urgency-tag";
const MODIFIER_BLOCKS = `${CAL_MARK}|${TAG}`;
const RULES: { names: [label: string, pattern: RegExp][]; allowed: RegExp[] }[] = [
  {
    names: [
      ["--now", new RegExp(`(?<!${MODIFIER_BLOCKS})--(?:on-)?now(?![A-Za-z0-9_])`)],
      ["now-banner", /(?<!api\/)now-banner/],
      ["verdict-card--now", /verdict-card--now/],
      ["status-chip--crit", /status-chip--crit/],
    ],
    allowed: [/^src\/components\/safety\//, /^src\/components\/StatusChip\//, /^src\/styles\//, /^src\/lint\//],
  },
  {
    names: [
      ["--week", new RegExp(`(?<!${MODIFIER_BLOCKS})--(?:on-)?week(?![A-Za-z0-9_])`)],
      ["verdict-card--week", /verdict-card--week/],
    ],
    allowed: [/^src\/components\/safety\//, /^src\/styles\//, /^src\/lint\//],
  },
  {
    names: [["month-calendar__(alert|key)--now", new RegExp(`${CAL_MARK}--now(?![A-Za-z0-9_])`)]],
    allowed: [/^src\/components\/MonthCalendar\//, /^src\/styles\//, /^src\/lint\//],
  },
  {
    names: [["month-calendar__(alert|key)--week", new RegExp(`${CAL_MARK}--week(?![A-Za-z0-9_])`)]],
    allowed: [/^src\/components\/MonthCalendar\//, /^src\/styles\//, /^src\/lint\//],
  },
  {
    names: [["urgency-tag--now", new RegExp(`${TAG}--now(?![A-Za-z0-9_])`)]],
    allowed: [/^src\/components\/safety\/UrgencyTag\//, /^src\/styles\//, /^src\/lint\//],
  },
  {
    names: [["urgency-tag--week", new RegExp(`${TAG}--week(?![A-Za-z0-9_])`)]],
    allowed: [/^src\/components\/safety\/UrgencyTag\//, /^src\/styles\//, /^src\/lint\//],
  },
];

/** Each NOW or THIS_WEEK name a file outside its allowed directories holds, as `<file> <name>`. */
function scanFile(rel: string, text: string): string[] {
  const found: string[] = [];
  for (const rule of RULES) {
    if (rule.allowed.some((a) => a.test(rel))) continue;
    for (const [label, pattern] of rule.names) if (pattern.test(text)) found.push(`${rel} ${label}`);
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

  it("thePathSegmentNowBannerIsNotTheClass", () => {
    // B3's fixtures live under fixtures/api/now-banner/ and its route is /app/api/now-banner: a path segment, which the
    // API types and the catalog must name, is not the class. Each spelling of the class is still reported (W2d-b).
    const paths = [
      'import one from "../../fixtures/api/now-banner/one.json";',
      "// GET /app/api/now-banner, PUT /app/api/now-banner/ack",
    ];
    for (const text of paths) expect(scanFile("src/catalog/states.tsx", text), text).toEqual([]);
    const classes = [
      '<div className="now-banner" />',
      '<div className="card now-banner__call" />',
      "<div className={`x ${y} now-banner--abroad`} />",
      'el.querySelector(".now-banner__line")',
      "<div className={['now-banner'].join(' ')} />",
      // After a slash that is not B3's `api/` segment, the name is still the class's.
      'import "../styles/now-banner.css";',
      "<div className={`${base}/now-banner__call`} />",
    ];
    for (const text of classes) expect(scanFile("src/components/Probe/Probe.tsx", text), text).toEqual(["src/components/Probe/Probe.tsx now-banner"]);
    // A path and a class in one file is still the class.
    expect(scanFile("src/catalog/states.tsx", `${paths[0]}\n${classes[0]}`)).toEqual(["src/catalog/states.tsx now-banner"]);
  });

  it("theCalendarsColourModifiersLiveOnlyInMonthCalendar", () => {
    // PO ruling R2 (DR-48): the calendar's NOW and THIS_WEEK marks carry their colour through these modifiers, which
    // MonthCalendar may name and nothing else may.
    const cal = "src/components/MonthCalendar/MonthCalendar.tsx";
    const marks = '<span className="month-calendar__alert month-calendar__alert--now" /><li className="month-calendar__key--week" />';
    expect(scanFile(cal, marks)).toEqual([]);
    expect(scanFile("src/components/MonthCalendar/MonthCalendar.cases.tsx", "month-calendar__key--now month-calendar__alert--week")).toEqual([]);
    expect(scanFile("src/components/Probe/Probe.tsx", marks)).toEqual([
      "src/components/Probe/Probe.tsx month-calendar__(alert|key)--now",
      "src/components/Probe/Probe.tsx month-calendar__(alert|key)--week",
    ]);
    // MonthCalendar is not a NOW component: a NOW token, or another component's NOW class, is still reported there.
    expect(scanFile(cal, '<path fill="var(--now)" />')).toEqual([`${cal} --now`]);
    expect(scanFile(cal, 'style={{ color: "var(--week)" }}')).toEqual([`${cal} --week`]);
    expect(scanFile(cal, '<span className="status-chip--crit-high verdict-card--week" />')).toEqual([
      `${cal} status-chip--crit`,
      `${cal} --week`,
      `${cal} verdict-card--week`,
    ]);
  });

  it("aPapersUrgencyTagsColourModifiersLiveOnlyInUrgencyTag", () => {
    // PO ruling P5 (2026-10-28): a paper's NOW tag on the papers list and the attention strip carries the NOW red, and
    // its THIS_WEEK tag the amber, through these modifiers, which UrgencyTag may name and nothing else may: not the
    // list or the page that place the tag, and not another safety component.
    const tag = "src/components/safety/UrgencyTag/UrgencyTag.tsx";
    const both = '<span className="urgency-tag urgency-tag--now" /><span className="urgency-tag urgency-tag--week" />';
    expect(scanFile(tag, both)).toEqual([]);
    expect(scanFile("src/components/safety/UrgencyTag/UrgencyTag.test.tsx", both)).toEqual([]);
    for (const file of [
      "src/components/MonthList/MonthList.tsx",
      "src/app/app/(signed-in)/timeline/page.tsx",
      "src/components/safety/NowBanner/NowBanner.tsx",
      "src/components/MonthCalendar/MonthCalendar.tsx",
    ]) {
      expect(scanFile(file, both), file).toEqual([`${file} urgency-tag--now`, `${file} urgency-tag--week`]);
    }
    // The modifiers are matched by name: a look-alike is not one, and the plain block class names no level.
    expect(scanFile("src/components/MonthList/MonthList.tsx", '<span className="urgency-tag urgency-tag--nowish urgency-tag--weekly" />')).toEqual([]);
  });

  it("aNowTokenIsMatchedByItsNameNotBySubstring", () => {
    // --on-now is a NOW token (the CSS checks say so), though it does not contain the substring "--now".
    expect(scanFile("src/components/Probe/Probe.tsx", '<svg><path fill="var(--on-now)" /></svg>')).toEqual([
      "src/components/Probe/Probe.tsx --now",
    ]);
    expect(scanFile("src/components/Probe/Probe.tsx", 'style={{ color: "var(--now-ink)" }}')).toEqual(["src/components/Probe/Probe.tsx --now"]);
    // A BEM modifier or a token that only starts with the letters is not NOW or THIS_WEEK.
    expect(scanFile("src/components/MonthCalendar/MonthCalendar.tsx", '<td className="day-cell--weekend month-calendar__day--weekly" />')).toEqual([]);
    expect(scanFile("src/components/Probe/Probe.tsx", 'style={{ whiteSpace: "var(--nowrap)" }}')).toEqual([]);
  });
});

import { readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { t, type CopyKey, type Lang } from "@/copy";
import failed from "../../fixtures/api/documents/failed.json";
import notFound from "../../fixtures/api/documents/not-found.json";
import nowAbroad from "../../fixtures/api/documents/now-abroad.json";
import nowBd from "../../fixtures/api/documents/now-bd.json";
import processing from "../../fixtures/api/documents/processing.json";
import retakeThird from "../../fixtures/api/documents/retake-third.json";
import routineReleased from "../../fixtures/api/documents/routine-released.json";
import thisWeekPending from "../../fixtures/api/documents/this-week-pending.json";
import unassessed from "../../fixtures/api/documents/unassessed.json";
// B3's generated GET /app/api/now-banner bodies (baymax NowBannerApiContractTest), imported as JSON so the assignments
// below are checked by `pnpm typecheck`: a field the type names that a fixture drops or retypes does not compile. A
// field Java ADDS compiles silently (an imported value gets no excess-property check), so the key sets are pinned at
// runtime below, against lists the compiler holds equal to the types' keys.
import abroad from "../../fixtures/api/now-banner/abroad.json";
import acked from "../../fixtures/api/now-banner/acked.json";
import one from "../../fixtures/api/now-banner/one.json";
import twoPatients from "../../fixtures/api/now-banner/two-patients.json";
// B3's generated calendar months and B6's generated labs bodies (plan W2e-b).
import busy from "../../fixtures/api/calendar/busy.json";
import crowded from "../../fixtures/api/calendar/crowded.json";
import empty from "../../fixtures/api/calendar/empty.json";
import fortyFive from "../../fixtures/api/calendar/forty-five.json";
import september from "../../fixtures/api/calendar/september.json";
// B3's generated timeline pages, doctor's dates and attention stacks (plan W8).
import attentionEmpty from "../../fixtures/api/attention/empty.json";
import attentionFull from "../../fixtures/api/attention/full-stack.json";
import attentionNow from "../../fixtures/api/attention/now-only.json";
import followsNone from "../../fixtures/api/follow-ups/none.json";
import followsSome from "../../fixtures/api/follow-ups/some.json";
import timelineEmpty from "../../fixtures/api/timeline/empty.json";
import timelineFirst from "../../fixtures/api/timeline/first-page.json";
import timelineFortyFive from "../../fixtures/api/timeline/forty-five-first-page.json";
import timelineInFlight from "../../fixtures/api/timeline/in-flight.json";
import timelineLab from "../../fixtures/api/timeline/lab-only.json";
import timelineLast from "../../fixtures/api/timeline/last-page.json";
import timelineRxNone from "../../fixtures/api/timeline/rx-none.json";
import timelineRx from "../../fixtures/api/timeline/rx-only.json";
import bp from "../../fixtures/api/health/bp.json";
import labsNonNumeric from "../../fixtures/api/health/labs-non-numeric.json";
import labsNone from "../../fixtures/api/health/labs-none.json";
import labsPrintedRange from "../../fixtures/api/health/labs-printed-range.json";
import labsSome from "../../fixtures/api/health/labs-some.json";
import labsWithUnknown from "../../fixtures/api/health/labs-with-unknown.json";
// B1's generated GET /app/api/me bodies (baymax MeApiContractTest), plan W4: the shell reads every field it renders here.
import meFlagsSet from "../../fixtures/api/me/flags-set.json";
import meLongName from "../../fixtures/api/me/long-name.json";
import meNoPatients from "../../fixtures/api/me/no-patients.json";
import meOnePatient from "../../fixtures/api/me/one-patient.json";
import mePreviewOff from "../../fixtures/api/me/preview-off.json";
import mePreviewOn from "../../fixtures/api/me/preview-on.json";
import meTwoOwned from "../../fixtures/api/me/two-owned.json";
import meTwoOwnedLong from "../../fixtures/api/me/two-owned-long.json";
import meTwoPatients from "../../fixtures/api/me/two-patients.json";
import meUnauthenticated from "../../fixtures/api/me/unauthenticated.json";
// W7's generated add-sheet bodies (baymax UploadApiContractTest).
import uploadCapReached from "../../fixtures/api/upload-options/cap-reached.json";
import uploadDownscaleOn from "../../fixtures/api/upload-options/downscale-on.json";
import uploadLongName from "../../fixtures/api/upload-options/long-name.json";
import uploadOwnerOne from "../../fixtures/api/upload-options/owner-one.json";
import uploadOwnerTwo from "../../fixtures/api/upload-options/owner-two.json";
import uploadOwnerTwoAsked from "../../fixtures/api/upload-options/owner-two-asked.json";
import uploadViewOnly from "../../fixtures/api/upload-options/view-only.json";
import uploadedOne from "../../fixtures/api/uploads/one-document.json";
import uploadedSeparate from "../../fixtures/api/uploads/separate.json";
import threadDefault from "../../fixtures/api/thread/default.json";
import threadFailed from "../../fixtures/api/thread/failed.json";
import threadFirstRun from "../../fixtures/api/thread/first-run.json";
import threadNowAbroad from "../../fixtures/api/thread/now-abroad.json";
import threadNow from "../../fixtures/api/thread/now.json";
import threadRetake from "../../fixtures/api/thread/retake.json";
import threadSecondPage from "../../fixtures/api/thread/second-page.json";
import threadShareMember from "../../fixtures/api/thread/share-member.json";
import {
  ATTENTION_KINDS,
  BANNER_LEVELS,
  MESSAGE_KINDS,
  DISPLAY_KINDS,
  GATES,
  PAPER_STATUSES,
  READING_STATUSES,
  SIDES,
  URGENCIES,
  type AttentionJson,
  type BpPointJson,
  type CalendarDayJson,
  type CalendarFollowUpJson,
  type CalendarJson,
  type CalendarPaperJson,
  type AttentionFollowUpJson,
  type AttentionReadingJson,
  type AttentionRetakeJson,
  type AttentionUrgentJson,
  type FollowUpItemJson,
  type FollowUpsJson,
  type TimelineCountsJson,
  type TimelineEntryJson,
  type TimelineJson,
  type Json,
  type LabSeriesJson,
  type LabsJson,
  type MeJson,
  type MePatient,
  type NowBannerDocumentJson,
  type NowBannerJson,
  type NowSeenJson,
  type PaperJson,
  type ThreadJson,
  type TrendPointJson,
  type UploadAnswerJson,
  type UploadOptionsJson,
  type UploadPatientJson,
} from "./types";

const nowBannerDir = join(__dirname, "..", "..", "fixtures/api/now-banner");

// Each assignment is the type check: a fixture that is not a NowBannerJson does not compile.
const NOW_BANNER: Record<string, NowBannerJson> = {
  "abroad.json": abroad.body,
  "acked.json": acked.body,
  "one.json": one.body,
  "two-patients.json": twoPatients.body,
};

/** True only when A and B are the same type; `const x: Equal<A, B> = true` does not compile otherwise. */
type Equal<A, B> = (<T>() => T extends A ? 1 : 2) extends <T>() => T extends B ? 1 : 2 ? true : false;

// The exact key set of each object in the body, sorted. The compiler holds each list equal to its type's keys (a key
// missing from a list, or one the type does not have, fails `pnpm typecheck`), and the test holds every fixture's
// objects to the list, so a key Java adds or drops anywhere in the body fails one of the two.
const BANNER_KEYS = ["call_999", "documents", "line_key", "seen", "show"] as const;
const DOCUMENT_KEYS = ["acked", "date", "date_is_upload_day", "date_uncertain", "document_id", "document_type", "patient_id"] as const;
// The ack body is sent back exactly as received (NowBannerSeen), so a key there changes what the client returns.
const SEEN_KEYS = ["document_id", "reasons_digest", "release_state", "urgency"] as const;
const bannerKeysAreTheType: Equal<(typeof BANNER_KEYS)[number], keyof NowBannerJson> = true;
const documentKeysAreTheType: Equal<(typeof DOCUMENT_KEYS)[number], keyof NowBannerDocumentJson> = true;
const seenKeysAreTheType: Equal<(typeof SEEN_KEYS)[number], keyof NowSeenJson> = true;

const LANGS: readonly Lang[] = ["bn", "en"];

describe("API types", () => {
  it("everyNowBannerFixtureIsANowBannerJson", () => {
    expect([bannerKeysAreTheType, documentKeysAreTheType, seenKeysAreTheType]).toEqual([true, true, true]);
    const committed = readdirSync(nowBannerDir).filter((f) => f.endsWith(".json")).sort();
    // Guard: the directory was read and every committed fixture is among the typed imports above.
    expect(committed.length, "fixtures under fixtures/api/now-banner").toBeGreaterThan(0);
    expect(Object.keys(NOW_BANNER).sort()).toEqual(committed);

    const wrong: string[] = [];
    const keysOf = (o: object) => Object.keys(o).sort().join();
    let documentsChecked = 0;
    let seenChecked = 0;
    for (const [file, banner] of Object.entries(NOW_BANNER)) {
      if (keysOf(banner) !== BANNER_KEYS.join()) wrong.push(`${file}: body keys ${keysOf(banner)}`);
      for (const doc of banner.documents) {
        documentsChecked++;
        if (keysOf(doc) !== DOCUMENT_KEYS.join()) wrong.push(`${file}: documents[] keys ${keysOf(doc)}`);
      }
      for (const entry of banner.seen) {
        seenChecked++;
        if (keysOf(entry) !== SEEN_KEYS.join()) wrong.push(`${file}: seen entry keys ${keysOf(entry)}`);
      }
      // One seen entry per document, in the same order: the client sends back what the banner listed.
      const listed = banner.documents.map((d) => d.document_id).join();
      if (banner.seen.map((s) => s.document_id).join() !== listed) wrong.push(`${file}: seen and documents differ`);
    }
    expect(wrong).toEqual([]);
    // Guard over the sweep, not each fixture (a generated body with nothing to show may list nothing): some document
    // and some seen entry were compared, so the empty list above is about real entries.
    expect(documentsChecked, "documents[] entries compared").toBeGreaterThan(0);
    expect(seenChecked, "seen entries compared").toBeGreaterThan(0);
  });

  it("everyNowBannerFixtureNamesASentenceTheCopyHolds", () => {
    // NowBanner falls back to urgency.now for a line_key it cannot read, so that a NOW banner never renders blank. The
    // fallback must never be what the committed contract relies on: every key Java sent resolves directly, in both
    // languages, with no `{0}` left to render raw.
    const wrong: string[] = [];
    for (const [file, banner] of Object.entries(NOW_BANNER)) {
      for (const lang of LANGS) {
        try {
          const line = t(lang, banner.line_key as CopyKey);
          if (/\{\d+\}/.test(line)) wrong.push(`${file} (${lang}): ${banner.line_key} has a placeholder`);
        } catch {
          wrong.push(`${file} (${lang}): no copy for ${banner.line_key}`);
        }
      }
    }
    expect(wrong).toEqual([]);
  });
});

/*
 * The generated contract and the types, held together twice (plan W2d-a):
 *  - at compile time: every committed fixture's body is assigned to `Json<PaperJson>` below, so `pnpm typecheck` fails
 *    here when B2's PaperApiContractTest writes a shape the type does not describe (a renamed key, a banner that
 *    became a string, a result field missing);
 *  - at run time: what a JSON import widens to `string` (a status, a gate, a level, a kind, a copy key) is checked
 *    against the unions and the copy, and the list below against the directory, so a fixture added there cannot go
 *    unchecked.
 */

// Compile time. A result body cannot pass as a reading one: PaperReadingJson declares every result key `never`.
const PAPERS: Record<string, Json<PaperJson>> = {
  "failed.json": failed.body,
  "now-abroad.json": nowAbroad.body,
  "now-bd.json": nowBd.body,
  "processing.json": processing.body,
  "retake-third.json": retakeThird.body,
  "routine-released.json": routineReleased.body,
  "this-week-pending.json": thisWeekPending.body,
  "unassessed.json": unassessed.body,
};
// The one documents case that is not a paper: the {"reason"} refusal (Wave 2 plan, Shared interfaces).
const REFUSALS: Record<string, { reason: string }> = { "not-found.json": notFound.body };
const STATUS: Record<string, number> = {
  "failed.json": failed.status,
  "not-found.json": notFound.status,
  "now-abroad.json": nowAbroad.status,
  "now-bd.json": nowBd.status,
  "processing.json": processing.status,
  "retake-third.json": retakeThird.status,
  "routine-released.json": routineReleased.status,
  "this-week-pending.json": thisWeekPending.status,
  "unassessed.json": unassessed.status,
};

const documentsDir = join(__dirname, "..", "..", "fixtures", "api", "documents");
const onDisk = () => readdirSync(documentsDir).filter((f) => f.endsWith(".json")).sort();

const has = (list: readonly string[], v: unknown) => typeof v === "string" && list.includes(v);

/** Every `<x>_key` string anywhere in a body, with its path. */
function copyKeys(node: unknown, path = ""): Array<{ path: string; key: string }> {
  if (Array.isArray(node)) return node.flatMap((n, i) => copyKeys(n, `${path}[${i}]`));
  if (node === null || typeof node !== "object") return [];
  return Object.entries(node as Record<string, unknown>).flatMap(([k, v]) => [
    ...(k.endsWith("_key") && typeof v === "string" ? [{ path: `${path}.${k}`, key: v }] : []),
    ...copyKeys(v, `${path}.${k}`),
  ]);
}

describe("the /app/api types", () => {
  it("everyDocumentsFixtureIsAPaperJson", () => {
    // Every committed documents fixture is in one of the two typed lists above, and only those.
    expect([...Object.keys(PAPERS), ...Object.keys(REFUSALS)].sort()).toEqual(onDisk());
    expect(Object.keys(STATUS).sort()).toEqual(onDisk());
    // A paper is a 200 body; a refusal is not.
    expect(Object.keys(PAPERS).filter((f) => STATUS[f] !== 200), "papers sent with a status other than 200").toEqual([]);
    expect(Object.keys(REFUSALS).filter((f) => STATUS[f] === 200), "refusals sent as 200").toEqual([]);
    // Guards: both kinds and both paper variants were read.
    expect(Object.keys(REFUSALS).length).toBeGreaterThan(0);
    const statuses = Object.values(PAPERS).map((p) => p.status);
    expect(statuses.some((s) => has(READING_STATUSES, s)), "a reading paper").toBe(true);
    expect(statuses.some((s) => !has(READING_STATUSES, s)), "a finished paper").toBe(true);
  });

  it("everyLiteralAJsonImportWidensIsOneTheTypeNames", () => {
    const wrong: string[] = [];
    const check = (file: string, field: string, list: readonly string[], v: unknown, nullable = false) => {
      if (nullable && v === null) return;
      if (!has(list, v)) wrong.push(`${file} ${field}: ${JSON.stringify(v)}`);
    };
    let levels = 0;
    for (const [file, paper] of Object.entries(PAPERS)) {
      check(file, "status", PAPER_STATUSES, paper.status);
      if (has(READING_STATUSES, paper.status)) continue;
      const p = paper as Record<string, unknown> & { values?: Array<Record<string, unknown>> };
      check(file, "gate", GATES, p.gate);
      check(file, "urgency", URGENCIES, p.urgency, true);
      const banner = p.banner as { level?: unknown } | null;
      if (banner !== null) {
        levels++;
        check(file, "banner.level", BANNER_LEVELS, banner.level);
      }
      for (const v of p.values ?? []) {
        check(file, `values[${String(v.id)}].display_kind`, DISPLAY_KINDS, v.display_kind);
        check(file, `values[${String(v.id)}].side`, SIDES, v.side, true);
      }
    }
    expect(wrong).toEqual([]);
    expect(levels, "papers with a banner").toBeGreaterThan(0);
  });

  it("everyCopyKeyJavaSentResolvesInBothLanguages", () => {
    const keys = Object.entries(PAPERS).flatMap(([file, body]) => copyKeys(body).map((k) => ({ ...k, file })));
    const missing: string[] = [];
    for (const { file, path, key } of keys) {
      for (const lang of ["bn", "en"] as const) {
        try {
          t(lang, key as CopyKey);
        } catch {
          missing.push(`${file} ${path}: ${key} (${lang})`);
        }
      }
    }
    expect(missing).toEqual([]);
    // Guard: the walk found the keys a NOW paper carries, so the empty list above is not vacuous.
    expect(keys.map((k) => k.key)).toEqual(expect.arrayContaining(["urgency.now", "now.notdoctor", "result.counts"]));
  });
});

/*
 * The types are written from the committed fixtures B3's and B6's contract tests generate (plan W2e-b). Each fixture is
 * assigned to its type, which `pnpm typecheck` judges; and because Vitest does not typecheck, every object is also
 * compared key for key with the type's field list here, so a field Java adds, drops or renames fails at test time too.
 */

const fixtures = join(__dirname, "..", "..", "fixtures", "api");

// Each list is held to its type by the compiler, as the banner's are above (a key missing from a list, or one the type
// does not have, fails `pnpm typecheck`); the test then holds every fixture's objects to the list, so a field Java adds,
// drops or renames anywhere in a body fails one of the two. A JSON import is not a fresh literal, so assigning a fixture
// to its type alone cannot see an extra field: only this runtime walk does.
const CALENDAR_KEYS = ["days", "month"] as const;
const DAY_KEYS = ["date", "follow_ups", "papers"] as const;
const PAPER_KEYS = ["chip", "document_id", "document_type"] as const;
const FOLLOW_KEYS = ["crop", "document_id", "instruction"] as const;
const LABS_KEYS = ["bp", "series"] as const;
const SERIES_KEYS = ["canonical_name", "name", "points", "unit"] as const;
const POINT_KEYS = [
  "chip_key",
  "crop",
  "date",
  "date_is_upload_day",
  "date_uncertain",
  "document_id",
  "kind",
  "observation_id",
  "ref_high",
  "ref_low",
  "ref_text",
  "side",
  "value",
] as const;
const PAIR_KEYS = ["date", "date_is_upload_day", "date_uncertain", "diastolic", "document_id", "systolic"] as const;
const keyListsAreTheTypes: [
  Equal<(typeof CALENDAR_KEYS)[number], keyof CalendarJson>,
  Equal<(typeof DAY_KEYS)[number], keyof CalendarDayJson>,
  Equal<(typeof PAPER_KEYS)[number], keyof CalendarPaperJson>,
  Equal<(typeof FOLLOW_KEYS)[number], keyof CalendarFollowUpJson>,
  Equal<(typeof LABS_KEYS)[number], keyof LabsJson>,
  Equal<(typeof SERIES_KEYS)[number], keyof LabSeriesJson>,
  Equal<(typeof POINT_KEYS)[number], keyof TrendPointJson>,
  Equal<(typeof PAIR_KEYS)[number], keyof BpPointJson>,
] = [true, true, true, true, true, true, true, true];

const keys = (o: object) => Object.keys(o).sort();

describe("the API types are the generated fixtures", () => {
  it("everyCalendarFixtureIsACalendarJson", () => {
    expect(keyListsAreTheTypes.every(Boolean)).toBe(true);
    const calendars: Array<[string, CalendarJson]> = [
      ["busy", busy.body],
      ["crowded", crowded.body],
      ["empty", empty.body],
      ["forty-five", fortyFive.body],
      ["september", september.body],
    ];
    // Every committed calendar fixture is assigned above, so a new case cannot go unchecked.
    expect(readdirSync(join(fixtures, "calendar")).filter((f) => f.endsWith(".json")).sort()).toEqual(
      calendars.map(([name]) => `${name}.json`).sort(),
    );
    let papers = 0;
    let follows = 0;
    for (const [name, c] of calendars) {
      expect(keys(c), name).toEqual([...CALENDAR_KEYS]);
      for (const day of c.days) {
        expect(keys(day), `${name} ${day.date}`).toEqual([...DAY_KEYS]);
        for (const p of day.papers) {
          expect(keys(p), `${name} ${day.date} paper`).toEqual([...PAPER_KEYS]);
          papers++;
        }
        for (const f of day.follow_ups) {
          expect(keys(f), `${name} ${day.date} follow-up`).toEqual([...FOLLOW_KEYS]);
          follows++;
        }
      }
    }
    // Guard: the walk met a paper and a follow-up, so the key lists were compared with something.
    expect(papers, "papers walked").toBeGreaterThan(0);
    expect(follows, "follow-ups walked").toBeGreaterThan(0);
  });

  it("everyLabsFixtureIsALabsJson", () => {
    const labs: Array<[string, LabsJson]> = [
      ["labs-some", labsSome.body],
      ["labs-none", labsNone.body],
      ["labs-with-unknown", labsWithUnknown.body],
      ["labs-non-numeric", labsNonNumeric.body],
      ["labs-printed-range", labsPrintedRange.body],
      ["bp", bp.body],
    ];
    // Every committed labs fixture is assigned above, so a case B6 or W9 adds fails here until it is listed.
    expect(
      readdirSync(join(fixtures, "health"))
        .filter((f) => f.startsWith("labs-") || f === "bp.json")
        .sort(),
    ).toEqual(labs.map(([name]) => `${name}.json`).sort());
    const points: TrendPointJson[] = [];
    let pairs = 0;
    for (const [name, l] of labs) {
      expect(keys(l), name).toEqual([...LABS_KEYS]);
      for (const s of l.series) {
        expect(keys(s), `${name} ${s.canonical_name}`).toEqual([...SERIES_KEYS]);
        points.push(...s.points);
      }
      for (const pair of l.bp.points) {
        expect(keys(pair), `${name} bp`).toEqual([...PAIR_KEYS]);
        points.push(pair.systolic, pair.diastolic);
        pairs++;
      }
    }
    for (const p of points) expect(keys(p), p.observation_id).toEqual([...POINT_KEYS]);
    expect(points.length, "points walked").toBeGreaterThan(0);
    expect(pairs, "blood-pressure pairs walked").toBeGreaterThan(0);

    // PO ruling R3: the printed range, both ways, over every generated point. A number comes only with the words it was
    // read from, so a point whose paper printed nothing carries no number: never a default range.
    for (const p of points) {
      const id = p.observation_id;
      expect(p.ref_low === null || typeof p.ref_low === "number", `${id} ref_low`).toBe(true);
      expect(p.ref_high === null || typeof p.ref_high === "number", `${id} ref_high`).toBe(true);
      expect(p.ref_text === null || typeof p.ref_text === "string", `${id} ref_text`).toBe(true);
      if (p.ref_text === null) expect([p.ref_low, p.ref_high], `${id}: nothing printed, no number`).toEqual([null, null]);
      if (p.ref_low !== null && p.ref_high !== null) expect(p.ref_low, `${id}: low below high`).toBeLessThan(p.ref_high);
      expect(bandAgreesWithChip(p), `${id}: a band that contradicts the ${p.kind} chip is never sent`).toBe(true);
    }
  });

  it("theLabsPrintedRangeFixtureHoldsEveryShapeOfAPrintedRange", () => {
    const points = labsPrintedRange.body.series.flatMap((s) => s.points as TrendPointJson[]);
    const at = (n: number) => points.find((p) => p.observation_id === observation(n));
    const shape = (p: TrendPointJson) =>
      p.ref_text === null
        ? "none"
        : p.ref_low !== null && p.ref_high !== null
          ? "two-sided"
          : p.ref_low !== null
            ? "low-only"
            : p.ref_high !== null
              ? "high-only"
              : "text-only";
    // Nothing printed is all three null — asserted on the point itself, not through ref_text (a default that also set
    // ref_text would pass "ref_text null ⇒ numbers null" vacuously).
    expect(at(179)).toMatchObject({ kind: "outside", ref_low: null, ref_high: null, ref_text: null });
    expect(new Set(points.map(shape))).toEqual(new Set(["two-sided", "low-only", "high-only", "none", "text-only"]));
    // The one-sided edges, kept only beside an OUTSIDE beyond them (the threshold path).
    expect(at(180)).toMatchObject({ kind: "outside", side: "high", ref_low: null, ref_high: 200, ref_text: "<200" });
    expect(at(181)).toMatchObject({ kind: "outside", side: "low", ref_low: 40, ref_high: null, ref_text: ">40" });
    // What the stopgap stores beside a one-sided range is UNKNOWN, above, below or within it: words, no number.
    for (const n of [172, 173, 176, 177]) expect(at(n), `${n}`).toMatchObject({ kind: "unknown", ref_low: null, ref_high: null });
    expect(at(174)).toMatchObject({ ref_low: null, ref_high: null, ref_text: "3.9-6.1" });
    // A band contradicting its chip: CRITICAL by a threshold inside 3.5–5.1, and a NULL verdict inside it.
    expect(at(182)).toMatchObject({ kind: "critical", ref_low: null, ref_high: null, ref_text: "3.5–5.1" });
    expect(at(183)).toMatchObject({ kind: "unknown", ref_low: null, ref_high: null, ref_text: "3.5–5.1" });
    // Ranges differ across the points of one series: two labs printed two ranges for creatinine.
    const creatinine = labsPrintedRange.body.series.find((s) => s.canonical_name === "creatinine");
    expect(creatinine?.points.map((p) => p.ref_text)).toEqual(["59–104", "62–106", null]);
  });
});

/*
 * The papers page's reads (plan W8): every committed timeline, follow-ups and attention body assigned to its type and
 * compared key for key, by kind for the attention stack, whose items differ by kind. Each directory is read, so a case
 * Java adds fails here until it is listed.
 */
const TIMELINE_KEYS = ["entries", "next_cursor"] as const;
const ENTRY_KEYS = [
  "chip",
  "counts",
  "date",
  "date_is_upload_day",
  "date_uncertain",
  "document_id",
  "document_type",
  "facility",
  "month",
  "page_one",
  "retake_reason_key",
  "review_pending",
  "status",
] as const;
const COUNT_KEYS = ["follow_ups", "medicines", "outside", "unverified", "values"] as const;
const FOLLOWS_KEYS = ["items"] as const;
const FOLLOW_ITEM_KEYS = ["crop", "document_id", "due_date", "instruction", "overdue"] as const;
const ATTENTION_KEYS: { [K in AttentionJson["items"][number]["kind"]]: readonly string[] } = {
  urgent: ["action_key", "date", "date_is_upload_day", "date_uncertain", "document_id", "document_type", "kind", "level", "received_at"],
  retake: ["action_key", "date", "date_is_upload_day", "document_id", "kind", "received_at", "title_key"],
  follow_up: ["action_key", "crop", "document_id", "due_date", "instruction", "kind", "title_key"],
  reading: ["action_key", "date", "date_is_upload_day", "document_id", "kind", "received_at", "title_key"],
};
const papersKeyListsAreTheTypes: [
  Equal<(typeof TIMELINE_KEYS)[number], keyof TimelineJson>,
  Equal<(typeof ENTRY_KEYS)[number], keyof TimelineEntryJson>,
  Equal<(typeof COUNT_KEYS)[number], keyof TimelineCountsJson>,
  Equal<(typeof FOLLOWS_KEYS)[number], keyof FollowUpsJson>,
  Equal<(typeof FOLLOW_ITEM_KEYS)[number], keyof FollowUpItemJson>,
] = [true, true, true, true, true];
// The attention lists are typed by kind; each is held to its type by the compiler through these.
const URGENT_KEYS: readonly (keyof AttentionUrgentJson)[] = ATTENTION_KEYS.urgent as (keyof AttentionUrgentJson)[];
const RETAKE_KEYS: readonly (keyof AttentionRetakeJson)[] = ATTENTION_KEYS.retake as (keyof AttentionRetakeJson)[];
const FOLLOW_ATTN_KEYS: readonly (keyof AttentionFollowUpJson)[] = ATTENTION_KEYS.follow_up as (keyof AttentionFollowUpJson)[];
const READING_KEYS: readonly (keyof AttentionReadingJson)[] = ATTENTION_KEYS.reading as (keyof AttentionReadingJson)[];

describe("the papers page's reads are the generated fixtures", () => {
  it("everyTimelineFixtureIsATimelineJson", () => {
    expect(papersKeyListsAreTheTypes.every(Boolean)).toBe(true);
    const pages: Array<[string, TimelineJson]> = [
      ["empty", timelineEmpty.body],
      ["first-page", timelineFirst.body],
      ["forty-five-first-page", timelineFortyFive.body],
      ["in-flight", timelineInFlight.body],
      ["lab-only", timelineLab.body],
      ["last-page", timelineLast.body],
      ["rx-none", timelineRxNone.body],
      ["rx-only", timelineRx.body],
    ];
    expect(readdirSync(join(fixtures, "timeline")).filter((f) => f.endsWith(".json")).sort()).toEqual(
      pages.map(([name]) => `${name}.json`).sort(),
    );
    let entries = 0;
    for (const [name, page] of pages) {
      expect(keys(page), name).toEqual([...TIMELINE_KEYS]);
      for (const e of page.entries) {
        expect(keys(e), `${name} ${e.document_id}`).toEqual([...ENTRY_KEYS]);
        expect(keys(e.counts), `${name} ${e.document_id} counts`).toEqual([...COUNT_KEYS]);
        expect(keys(e.page_one), `${name} ${e.document_id} page_one`).toEqual(["src"]);
        // On Java's real output the month is the date's (TimelineJson): MonthList groups by `month` and never re-derives it.
        expect(e.month, `${name} ${e.document_id} month`).toBe(e.date.slice(0, 7));
        entries++;
      }
    }
    expect(entries, "entries walked").toBeGreaterThan(0);
  });

  it("everyFollowUpsFixtureIsAFollowUpsJson", () => {
    const bodies: Array<[string, FollowUpsJson]> = [
      ["none", followsNone.body],
      ["some", followsSome.body],
    ];
    expect(readdirSync(join(fixtures, "follow-ups")).filter((f) => f.endsWith(".json")).sort()).toEqual(
      bodies.map(([name]) => `${name}.json`).sort(),
    );
    let items = 0;
    for (const [name, b] of bodies) {
      expect(keys(b), name).toEqual([...FOLLOWS_KEYS]);
      for (const i of b.items) {
        expect(keys(i), `${name} ${i.due_date}`).toEqual([...FOLLOW_ITEM_KEYS]);
        expect(keys(i.crop), `${name} ${i.due_date} crop`).toEqual(["src"]);
        items++;
      }
    }
    expect(items, "items walked").toBeGreaterThan(0);
  });

  it("everyAttentionFixtureIsAnAttentionJson", () => {
    expect([URGENT_KEYS, RETAKE_KEYS, FOLLOW_ATTN_KEYS, READING_KEYS].every((l) => l.length > 0)).toBe(true);
    const bodies: Array<[string, Json<AttentionJson>]> = [
      ["empty", attentionEmpty.body],
      ["full-stack", attentionFull.body],
      ["now-only", attentionNow.body],
    ];
    expect(readdirSync(join(fixtures, "attention")).filter((f) => f.endsWith(".json")).sort()).toEqual(
      bodies.map(([name]) => `${name}.json`).sort(),
    );
    const kinds = new Set<string>();
    for (const [name, b] of bodies) {
      expect(keys(b), name).toEqual(["items"]);
      for (const item of b.items) {
        const want = ATTENTION_KEYS[item.kind as keyof typeof ATTENTION_KEYS];
        expect(want, `${name}: a kind the type names (${item.kind})`).toBeDefined();
        expect(keys(item), `${name} ${item.kind} ${item.document_id}`).toEqual([...want!].sort());
        kinds.add(item.kind);
      }
    }
    // Guard: every kind the type names was met, so each key list was compared with something.
    expect([...kinds].sort()).toEqual(Object.keys(ATTENTION_KEYS).sort());
  });
});

/*
 * GET /app/api/me (plan W4): every committed body is assigned to MeJson (the compile-time half) and compared key for key
 * with the lists below (the run-time half, which sees a field Java adds), and the directory is read so a new case cannot
 * go unchecked. The one refusal is the {"reason"} of a 401.
 */
const ME_KEYS = ["active_patient_id", "family", "patients", "preview"] as const;
const ME_FAMILY_KEYS = ["id", "owner_name"] as const;
const ME_PATIENT_KEYS = [
  "age",
  "chronic_flags_set",
  "documents",
  "id",
  "last_document_at",
  "name",
  "name_objective",
  "name_possessive",
  "owner",
  "sex",
  "urgent",
] as const;
const meKeyListsAreTheTypes: [
  Equal<(typeof ME_KEYS)[number], keyof MeJson>,
  Equal<(typeof ME_FAMILY_KEYS)[number], keyof MeJson["family"]>,
  Equal<(typeof ME_PATIENT_KEYS)[number], keyof MePatient>,
] = [true, true, true];

describe("GET /app/api/me", () => {
  it("everyMeFixtureIsAMeJson", () => {
    expect(meKeyListsAreTheTypes.every(Boolean)).toBe(true);
    const bodies: Array<[string, MeJson, number]> = [
      ["flags-set.json", meFlagsSet.body, meFlagsSet.status],
      ["long-name.json", meLongName.body, meLongName.status],
      ["no-patients.json", meNoPatients.body, meNoPatients.status],
      ["one-patient.json", meOnePatient.body, meOnePatient.status],
      ["preview-off.json", mePreviewOff.body, mePreviewOff.status],
      ["preview-on.json", mePreviewOn.body, mePreviewOn.status],
      ["two-owned.json", meTwoOwned.body, meTwoOwned.status],
      ["two-owned-long.json", meTwoOwnedLong.body, meTwoOwnedLong.status],
      ["two-patients.json", meTwoPatients.body, meTwoPatients.status],
    ];
    const refusals: Array<[string, { reason: string }, number]> = [
      ["unauthenticated.json", meUnauthenticated.body, meUnauthenticated.status],
    ];
    expect(readdirSync(join(fixtures, "me")).filter((f) => f.endsWith(".json")).sort()).toEqual(
      [...bodies, ...refusals].map(([file]) => file).sort(),
    );
    expect(refusals.map(([file, body, status]) => [file, body.reason, status])).toEqual([["unauthenticated.json", "UNAUTHENTICATED", 401]]);
    let patients = 0;
    for (const [file, me, status] of bodies) {
      expect(status, file).toBe(200);
      expect(keys(me), file).toEqual([...ME_KEYS]);
      expect(keys(me.family), `${file} family`).toEqual([...ME_FAMILY_KEYS]);
      for (const p of me.patients) {
        expect(keys(p), `${file} ${p.id}`).toEqual([...ME_PATIENT_KEYS]);
        expect(typeof p.urgent, `${file} ${p.id} urgent`).toBe("boolean");
        expect(typeof p.chronic_flags_set, `${file} ${p.id} chronic_flags_set`).toBe("boolean");
        patients++;
      }
      // The remembered patient is one of the list or nobody: Java never names a patient the family cannot see.
      if (me.active_patient_id !== null) expect(me.patients.map((p) => p.id), file).toContain(me.active_patient_id);
    }
    // Guards: patients were walked, and the cases the shell branches on are all here.
    expect(patients, "patients walked").toBeGreaterThan(0);
    expect(bodies.some(([, me]) => me.patients.length === 0), "a family with no patient").toBe(true);
    expect(bodies.some(([, me]) => me.patients.length > 1 && me.active_patient_id === null), "two and no choice").toBe(true);
    expect(bodies.some(([, me]) => me.preview === false), "a family outside the preview").toBe(true);
    expect(bodies.some(([, me]) => me.patients.some((p) => p.urgent)), "an urgent patient").toBe(true);
    // The papers page's chronic-flags ask is judged both ways: a patient with none stored, and one with a flag.
    expect(bodies.some(([, me]) => me.patients.some((p) => !p.chronic_flags_set)), "a patient with no flag").toBe(true);
    expect(bodies.some(([, me]) => me.patients.some((p) => p.chronic_flags_set)), "a patient with a flag").toBe(true);
  });
});

/** The id HealthApiContractTest gives observation n of a labs case. */
function observation(n: number): string {
  return `b6b6b6b6-0000-4000-8000-000000000${n}`;
}

/**
 * R3's contract, judged from the fixture alone: Java sends a number only where the picture agrees with the point's chip
 * (PaperDisplay.drawnBand). Never beside an UNKNOWN; two numbers in the band for INSIDE, beyond it on the side for
 * OUTSIDE/CRITICAL; one number only beside an OUTSIDE/CRITICAL beyond that bound on its own side.
 */
function bandAgreesWithChip(p: TrendPointJson): boolean {
  const { ref_low: low, ref_high: high } = p;
  if (low === null && high === null) return true;
  const v = Number(p.value);
  if (p.kind === "unknown" || !Number.isFinite(v)) return false;
  if (low !== null && high !== null) {
    if (p.kind === "inside") return v >= low && v <= high;
    return p.side === "high" ? v > high : p.side === "low" ? v < low : false;
  }
  if (p.kind !== "outside" && p.kind !== "critical") return false;
  return high !== null ? p.side === "high" && v > high : p.side === "low" && low !== null && v < low;
}

// ---- GET /app/api/upload-options, POST /app/api/uploads (plan W7) -------------------------------------------------

const OPTIONS_KEYS = ["allowance", "can_send", "chosen_patient_id", "limits", "preselected_patient_id", "view_only"] as const;
const ALLOWANCE_KEYS = ["limited", "max", "remaining", "resets_on"] as const;
const LIMITS_KEYS = [
  "downscale_max_edge",
  "downscale_quality",
  "max_file_bytes",
  "max_files",
  "max_pages",
  "max_request_bytes",
  "pass_through_bytes",
] as const;
const UPLOAD_PATIENT_KEYS = ["age", "documents", "id", "last_document_at", "name", "name_objective", "name_possessive", "owner", "sex"] as const;
const ANSWER_KEYS = ["documents", "next"] as const;
const uploadKeyListsAreTheTypes: [
  Equal<(typeof OPTIONS_KEYS)[number], keyof UploadOptionsJson>,
  Equal<(typeof ALLOWANCE_KEYS)[number], keyof UploadOptionsJson["allowance"]>,
  Equal<(typeof LIMITS_KEYS)[number], keyof UploadOptionsJson["limits"]>,
  Equal<(typeof UPLOAD_PATIENT_KEYS)[number], keyof UploadPatientJson>,
  Equal<(typeof ANSWER_KEYS)[number], keyof UploadAnswerJson>,
] = [true, true, true, true, true];

describe("the add sheet's JSON", () => {
  it("everyUploadFixtureIsItsType", () => {
    expect(uploadKeyListsAreTheTypes.every(Boolean)).toBe(true);
    // Each assignment is the type check: a fixture that is not an UploadOptionsJson does not compile.
    const options: Array<[string, UploadOptionsJson, number]> = [
      ["cap-reached.json", uploadCapReached.body, uploadCapReached.status],
      ["downscale-on.json", uploadDownscaleOn.body, uploadDownscaleOn.status],
      ["long-name.json", uploadLongName.body, uploadLongName.status],
      ["owner-one.json", uploadOwnerOne.body, uploadOwnerOne.status],
      ["owner-two-asked.json", uploadOwnerTwoAsked.body, uploadOwnerTwoAsked.status],
      ["owner-two.json", uploadOwnerTwo.body, uploadOwnerTwo.status],
      ["view-only.json", uploadViewOnly.body, uploadViewOnly.status],
    ];
    expect(readdirSync(join(fixtures, "upload-options")).filter((f) => f.endsWith(".json")).sort()).toEqual(options.map(([f]) => f));
    let patients = 0;
    for (const [file, o, status] of options) {
      expect(status, file).toBe(200);
      expect(keys(o), file).toEqual([...OPTIONS_KEYS]);
      expect(keys(o.allowance), `${file} allowance`).toEqual([...ALLOWANCE_KEYS]);
      expect(keys(o.limits), `${file} limits`).toEqual([...LIMITS_KEYS]);
      for (const p of [...o.can_send, ...o.view_only]) {
        expect(keys(p), `${file} ${p.id}`).toEqual([...UPLOAD_PATIENT_KEYS]);
        patients++;
      }
      // Java preselects only the one patient there is to send for; never one of several, never one only to see.
      if (o.preselected_patient_id !== null) expect(o.can_send.map((p) => p.id), file).toEqual([o.preselected_patient_id]);
      // Java chooses only a patient the viewer may send for, and the one preselect whenever there is one.
      if (o.chosen_patient_id !== null) expect(o.can_send.map((p) => p.id), file).toContain(o.chosen_patient_id);
      if (o.preselected_patient_id !== null) expect(o.chosen_patient_id, file).toBe(o.preselected_patient_id);
    }
    expect(patients, "patients walked").toBeGreaterThan(0);
    // Guards: the cases the sheet branches on are all here.
    expect(options.some(([, o]) => o.can_send.length > 1 && o.preselected_patient_id === null), "two and none chosen").toBe(true);
    expect(options.some(([, o]) => o.view_only.length > 0), "a patient only to see").toBe(true);
    expect(options.some(([, o]) => o.preselected_patient_id === null && o.chosen_patient_id !== null), "two, one asked for").toBe(true);
    expect(options.some(([, o]) => o.allowance.limited && o.allowance.remaining === 0), "the cap reached").toBe(true);

    const answers: Array<[string, UploadAnswerJson]> = [
      ["one-document.json", uploadedOne.body],
      ["separate.json", uploadedSeparate.body],
    ];
    for (const [file, a] of answers) {
      expect(keys(a), file).toEqual([...ANSWER_KEYS]);
      for (const d of a.documents) expect(keys(d), file).toEqual(["id"]);
    }
    expect(answers.map(([, a]) => a.documents.length)).toEqual([1, 3]);
  });
});

/*
 * GET /app/api/patients/{id}/thread (W5-api) and /attention (B3), read by the chat page (plan W5): every committed body
 * is assigned to its type (the compile-time half) and walked here (the run-time half): each item's keys are its kind's,
 * each kind one the type names, and a thread's paper card is a paper body. The directory is read, so a case Java adds
 * cannot go unchecked.
 */
const THREADS: Record<string, Json<ThreadJson>> = {
  "default.json": threadDefault.body,
  "failed.json": threadFailed.body,
  "first-run.json": threadFirstRun.body,
  "now-abroad.json": threadNowAbroad.body,
  "now.json": threadNow.body,
  "retake.json": threadRetake.body,
  "second-page.json": threadSecondPage.body,
  "share-member.json": threadShareMember.body,
};
const ATTENTION: Record<string, Json<AttentionJson>> = {
  "empty.json": attentionEmpty.body,
  "full-stack.json": attentionFull.body,
  "now-only.json": attentionNow.body,
};
const THREAD_MESSAGE_KEYS = ["at", "body", "document_id", "kind", "message_kind"];
const THREAD_PAPER_KEYS = ["at", "card", "kind"];
// ATTENTION_KEYS is the papers page's, above: one key list per kind, typed against AttentionJson.

describe("the chat page's bodies", () => {
  it("everyThreadFixtureIsAThreadJson", () => {
    expect(readdirSync(join(fixtures, "thread")).filter((f) => f.endsWith(".json")).sort()).toEqual(Object.keys(THREADS).sort());
    const kinds = new Set<string>();
    let papers = 0;
    for (const [file, thread] of Object.entries(THREADS)) {
      expect(keys(thread), file).toEqual(["items", "next_cursor"]);
      for (const item of thread.items) {
        if (item.kind === "message") {
          expect(keys(item), file).toEqual(THREAD_MESSAGE_KEYS);
          const kind = (item as { message_kind: string }).message_kind;
          expect(MESSAGE_KINDS as readonly string[], `${file} message_kind`).toContain(kind);
          kinds.add(kind);
        } else {
          expect(item.kind, file).toBe("paper");
          expect(keys(item), file).toEqual(THREAD_PAPER_KEYS);
          const card = (item as { card: { status: string } }).card;
          expect(PAPER_STATUSES as readonly string[], `${file} card.status`).toContain(card.status);
          papers++;
        }
      }
    }
    // Guards: papers were walked, and the failure notice (V23) is among the kinds the fixtures carry.
    expect(papers).toBeGreaterThan(0);
    expect([...kinds]).toEqual(expect.arrayContaining(["failure", "nudge", "detail", "retake"]));
  });

  it("everyAttentionFixtureIsAnAttentionJson", () => {
    expect(readdirSync(join(fixtures, "attention")).filter((f) => f.endsWith(".json")).sort()).toEqual(Object.keys(ATTENTION).sort());
    const seen = new Set<string>();
    for (const [file, stack] of Object.entries(ATTENTION)) {
      expect(keys(stack), file).toEqual(["items"]);
      for (const item of stack.items) {
        expect(ATTENTION_KINDS as readonly string[], `${file} kind`).toContain(item.kind);
        expect(keys(item), `${file} ${item.kind}`).toEqual(ATTENTION_KEYS[item.kind as keyof typeof ATTENTION_KEYS]);
        if (item.kind === "urgent") expect(BANNER_LEVELS as readonly string[]).toContain((item as { level: string }).level);
        seen.add(item.kind);
      }
    }
    expect([...seen].sort(), "every kind was walked").toEqual([...ATTENTION_KINDS].sort());
  });
});

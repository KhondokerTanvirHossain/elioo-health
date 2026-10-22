import { readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { t, type CopyKey, type Lang } from "@/copy";
import failed from "../../fixtures/api/documents/failed.json";
import failedSafety from "../../fixtures/api/documents/failed-safety.json";
import fiftyEightValues from "../../fixtures/api/documents/fifty-eight-values-multipage.json";
import notFound from "../../fixtures/api/documents/not-found.json";
import nowAbroad from "../../fixtures/api/documents/now-abroad.json";
import nowBd from "../../fixtures/api/documents/now-bd.json";
import processing from "../../fixtures/api/documents/processing.json";
import retakeThird from "../../fixtures/api/documents/retake-third.json";
import routineReleased from "../../fixtures/api/documents/routine-released.json";
import thisWeekPending from "../../fixtures/api/documents/this-week-pending.json";
import unassessed from "../../fixtures/api/documents/unassessed.json";
import withheldRejected from "../../fixtures/api/documents/withheld-rejected.json";
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
import bp from "../../fixtures/api/health/bp.json";
import labsNonNumeric from "../../fixtures/api/health/labs-non-numeric.json";
import labsNone from "../../fixtures/api/health/labs-none.json";
import labsSome from "../../fixtures/api/health/labs-some.json";
import labsWithUnknown from "../../fixtures/api/health/labs-with-unknown.json";
// B1's generated GET /app/api/me bodies (baymax MeApiContractTest), plan W4: the shell reads every field it renders here.
import meLongName from "../../fixtures/api/me/long-name.json";
import meNoPatients from "../../fixtures/api/me/no-patients.json";
import meOnePatient from "../../fixtures/api/me/one-patient.json";
import mePreviewOff from "../../fixtures/api/me/preview-off.json";
import mePreviewOn from "../../fixtures/api/me/preview-on.json";
import meTwoPatients from "../../fixtures/api/me/two-patients.json";
import meUnauthenticated from "../../fixtures/api/me/unauthenticated.json";
// W6-api's generated value sheets (baymax ValueSheetApiContractTest), plan W6.
import sheetEarlierNone from "../../fixtures/api/value-sheet/earlier-none.json";
import sheetEarlierOtherUnit from "../../fixtures/api/value-sheet/earlier-other-unit.json";
import sheetEarlierThree from "../../fixtures/api/value-sheet/earlier-three.json";
import sheetInside from "../../fixtures/api/value-sheet/inside.json";
import sheetNoHistory from "../../fixtures/api/value-sheet/no-history.json";
import sheetNotFound from "../../fixtures/api/value-sheet/not-found.json";
import sheetOutsideHigh from "../../fixtures/api/value-sheet/outside-high.json";
import sheetUnknownOneSided from "../../fixtures/api/value-sheet/unknown-one-sided.json";
import {
  BANNER_LEVELS,
  DISPLAY_KINDS,
  EARLIER_LINE_KEYS,
  GATES,
  PAPER_STATUSES,
  READING_STATUSES,
  SIDES,
  URGENCIES,
  type BpPointJson,
  type CalendarDayJson,
  type CalendarFollowUpJson,
  type CalendarJson,
  type CalendarPaperJson,
  type EarlierReadingJson,
  type Json,
  type LabSeriesJson,
  type LabsJson,
  type MeJson,
  type MePatient,
  type NowBannerDocumentJson,
  type NowBannerJson,
  type NowSeenJson,
  type PaperJson,
  type PaperValueJson,
  type TrendPointJson,
  type ValueSheetJson,
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
  "failed-safety.json": failedSafety.body,
  "fifty-eight-values-multipage.json": fiftyEightValues.body,
  "withheld-rejected.json": withheldRejected.body,
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
  "failed-safety.json": failedSafety.status,
  "fifty-eight-values-multipage.json": fiftyEightValues.status,
  "withheld-rejected.json": withheldRejected.status,
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
      ["long-name.json", meLongName.body, meLongName.status],
      ["no-patients.json", meNoPatients.body, meNoPatients.status],
      ["one-patient.json", meOnePatient.body, meOnePatient.status],
      ["preview-off.json", mePreviewOff.body, mePreviewOff.status],
      ["preview-on.json", mePreviewOn.body, mePreviewOn.status],
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
  });
});

/*
 * GET /app/api/documents/{id}/values/{obs} (W6-api, plan W6): every committed sheet is assigned to ValueSheetJson (the
 * compile-time half) and compared key for key with the lists below (the run-time half), the value row with the paper's
 * own row keys, and the heading key with the keys the type names, so a key Java adds or a new heading fails here.
 */
const SHEET_KEYS = ["earlier", "earlier_line_key", "paper", "value"] as const;
const SHEET_PAPER_KEYS = ["date", "date_is_upload_day", "date_uncertain", "document_type", "id", "patient"] as const;
const SHEET_PATIENT_KEYS = ["id", "name", "name_possessive"] as const;
const SHEET_VALUE_KEYS = [
  "chip_key",
  "crop",
  "display_kind",
  "id",
  "in_now_block",
  "name",
  "pin",
  "ref_high",
  "ref_low",
  "side",
  "unit",
  "value",
] as const;
const EARLIER_KEYS = ["crop", "date", "date_is_upload_day", "document_id", "observation_id", "unit", "value"] as const;
const sheetKeyListsAreTheTypes: [
  Equal<(typeof SHEET_KEYS)[number], keyof ValueSheetJson>,
  Equal<(typeof SHEET_PAPER_KEYS)[number], keyof ValueSheetJson["paper"]>,
  Equal<(typeof SHEET_PATIENT_KEYS)[number], keyof ValueSheetJson["paper"]["patient"]>,
  Equal<(typeof SHEET_VALUE_KEYS)[number], keyof PaperValueJson>,
  Equal<(typeof EARLIER_KEYS)[number], keyof EarlierReadingJson>,
] = [true, true, true, true, true];

describe("GET /app/api/documents/{id}/values/{obs}", () => {
  it("everyValueSheetFixtureIsAValueSheetJson", () => {
    expect(sheetKeyListsAreTheTypes.every(Boolean)).toBe(true);
    const sheets: Array<[string, Json<ValueSheetJson>]> = [
      ["earlier-none.json", sheetEarlierNone.body],
      ["earlier-other-unit.json", sheetEarlierOtherUnit.body],
      ["earlier-three.json", sheetEarlierThree.body],
      ["inside.json", sheetInside.body],
      ["no-history.json", sheetNoHistory.body],
      ["outside-high.json", sheetOutsideHigh.body],
      ["unknown-one-sided.json", sheetUnknownOneSided.body],
    ];
    const refusals: Array<[string, { reason: string }, number]> = [["not-found.json", sheetNotFound.body, sheetNotFound.status]];
    expect(readdirSync(join(fixtures, "value-sheet")).filter((f) => f.endsWith(".json")).sort()).toEqual(
      [...sheets.map(([f]) => f), ...refusals.map(([f]) => f)].sort(),
    );
    expect(refusals.map(([, body, status]) => [body.reason, status])).toEqual([["NOT_FOUND", 404]]);
    const lineKeys = new Set<string | null>();
    let earlier = 0;
    for (const [file, sheet] of sheets) {
      expect(keys(sheet), file).toEqual([...SHEET_KEYS]);
      expect(keys(sheet.paper), `${file} paper`).toEqual([...SHEET_PAPER_KEYS]);
      expect(keys(sheet.paper.patient), `${file} patient`).toEqual([...SHEET_PATIENT_KEYS]);
      expect(keys(sheet.value), `${file} value`).toEqual([...SHEET_VALUE_KEYS]);
      for (const e of sheet.earlier) {
        expect(keys(e), `${file} earlier`).toEqual([...EARLIER_KEYS]);
        earlier++;
      }
      const key = sheet.earlier_line_key;
      expect(key === null || (EARLIER_LINE_KEYS as readonly string[]).includes(key), `${file} earlier_line_key ${key}`).toBe(true);
      lineKeys.add(key);
    }
    // Guard: the fixtures hold a sheet with no line, one with "the first time" and one with readings, and readings.
    expect([...lineKeys]).toEqual(expect.arrayContaining([null, "sheet.earlier.none", "sheet.earlier"]));
    expect(earlier, "earlier readings walked").toBeGreaterThan(0);
  });
});

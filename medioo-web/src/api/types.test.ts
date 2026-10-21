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
import {
  BANNER_LEVELS,
  DISPLAY_KINDS,
  GATES,
  PAPER_STATUSES,
  READING_STATUSES,
  SIDES,
  URGENCIES,
  type Json,
  type NowBannerDocumentJson,
  type NowBannerJson,
  type NowSeenJson,
  type PaperJson,
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

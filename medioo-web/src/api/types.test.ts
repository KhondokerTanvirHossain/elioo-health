import { readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { t, type CopyKey, type Lang } from "@/copy";
// B3's generated GET /app/api/now-banner bodies (baymax NowBannerApiContractTest), imported as JSON so the assignments
// below are checked by `pnpm typecheck`: a field the type names that a fixture drops or retypes does not compile. A
// field Java ADDS compiles silently (an imported value gets no excess-property check), so the key sets are pinned at
// runtime below, against lists the compiler holds equal to the types' keys.
import abroad from "../../fixtures/api/now-banner/abroad.json";
import acked from "../../fixtures/api/now-banner/acked.json";
import one from "../../fixtures/api/now-banner/one.json";
import twoPatients from "../../fixtures/api/now-banner/two-patients.json";
import type { NowBannerDocumentJson, NowBannerJson, NowSeenJson } from "./types";

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

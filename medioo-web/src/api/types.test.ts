import { readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
// B3's generated GET /app/api/now-banner bodies (baymax NowBannerApiContractTest), imported as JSON so the assignments
// below are checked by `pnpm typecheck`: the moment the generated contract and the type drift apart, it fails.
import abroad from "../../fixtures/api/now-banner/abroad.json";
import acked from "../../fixtures/api/now-banner/acked.json";
import one from "../../fixtures/api/now-banner/one.json";
import twoPatients from "../../fixtures/api/now-banner/two-patients.json";
import type { NowBannerJson, NowSeenJson } from "./types";

const nowBannerDir = join(__dirname, "..", "..", "fixtures/api/now-banner");

// Each assignment is the type check: a fixture that is not a NowBannerJson does not compile.
const NOW_BANNER: Record<string, NowBannerJson> = {
  "abroad.json": abroad.body,
  "acked.json": acked.body,
  "one.json": one.body,
  "two-patients.json": twoPatients.body,
};

// The ack body is sent back exactly as received (NowBannerSeen), so its entries' shape is pinned field by field: a key
// Java adds or drops there changes what the client returns, and must be looked at here.
const SEEN_KEYS: Array<keyof NowSeenJson> = ["document_id", "reasons_digest", "release_state", "urgency"];

describe("API types", () => {
  it("everyNowBannerFixtureIsANowBannerJson", () => {
    const committed = readdirSync(nowBannerDir).filter((f) => f.endsWith(".json")).sort();
    // Guard: the directory was read and every committed fixture is among the typed imports above.
    expect(committed.length, "fixtures under fixtures/api/now-banner").toBeGreaterThan(0);
    expect(Object.keys(NOW_BANNER).sort()).toEqual(committed);

    const wrong: string[] = [];
    for (const [file, banner] of Object.entries(NOW_BANNER)) {
      if (banner.seen.length === 0) wrong.push(`${file}: an empty seen list`);
      for (const entry of banner.seen) {
        const keys = Object.keys(entry).sort();
        if (keys.join() !== SEEN_KEYS.join()) wrong.push(`${file}: seen entry keys ${keys.join()}`);
      }
      // One seen entry per document, in the same order: the client sends back what the banner listed.
      const listed = banner.documents.map((d) => d.document_id).join();
      if (banner.seen.map((s) => s.document_id).join() !== listed) wrong.push(`${file}: seen and documents differ`);
    }
    expect(wrong).toEqual([]);
  });
});

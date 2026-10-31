import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import type { ThreadItemJson, ThreadJson } from "@/api/types";
import { mergeAsks } from "./merge";

const thread = (file: string) =>
  (JSON.parse(readFileSync(join(__dirname, "..", "..", "..", "fixtures", "api", "thread", file), "utf8")) as { body: ThreadJson }).body;

const WITH_ASKS = thread("with-asks.json");
const plainOf = (items: readonly ThreadItemJson[]) => items.filter((i) => i.kind !== "ask");
const asksOf = (items: readonly ThreadItemJson[]) => items.filter((i) => i.kind === "ask");

describe("mergeAsks", () => {
  it("javasOwnPageIsRebuiltInJavasOrder", () => {
    // The two reads of the same page, merged, are the page Java sent with asks=1: same items, same order.
    expect(asksOf(WITH_ASKS.items).length, "with-asks holds asks").toBeGreaterThan(0);
    expect(mergeAsks(plainOf(WITH_ASKS.items), WITH_ASKS.items)).toEqual(WITH_ASKS.items);
  });

  it("asksNeverPushAPaperOffThePage", () => {
    // Review of PR 166, finding 7: twenty newer asks fill the asks read's whole page; the paper is still drawn, from the
    // read without asks.
    const ask = asksOf(WITH_ASKS.items)[0]!;
    const many = Array.from({ length: 20 }, (_, k) => ({
      ...ask,
      turn_id: `turn-${k}`,
      at: new Date(Date.parse("2026-10-20T00:00:00Z") + k * 60_000).toISOString(),
    }));
    const page = [...many].reverse();
    const merged = mergeAsks(plainOf(WITH_ASKS.items), page);
    expect(merged.filter((i) => i.kind === "paper").length, "the paper card").toBe(plainOf(WITH_ASKS.items).filter((i) => i.kind === "paper").length);
    expect(merged.filter((i) => i.kind === "ask").length).toBe(20);
    expect(merged[merged.length - 1]!.kind, "the oldest, last").not.toBe("ask");
  });

  it("onATieAPaperComesBeforeAMessageBeforeAnAsk", () => {
    const at = "2026-10-12T04:00:00Z";
    const [paper] = WITH_ASKS.items.filter((i) => i.kind === "paper");
    const ask = { ...asksOf(WITH_ASKS.items)[0]!, at };
    const message: ThreadItemJson = { kind: "message", at, message_kind: "nudge", document_id: null, body: "b" };
    expect(mergeAsks([message, { ...paper!, at }], [ask]).map((i) => i.kind)).toEqual(["paper", "message", "ask"]);
  });
});

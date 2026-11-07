import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import type { ThreadJson } from "@/api/types";
import { webText } from "./web-text";

const threads = join(__dirname, "..", "..", "..", "fixtures", "api", "thread");

describe("webText", () => {
  it("isJavasWebBodyForEveryMessageJavaWrote", () => {
    // Derived from Java's output, not from examples typed here: every message of every thread fixture, `body` in,
    // Java's `web_body` out. Guarded against emptiness, and against a fixture set where the rule changes nothing.
    const messages = readdirSync(threads)
      .filter((f) => f.endsWith(".json"))
      .flatMap((f) => (JSON.parse(readFileSync(join(threads, f), "utf8")) as { body: ThreadJson }).body.items)
      .flatMap((i) => (i.kind === "message" ? [i] : []));
    expect(messages.length).toBeGreaterThanOrEqual(5);
    expect(messages.filter((m) => m.body !== m.web_body).length, "some message had a link line").toBeGreaterThan(0);
    for (const m of messages) expect(webText(m.body), m.body).toBe(m.web_body);
  });

  it("keepsEveryOtherLineAsWritten", () => {
    expect(webText("a\n\nhttps://x/y · b\nc\n")).toBe("a\n\nc\n");
    expect(webText("http://x")).toBe("");
    expect(webText("no link")).toBe("no link");
  });
});

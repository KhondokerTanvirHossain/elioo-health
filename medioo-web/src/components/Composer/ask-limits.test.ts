import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { ASK_TIMEOUT_MS, QUESTION_MAX_CHARS } from "./ask-limits";

/*
 * Review of PR 166, findings 4 and 11: the composer's two numbers against the ones Java ships
 * (medscribe-ai/src/main/resources/application.properties), read from that file, so a change on one side alone fails here.
 * The answer deadline is counted from the request; Java adds its store's grace (ChatAnswerService.STORE_GRACE, 3 s) and
 * the network may add a little more (ChatPropertiesCheck.NETWORK_MARGIN, 2 s): the web waits for both.
 */
const SHIPPED = readFileSync(join(__dirname, "..", "..", "..", "..", "medscribe-ai", "src", "main", "resources", "application.properties"), "utf8");
const STORE_GRACE_MS = 3_000;
const NETWORK_MARGIN_MS = 2_000;

function shipped(key: string): string {
  const line = SHIPPED.split("\n").find((l) => l.startsWith(`${key}=`));
  expect(line, key).toBeDefined();
  return line!.slice(key.length + 1).trim();
}

describe("the composer's limits", () => {
  it("theWebWaitsPastJavasWholeAnswer", () => {
    const deadline = /^PT(\d+)S$/.exec(shipped("baymax.chat.answer-deadline"));
    expect(deadline, "the deadline is written PT<n>S").not.toBeNull();
    const deadlineMs = Number(deadline![1]) * 1000;
    expect(deadlineMs, "the shipped deadline").toBe(45_000);
    expect(ASK_TIMEOUT_MS - deadlineMs, "the web's margin past Java's answer deadline").toBeGreaterThanOrEqual(STORE_GRACE_MS + NETWORK_MARGIN_MS);
  });

  it("theLengthTheComposerNamesIsJavas", () => {
    expect(String(QUESTION_MAX_CHARS)).toBe(shipped("baymax.chat.question-max-chars"));
  });
});

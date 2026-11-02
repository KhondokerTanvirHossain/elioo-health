import { act, cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MAX_CHECKS, STEADY_CHECKS, waitBefore } from "@/api/reading-check";
import { BANNER_ONLY_COOKIE, clearBannerOnly, holdOnBannerRefresh, markBannerOnly } from "@/components/safety/NowBanner/banner-only";
import { ReadingProgress, type ReadingCheck } from "./ReadingProgress";

const apiGet = vi.fn();
vi.mock("@/api/browser", () => ({ apiGet: (...args: unknown[]) => apiGet(...args) }));
const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));

const DOC = "5e9edf6d-0a2a-4913-8675-9d07a4100003";
const AGAIN = `/app/preview/documents/${DOC}`;
const CHECK: ReadingCheck = {
  documentId: DOC,
  again: AGAIN,
  words: { settled: { DONE: "Read", NEEDS_RETAKE: "Retake the photo", FAILED: "Could not be read" }, again: "See how far it is" },
};
const status = (s: string) => ({ kind: "ok", status: 200, body: { status: s } });

let hidden = false;
beforeEach(() => {
  vi.useFakeTimers();
  hidden = false;
  Object.defineProperty(document, "visibilityState", { configurable: true, get: () => (hidden ? "hidden" : "visible") });
  apiGet.mockReset().mockResolvedValue(status("PROCESSING"));
  refresh.mockReset();
});
afterEach(() => {
  cleanup();
  clearBannerOnly();
  vi.useRealTimers();
});

/** Lets the clock run `ms` and every answer that came due resolve. */
const pass = (ms: number) => act(() => vi.advanceTimersByTimeAsync(ms));
const setHidden = (value: boolean) =>
  act(() => {
    hidden = value;
    document.dispatchEvent(new Event("visibilitychange"));
  });
const live = (el: HTMLElement) => el.querySelector("[role='status']");

// The paper page's reading check follows the one reading of PO ruling P6 (src/api/reading-check.ts), as the papers
// page's QuietCheck does: Java's 5 s for the first checks, then backing off, a cap with a plain link, no checks while
// the tab is hidden, and each settle said once in a polite live region.
describe("ReadingProgress (PO ruling P6, reading-check.ts)", () => {
  it("asksTheStatusAtJavasPaceThenBacksOff", async () => {
    render(<ReadingProgress check={CHECK} />);
    expect(apiGet, "nothing before the first wait").not.toHaveBeenCalled();
    for (let i = 0; i < STEADY_CHECKS; i++) await pass(waitBefore(i));
    expect(apiGet).toHaveBeenCalledTimes(STEADY_CHECKS);
    // The next wait is longer than Java's 5 s: one 5 s more asks nothing, the rest of the backed-off wait asks once.
    expect(waitBefore(STEADY_CHECKS)).toBeGreaterThan(waitBefore(0));
    await pass(waitBefore(0));
    expect(apiGet, "backed off: no check at 5 s").toHaveBeenCalledTimes(STEADY_CHECKS);
    await pass(waitBefore(STEADY_CHECKS) - waitBefore(0));
    expect(apiGet).toHaveBeenCalledTimes(STEADY_CHECKS + 1);
    // Every request is the status, which records nothing, never the paper itself (plan Review Focus 4).
    expect(new Set(apiGet.mock.calls.map((c) => c[0]))).toEqual(new Set([`/app/api/documents/${DOC}/status`]));
    expect(refresh).not.toHaveBeenCalled();
  });

  it("aPaperReadIsRenderedOnceSaidOnceAndTheChecksStop", async () => {
    const { container, rerender } = render(<ReadingProgress check={CHECK} />);
    await pass(waitBefore(0));
    expect(live(container)?.textContent, "a check while reading says nothing").toBe("");
    apiGet.mockResolvedValue(status("DONE"));
    await pass(waitBefore(1));
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(live(container)?.textContent).toBe("Read");
    // The refresh landed: the page is the result now, and passes no check. The region stays, still saying it once.
    rerender(<ReadingProgress check={null} />);
    expect(live(container)?.textContent, "the region outlives the reading page").toBe("Read");
    await pass(10 * 60_000);
    expect(apiGet).toHaveBeenCalledTimes(2);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("aResultPageWithNothingSaidDrawsNothing", () => {
    const { container } = render(<ReadingProgress check={null} />);
    expect(container.innerHTML).toBe("");
  });

  it("aPaperGoneMeanwhileIsRefreshedOnceAndSaysNothing", async () => {
    apiGet.mockResolvedValue({ kind: "refused", status: 404, reason: "NOT_FOUND" });
    const { container } = render(<ReadingProgress check={CHECK} />);
    await pass(waitBefore(0));
    await pass(waitBefore(1));
    expect(apiGet).toHaveBeenCalledTimes(1);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(live(container)?.textContent).toBe("");
  });

  it("aSignedOutAnswerStopsTheChecks", async () => {
    apiGet.mockResolvedValue({ kind: "signed-out" });
    render(<ReadingProgress check={CHECK} />);
    await pass(waitBefore(0));
    await pass(10 * 60_000);
    expect(apiGet).toHaveBeenCalledTimes(1);
    expect(refresh).not.toHaveBeenCalled();
  });

  it("aTroubledAnswerIsNeverAPaperRead", async () => {
    const { container } = render(<ReadingProgress check={CHECK} />);
    apiGet.mockResolvedValueOnce({ kind: "offline" }).mockResolvedValueOnce({ kind: "refused", status: 503, reason: "" });
    await pass(waitBefore(0));
    await pass(waitBefore(1));
    expect(refresh).not.toHaveBeenCalled();
    expect(live(container)?.textContent).toBe("");
    await pass(waitBefore(2));
    expect(apiGet, "and the checks go on after it").toHaveBeenCalledTimes(3);
  });

  it("aHiddenTabIsNotCheckedAndIsCheckedAgainWhenSeen", async () => {
    render(<ReadingProgress check={CHECK} />);
    await pass(waitBefore(0));
    expect(apiGet).toHaveBeenCalledTimes(1);
    setHidden(true);
    await pass(10 * 60_000);
    expect(apiGet, "no check while the tab is hidden").toHaveBeenCalledTimes(1);
    setHidden(false);
    await pass(0);
    expect(apiGet, "seen again: checked at once").toHaveBeenCalledTimes(2);
    await pass(waitBefore(2));
    expect(apiGet, "and at its pace after that").toHaveBeenCalledTimes(3);
  });

  it("afterTheCapItStopsAndOffersTheLink", async () => {
    const { container } = render(<ReadingProgress check={CHECK} />);
    expect(container.querySelector("a"), "no link while it is still checking").toBeNull();
    for (let i = 0; i < MAX_CHECKS; i++) await pass(waitBefore(i));
    expect(apiGet).toHaveBeenCalledTimes(MAX_CHECKS);
    const link = container.querySelector("a");
    expect(link?.getAttribute("href")).toBe(AGAIN);
    expect(link?.textContent).toBe(CHECK.words.again);
    await pass(10 * 60_000);
    expect(apiGet, "no check after the cap").toHaveBeenCalledTimes(MAX_CHECKS);
    expect(refresh).not.toHaveBeenCalled();
  });

  it("theResultsRefreshIsNeverBannerOnly", async () => {
    // A stale seen tap on this reading page wrote the banner-only marker (banner-only.ts) and the paper finished before it
    // was cleared. The check's refresh is the one read the page wants: carried with the marker, the server would send an
    // empty main, the holder would keep "being read", and the checks have stopped, so the page would never move on.
    const marker = () => document.cookie.split("; ").find((c) => c.startsWith(`${BANNER_ONLY_COOKIE}=`)) ?? null;
    window.history.replaceState(null, "", `/app/documents/${DOC}`);
    const release = holdOnBannerRefresh({ path: `/app/documents/${DOC}`, patient: null });
    markBannerOnly();
    release();
    expect(marker(), "guard: the marker is set while the paper is read").not.toBeNull();
    const atRefresh: Array<string | null> = [];
    refresh.mockImplementation(() => void atRefresh.push(marker()));
    apiGet.mockResolvedValue(status("DONE"));
    render(<ReadingProgress check={CHECK} />);
    await pass(waitBefore(0));
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(atRefresh[0], "the marker was on the result's refresh").toBeNull();
  });
});

import { act, cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MAX_CHECKS, waitBefore } from "./quiet-check";
import { QuietCheck, type QuietWords } from "./QuietCheck";

const apiGet = vi.fn();
vi.mock("@/api/browser", () => ({ apiGet: (...args: unknown[]) => apiGet(...args) }));
const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));

const A = "4b7e838a-96be-311b-9e3e-1e2c0a680fa9";
const B = "164846f2-a4f6-369c-950b-c8b827221498";
const WORDS: QuietWords = { settled: { DONE: "Read", NEEDS_RETAKE: "Retake the photo", FAILED: "Could not be read" }, again: "See how far it is" };
const answer = (status: string) => ({ kind: "ok", status: 200, body: { status } });
const statusOf = (path: string) => /\/documents\/([^/]+)\/status$/.exec(path)?.[1];

/** Which papers' status each check asked, in order. */
const asked = () => apiGet.mock.calls.map((c) => statusOf(c[0] as string));

let hidden = false;
beforeEach(() => {
  vi.useFakeTimers();
  hidden = false;
  Object.defineProperty(document, "visibilityState", { configurable: true, get: () => (hidden ? "hidden" : "visible") });
  apiGet.mockReset().mockResolvedValue(answer("PROCESSING"));
  refresh.mockReset();
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

/** Lets the clock run `ms` and every answer that came due resolve. */
const pass = (ms: number) => act(() => vi.advanceTimersByTimeAsync(ms));
const setHidden = (value: boolean) =>
  act(() => {
    hidden = value;
    document.dispatchEvent(new Event("visibilitychange"));
  });

const live = (el: HTMLElement) => el.querySelector("[role='status']")!;

describe("QuietCheck (PO ruling P6)", () => {
  it("checksEachPaperBeingReadAtJavasPaceAndNeverReloads", async () => {
    const { container } = render(<QuietCheck reading={[A, B]} again="/app/timeline" words={WORDS} />);
    expect(apiGet, "nothing is asked before the first wait").not.toHaveBeenCalled();
    await pass(waitBefore(0));
    expect(asked()).toEqual([A, B]);
    await pass(waitBefore(1));
    expect(asked()).toEqual([A, B, A, B]);
    // Still being read: no re-render asked for and nothing said.
    expect(refresh).not.toHaveBeenCalled();
    expect(live(container).textContent).toBe("");
  });

  it("aPaperThatSettlesReRendersThePageOnceAndSaysSoOnce", async () => {
    const { container, rerender } = render(<QuietCheck reading={[A, B]} again="/app/timeline" words={WORDS} />);
    await pass(waitBefore(0));
    await pass(waitBefore(1));
    expect(live(container).textContent, "two checks while reading say nothing").toBe("");
    apiGet.mockImplementation((path: string) => Promise.resolve(answer(statusOf(path) === A ? "DONE" : "PROCESSING")));
    await pass(waitBefore(2));
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(live(container).textContent).toBe("Read");
    // The page re-rendered: A is read, B is still being read, so only B is checked from here, and the region is not
    // told again by checks that find nothing new.
    rerender(<QuietCheck reading={[B]} again="/app/timeline" words={WORDS} />);
    apiGet.mockClear();
    await pass(waitBefore(0));
    await pass(waitBefore(1));
    expect(asked()).toEqual([B, B]);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(live(container).textContent).toBe("Read");
  });

  it("nothingBeingReadAsksNothing", async () => {
    render(<QuietCheck reading={[]} again="/app/timeline" words={WORDS} />);
    await pass(10 * 60_000);
    expect(apiGet).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
  });

  it("aReRenderWithNothingLeftStopsTheChecks", async () => {
    const { rerender } = render(<QuietCheck reading={[A]} again="/app/timeline" words={WORDS} />);
    apiGet.mockResolvedValue(answer("DONE"));
    await pass(waitBefore(0));
    expect(refresh).toHaveBeenCalledTimes(1);
    rerender(<QuietCheck reading={[]} again="/app/timeline" words={WORDS} />);
    apiGet.mockClear();
    await pass(10 * 60_000);
    expect(apiGet).not.toHaveBeenCalled();
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("aHiddenTabIsNotCheckedAndIsCheckedAgainWhenSeen", async () => {
    render(<QuietCheck reading={[A]} again="/app/timeline" words={WORDS} />);
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
    const { container } = render(<QuietCheck reading={[A]} again="/app/timeline?patient=p1" words={WORDS} />);
    expect(container.querySelector("a"), "no link while it is still checking").toBeNull();
    for (let i = 0; i < MAX_CHECKS; i++) await pass(waitBefore(i));
    expect(apiGet).toHaveBeenCalledTimes(MAX_CHECKS);
    const link = container.querySelector("a");
    expect(link?.getAttribute("href")).toBe("/app/timeline?patient=p1");
    expect(link?.textContent).toBe(WORDS.again);
    await pass(10 * 60_000);
    expect(apiGet, "no check after the cap").toHaveBeenCalledTimes(MAX_CHECKS);
    expect(refresh).not.toHaveBeenCalled();
  });

  it("aTroubledAnswerIsNeverASettledPaper", async () => {
    const { container } = render(<QuietCheck reading={[A]} again="/app/timeline" words={WORDS} />);
    apiGet.mockResolvedValueOnce({ kind: "offline" }).mockResolvedValueOnce({ kind: "refused", status: 503, reason: "" });
    await pass(waitBefore(0));
    await pass(waitBefore(1));
    expect(refresh).not.toHaveBeenCalled();
    expect(live(container).textContent).toBe("");
    // And the checks go on after it.
    await pass(waitBefore(2));
    expect(apiGet).toHaveBeenCalledTimes(3);
  });

  it("aPaperThatIsGoneReRendersAndSaysNothing", async () => {
    const { container } = render(<QuietCheck reading={[A]} again="/app/timeline" words={WORDS} />);
    apiGet.mockResolvedValue({ kind: "refused", status: 404, reason: "NOT_FOUND" });
    await pass(waitBefore(0));
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(live(container).textContent).toBe("");
  });
});

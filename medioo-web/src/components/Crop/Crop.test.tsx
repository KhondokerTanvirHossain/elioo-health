import { cleanup, fireEvent, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { Crop } from "./Crop";

const apiGet = vi.fn();
vi.mock("@/api/browser", () => ({ apiGet: (...args: unknown[]) => apiGet(...args) }));

beforeEach(() => apiGet.mockReset().mockResolvedValue({ kind: "ok", status: 200, body: { status: "DONE" } }));
afterEach(cleanup);

const DOC = "5e9edf6d-0a2a-4913-8675-9d07a4100003";
const SRC = `/app/documents/${DOC}/image?key=f%2Fp%2Fd%2Fcrop-Potassium.jpg`;
const words = { retry: "The picture won't load — try again", alt: "Potassium" };

const crop = (width: number | null = 120, height: number | null = 30) =>
  render(<Crop src={SRC} width={width} height={height} documentId={DOC} words={words} />).container;

describe("Crop", () => {
  it("aCropThatFailsShowsRetryNeverABlank", () => {
    const el = crop();
    expect(el.querySelector("img")?.getAttribute("src")).toBe(SRC);
    fireEvent.error(el.querySelector("img")!);
    expect(el.querySelector("img"), "no broken image left").toBeNull();
    const retry = el.querySelector("button");
    expect(retry?.textContent).toBe(words.retry);
    // The first failure only offers the retry: the session is not asked yet.
    expect(apiGet).not.toHaveBeenCalled();
  });

  it("theRetryIsAFreshImage", () => {
    const el = crop();
    const first = el.querySelector("img")!;
    fireEvent.error(first);
    fireEvent.click(el.querySelector("button")!);
    const second = el.querySelector("img");
    expect(second?.getAttribute("src")).toBe(SRC);
    // A new element is a new request; the same element with its src reset is not (e2e theRetryFetchesTheImageAgain).
    expect(second).not.toBe(first);
  });

  it("aFailedRetryChecksTheSession", () => {
    const el = crop();
    fireEvent.error(el.querySelector("img")!);
    fireEvent.click(el.querySelector("button")!);
    fireEvent.error(el.querySelector("img")!);
    expect(apiGet).toHaveBeenCalledTimes(1);
    expect(apiGet).toHaveBeenCalledWith(`/app/api/documents/${DOC}/status`);
    // Still never a blank: the retry is offered again.
    expect(el.querySelector("button")?.textContent).toBe(words.retry);
  });

  it("aCropIsLaidOutAtItsStoredSize", () => {
    const sized = crop(307, 41).querySelector("img")!;
    expect(sized.getAttribute("width")).toBe("307");
    expect(sized.getAttribute("height")).toBe("41");
    cleanup();
    const unsized = crop(null, null).querySelector("img")!;
    expect(unsized.hasAttribute("width")).toBe(false);
    expect(unsized.hasAttribute("height")).toBe(false);
  });
});

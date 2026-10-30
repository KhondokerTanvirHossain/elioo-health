import { cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { BANNER_ONLY_COOKIE, clearBannerOnly, holdOnBannerRefresh, markBannerOnly, readBannerOnly } from "./banner-only";
import { KeptOnBannerRefresh } from "./KeptOnBannerRefresh";

const PATH = "/app/documents/5e9edf6d-0a2a-4913-8675-9d07a4100003";
const PATIENT = "5e9edf6d-0a2a-4913-8675-9d07a4100002";
const marker = () => document.cookie.split("; ").find((c) => c.startsWith(`${BANNER_ONLY_COOKIE}=`)) ?? null;

// The marker is a cookie under /app, as a paper page sees it: the document is at the paper's address.
beforeEach(() => window.history.replaceState(null, "", PATH));

afterEach(() => {
  cleanup();
  clearBannerOnly();
  vi.unstubAllGlobals();
});

describe("banner-only", () => {
  it("theMarkerNamesOnlyTheHeldPageAndOnlyWhileItIsHeld", () => {
    expect(markBannerOnly(), "no page held: no marker").toBe(false);
    expect(marker()).toBeNull();
    const release = holdOnBannerRefresh({ path: PATH, patient: PATIENT });
    expect(markBannerOnly()).toBe(true);
    const value = marker()!.slice(BANNER_ONLY_COOKIE.length + 1);
    expect(readBannerOnly(value, PATH)).toEqual({ path: PATH, patient: PATIENT });
    clearBannerOnly();
    release();
    expect(markBannerOnly(), "released: no marker").toBe(false);
    expect(marker()).toBeNull();
  });

  it("theServerHonoursAMarkerForItsOwnPathOnly", () => {
    const value = encodeURIComponent(JSON.stringify({ path: PATH, patient: null }));
    expect(readBannerOnly(value, PATH), "a gone paper names nobody").toEqual({ path: PATH, patient: null });
    // The family moved on before the marker was cleared: another page reads as usual.
    expect(readBannerOnly(value, `${PATH}/all`)).toBeNull();
    expect(readBannerOnly(undefined, PATH)).toBeNull();
    expect(readBannerOnly("", PATH)).toBeNull();
    expect(readBannerOnly("%E0%A4%A", PATH), "not URI-encoded").toBeNull();
    expect(readBannerOnly("not json", PATH)).toBeNull();
    expect(readBannerOnly(encodeURIComponent(JSON.stringify({ path: PATH, patient: 7 })), PATH)).toBeNull();
    expect(readBannerOnly(encodeURIComponent(JSON.stringify([PATH])), PATH)).toBeNull();
  });

  it("aHeldMainKeepsWhatItShowsWhenTheRefreshSendsNone", () => {
    const { container, rerender } = render(
      <KeptOnBannerRefresh path={PATH} patient={PATIENT}>
        <p data-paper>the paper</p>
      </KeptOnBannerRefresh>,
    );
    expect(markBannerOnly(), "a mounted holder holds its page").toBe(true);
    expect(readBannerOnly(marker()!.slice(BANNER_ONLY_COOKIE.length + 1), PATH)).toEqual({ path: PATH, patient: PATIENT });
    rerender(<KeptOnBannerRefresh path={PATH} patient={PATIENT} />);
    expect(container.querySelector("[data-paper]")?.textContent, "kept across a banner-only refresh").toBe("the paper");
    rerender(
      <KeptOnBannerRefresh path={PATH} patient={PATIENT}>
        <p data-paper>read again</p>
      </KeptOnBannerRefresh>,
    );
    expect(container.querySelector("[data-paper]")?.textContent, "a real render is the server's").toBe("read again");
    cleanup();
    clearBannerOnly();
    expect(markBannerOnly(), "unmounted: nothing held").toBe(false);
  });

  it("aHolderWithNothingToKeepClearsTheMarkerAndLoadsAgain", () => {
    const reload = vi.fn();
    vi.stubGlobal("location", { ...location, reload });
    render(<KeptOnBannerRefresh path={PATH} patient={PATIENT} />);
    expect(reload).toHaveBeenCalledTimes(1);
  });
});

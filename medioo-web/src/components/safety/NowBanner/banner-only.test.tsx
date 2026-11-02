import { cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  BANNER_ONLY_COOKIE,
  clearBannerOnly,
  holdOnBannerRefresh,
  markBannerOnly,
  readBannerOnly,
  rememberShown,
  shownBefore,
} from "./banner-only";
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
    // A marker that outlived its refresh: written for this page, then the page mounted empty (a load that got the
    // banner-only main). Reloading with the marker still set would get the empty main again, so it must be gone by then.
    // A page this tab has not shown (the store of what each page showed would restore it otherwise).
    const fresh = `${PATH}/values/7af69ec6-676f-3bb9-b0bb-3e665c36ed57`;
    const release = holdOnBannerRefresh({ path: fresh, patient: PATIENT });
    expect(markBannerOnly()).toBe(true);
    release();
    expect(marker(), "guard: the marker is set before the holder mounts").not.toBeNull();
    const atReload: Array<string | null> = [];
    const reload = vi.fn(() => void atReload.push(marker()));
    vi.stubGlobal("location", { ...location, reload });
    render(<KeptOnBannerRefresh path={fresh} patient={PATIENT} />);
    expect(reload).toHaveBeenCalledTimes(1);
    expect(atReload[0], "the marker was still set at reload").toBeNull();
    expect(marker(), "and it stays cleared").toBeNull();
  });

  it("aHolderMountedAgainEmptyForAPageItShowedRestoresItWithNoReload", () => {
    // Back to the paper after a banner-only refresh and a sheet: the router restores the paper's address from its cache,
    // which holds the refresh's empty main. The holder mounts fresh with no children: it shows what this tab last showed
    // at that address, as back did before the refresh, and loads nothing.
    const reload = vi.fn();
    vi.stubGlobal("location", { ...location, reload });
    render(
      <KeptOnBannerRefresh path={PATH} patient={PATIENT}>
        <p data-paper>the paper</p>
      </KeptOnBannerRefresh>,
    );
    cleanup();
    const { container } = render(<KeptOnBannerRefresh path={PATH} patient={PATIENT} />);
    expect(container.querySelector("[data-paper]")?.textContent, "restored on a fresh mount").toBe("the paper");
    expect(reload, "and nothing loaded").not.toHaveBeenCalled();
    cleanup();
    // A page this tab never showed has nothing to restore: it loads again, as before.
    render(<KeptOnBannerRefresh path={`${PATH}/all`} patient={PATIENT} />);
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it("theTabRemembersTheLastFourPagesOnly", () => {
    const paths = [1, 2, 3, 4, 5].map((n) => `/app/documents/0000000${n}`);
    for (const p of paths) rememberShown(p, p);
    expect(shownBefore(paths[0]!), "the oldest is forgotten").toBeUndefined();
    expect(paths.slice(1).map(shownBefore)).toEqual(paths.slice(1));
    // Shown again, a page is the newest: the next one forgets the page after it.
    rememberShown(paths[1]!, "again");
    rememberShown("/app/documents/00000006", "six");
    expect(shownBefore(paths[1]!)).toBe("again");
    expect(shownBefore(paths[2]!)).toBeUndefined();
  });

  it("theMarkerCarriesTheCodenameAsEveryOtherCookieDoes", () => {
    // baymax_session, baymax_patient, baymax_lang …: Java sees every cookie under /app, so this one reads as ours.
    expect(BANNER_ONLY_COOKIE).toBe("baymax_banner_only");
  });
});

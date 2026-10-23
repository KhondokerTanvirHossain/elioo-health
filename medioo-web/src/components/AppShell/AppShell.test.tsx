import { cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { MeJson } from "@/api/types";
import { t } from "@/copy";
import longName from "../../../fixtures/api/me/long-name.json";
import onePatient from "../../../fixtures/api/me/one-patient.json";
import twoPatients from "../../../fixtures/api/me/two-patients.json";
import { shellWords } from "@/app/app/(signed-in)/shell-words";
import { AppShell } from "./AppShell";

/*
 * The shell every signed-in page renders (plan W4): the banner slot above everything, the sidebar (from 900 px; behind
 * the toolbar's button until 1199 px), the toolbar with Mio and the switcher, the page's main, the conversation aside,
 * and the phone's tab bar. The layout's widths are a browser's to measure (e2e/shell.spec.ts); this holds the order,
 * the names and the wiring.
 */

const ONE: MeJson = onePatient.body;
const TWO: MeJson = twoPatients.body;
const LONG: MeJson = longName.body;
const assign = vi.fn();
let calls: { url: string; init: RequestInit }[] = [];

beforeEach(() => {
  calls = [];
  assign.mockClear();
  vi.stubGlobal("location", { pathname: "/app/shell-test", search: "", assign });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function shell(me: MeJson, extra: Partial<Parameters<typeof AppShell>[0]> = {}) {
  const active = extra.active === undefined ? (me.patients[0] ?? null) : extra.active;
  return render(
    <AppShell
      lang="bn"
      preview={false}
      me={me}
      active={active}
      words={shellWords("bn", me, active)}
      tab="chat"
      main={<p data-slot="main">main</p>}
      aside={<p data-slot="aside">aside</p>}
      banner={<p data-slot="banner">banner</p>}
      {...extra}
    />,
  );
}

describe("AppShell", () => {
  it("theBannerIsAboveEverythingThenMainThenTheAside", () => {
    const { container } = shell(ONE);
    const slots = [...container.querySelectorAll("[data-slot]")].map((e) => e.getAttribute("data-slot"));
    expect(slots).toEqual(["banner", "main", "aside"]);
    const banner = container.querySelector("[data-slot=banner]")!;
    // Nothing of the shell (the toolbar, the sidebar, the tabs) comes before the banner in the document.
    const first = container.querySelector("header, nav, aside, main, button, a");
    expect(first!.compareDocumentPosition(banner) & Node.DOCUMENT_POSITION_PRECEDING, "the banner comes first").toBeTruthy();
    expect(container.querySelector("main [data-slot=main]"), "the page's main is the document's main").not.toBeNull();
  });

  it("everyShellPageCarriesTheDisclaimer", () => {
    // Master Global Constraints: the not-a-doctor line on every page that shows a result. Every signed-in page renders
    // through this shell, so the bundle's `disclaimer` is said once here, under the page's main, as Java's AppShell
    // footer says it on every app page; in both languages, with or without a patient in view.
    for (const lang of ["bn", "en"] as const) {
      for (const active of [ONE.patients[0]!, null]) {
        const { container } = shell(ONE, { lang, active, words: shellWords(lang, ONE, active) });
        const foot = container.querySelectorAll("main .app-shell__foot");
        expect(foot.length, `${lang} ${active ? "patient" : "none"}: one disclaimer, inside main`).toBe(1);
        expect(foot[0]!.textContent).toBe(t(lang, "disclaimer"));
        // After the page's own content, never above it.
        const main = container.querySelector("[data-slot=main]")!;
        expect(main.compareDocumentPosition(foot[0]!) & Node.DOCUMENT_POSITION_FOLLOWING, "after the page").toBeTruthy();
        cleanup();
      }
    }
  });

  it("noAsideNoConversationColumn", () => {
    const { container } = shell(ONE, { aside: undefined, banner: undefined });
    expect(container.querySelector(".app-shell__aside")).toBeNull();
    expect(container.querySelector(".app-shell__banner")).toBeNull();
    expect(container.querySelector(".app-shell--aside")).toBeNull();
    cleanup();
    expect(shell(ONE).container.querySelector(".app-shell--aside")).not.toBeNull();
  });

  it("theToolbarButtonControlsTheSidebar", () => {
    const { container, getByRole } = shell(ONE);
    const button = getByRole("button", { name: t("bn", "tabs.label") });
    const sidebar = container.querySelector(`#${button.getAttribute("aria-controls")}`);
    expect(sidebar, "aria-controls names the sidebar").not.toBeNull();
    expect(sidebar!.classList.contains("app-shell__sidebar")).toBe(true);
    expect(button.getAttribute("aria-expanded")).toBe("false");
    expect(sidebar!.hasAttribute("data-open")).toBe(false);
    fireEvent.click(button);
    expect(button.getAttribute("aria-expanded")).toBe("true");
    expect(sidebar!.hasAttribute("data-open")).toBe(true);
    fireEvent.click(button);
    expect(button.getAttribute("aria-expanded")).toBe("false");
    expect(sidebar!.hasAttribute("data-open")).toBe(false);
  });

  it("theSidebarHoldsTheTabsAndTheWayOut", () => {
    const { container } = shell(ONE);
    const sidebar = container.querySelector(".app-shell__sidebar")!;
    expect(sidebar.querySelector("nav a[href='/app/home']"), "the tabs, as a rail").not.toBeNull();
    expect([...sidebar.querySelectorAll("button")].map((b) => b.textContent)).toContain(t("bn", "app.logout"));
    expect(sidebar.textContent, "whose account this is").toContain(ONE.family.owner_name);
    // The phone's tab bar is its own nav, outside the sidebar.
    const bars = [...container.querySelectorAll("nav")].filter((n) => !sidebar.contains(n));
    expect(bars).toHaveLength(1);
  });

  it("theSwitcherSaysWhosePapersAreShown", () => {
    const { getByRole } = shell(LONG);
    const name = LONG.patients[0]!.name;
    expect(getByRole("button", { name: t("bn", "switch.label", name) })).toBeTruthy();
  });

  it("noActivePatientNoSwitcher", () => {
    // The chooser is the page's main when nobody is chosen; the toolbar never shows a patient it guessed.
    const { container } = shell(TWO, { active: null });
    expect(container.querySelector(".patient-switcher")).toBeNull();
  });

  it("signOutPostsThenGoesToTheSignInPage", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string, init: RequestInit) => {
        calls.push({ url, init });
        return new Response(null, { status: 204 });
      }),
    );
    const { getByRole } = shell(ONE, { preview: true });
    fireEvent.click(getByRole("button", { name: t("bn", "app.logout") }));
    await waitFor(() => expect(assign).toHaveBeenCalledTimes(1));
    expect(calls.map((c) => [c.init.method, c.url])).toEqual([["POST", "/app/api/sign-out"]]);
    const headers = new Headers(calls[0]!.init.headers);
    expect(headers.get("x-medioo")).toBe("1");
    expect(headers.get("content-type")).toBe("application/json");
    // A preview page signs out to the preview's sign-in page.
    expect(assign).toHaveBeenCalledWith("/app/preview/");
  });
});

import { cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { JavaAnswer } from "@/api/java";
import type { FollowUpsJson, MeJson } from "@/api/types";
import { t, type CopyKey } from "@/copy";
import followUps from "../../../../fixtures/api/follow-ups/proto-amma.json";
import proto from "../../../../fixtures/api/me/proto.json";
import nowBanner from "../../../../fixtures/api/now-banner/proto.json";

/*
 * signedInPage's choice of shell (plan desktop-parity Task 5): a page that gives `desktop` slots gets DesktopShell only
 * when desktopLayout(ctx) says so (a preview request until the flip, Task 10); every other request renders today's
 * AppShell exactly as before, so merging the desktop work changes nothing for live users. On the desktop layout the
 * phone shell stays in the document for widths below 900 px (nothing changes there until the mobile list is marked),
 * and the desktop shell takes over from 900 px.
 */

const ME = proto.body as MeJson;
const AMMA = ME.patients.find((p) => p.age === 68)!;
const ctx = { lang: "bn" as const, preview: false, here: "/app/home" };
let reads: string[] = [];
let remembered: string | null = AMMA.id;

vi.mock("@/api/request", () => ({ requestContext: async () => ctx }));
// NowBannerHold refreshes through the app router, which a unit test has none of; notFound and redirect stay real.
vi.mock("next/navigation", async (actual) => ({
  ...(await actual<typeof import("next/navigation")>()),
  useRouter: () => ({ refresh: () => undefined }),
}));
vi.mock("@/api/java", async (actual) => ({
  ...(await actual<typeof import("@/api/java")>()),
  javaGet: async (path: string): Promise<JavaAnswer<unknown>> => {
    reads.push(path);
    if (path === "/app/api/me") return { status: 200, body: { ...ME, active_patient_id: remembered, preview: true } };
    if (path === "/app/api/now-banner") return { status: 200, body: nowBanner.body };
    if (path === `/app/api/patients/${AMMA.id}/follow-ups`) return { status: 200, body: followUps.body as FollowUpsJson };
    throw new Error(`unexpected read ${path}`);
  },
}));

const { signedInPage } = await import("./shell");
const { desktopFrame } = await import("./desktop-frame");

beforeEach(() => {
  reads = [];
  remembered = AMMA.id;
  ctx.preview = false;
  vi.stubGlobal("location", { pathname: ctx.here, search: "", assign: vi.fn() });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const page = (desktop: boolean) =>
  signedInPage({
    tab: "chat",
    main: <p data-slot="main">main</p>,
    aside: <p data-slot="aside">aside</p>,
    ...(desktop ? { desktop: { frame: desktopFrame, paper: <p data-slot="paper">paper</p>, talk: <p data-slot="talk">talk</p> } } : {}),
  });

describe("signedInPage's shell", () => {
  it("aLiveRequestKeepsTodaysShellEvenWithDesktopSlots", async () => {
    // The gate: preview false and DESKTOP_DEFAULT false is today's AppShell and nothing else, whatever the page gives.
    const { container } = render(await page(true));
    expect(container.querySelector(".desktop-shell"), "no desktop shell for a live request").toBeNull();
    expect(container.querySelector("[data-slot=paper], [data-slot=talk]"), "the desktop slots are not rendered").toBeNull();
    expect(container.querySelector(".app-shell")).not.toBeNull();
    expect(container.querySelector(".app-shell--phone"), "today's shell, unchanged: shown at every width").toBeNull();
    expect(reads.some((r) => r.endsWith("/follow-ups")), "no read the desktop alone needs").toBe(false);
  });

  it("aPreviewPageWithoutDesktopSlotsKeepsTodaysShell", async () => {
    ctx.preview = true;
    const { container } = render(await page(false));
    expect(container.querySelector(".desktop-shell")).toBeNull();
    expect(container.querySelector(".app-shell--phone")).toBeNull();
  });

  it("aPreviewPageWithDesktopSlotsRendersTheDesktopShellBesideThePhoneShell", async () => {
    ctx.preview = true;
    const { container } = render(await page(true));
    const desk = container.querySelector(".desktop-shell")!;
    expect(desk, "the desktop shell").not.toBeNull();
    expect(desk.querySelector("[data-slot=paper]")).not.toBeNull();
    expect(desk.querySelector("[data-slot=talk]")).not.toBeNull();
    expect(desk.querySelector(".desktop-sidebar"), "the desktop sidebar").not.toBeNull();
    expect(desk.querySelector(".desktop-sidebar__ledger-title")!.textContent).toBe(t("bn", "desk.ledger", AMMA.name_possessive));
    // The NOW banner is in the desktop shell's bar slot, never in its sidebar: Java's sentence, read off each.
    const sentence = t("bn", nowBanner.body.line_key as CopyKey);
    expect(desk.querySelector(".desktop-shell__bar")!.textContent).toContain(sentence);
    expect(desk.querySelector(".desktop-shell__sidebar")!.textContent).not.toContain(sentence);
    // The prescription's date in the sidebar, from the patient's follow-ups.
    expect(reads).toContain(`/app/api/patients/${AMMA.id}/follow-ups`);
    expect(desk.querySelector(".desktop-sidebar__visit")).not.toBeNull();
    // Below 900 px: today's phone shell, marked so it gives way to the desktop from 900 px.
    const phone = container.querySelector(".app-shell.app-shell--phone")!;
    expect(phone, "the phone shell stays for narrow windows").not.toBeNull();
    expect(phone.querySelector("[data-slot=main]")).not.toBeNull();
    expect(desk.contains(phone)).toBe(false);
    // Two shells, one sidebar id each.
    const ids = [...container.querySelectorAll("[id]")].map((e) => e.id);
    expect(ids.length, "the page has ids to compare").toBeGreaterThan(1);
    expect(ids.filter((id, i) => ids.indexOf(id) !== i), "ids said twice").toEqual([]);
  });

  it("withNobodyChosenTheDesktopShowsTheChooserAndNamesNobody", async () => {
    // Two patients, no remembered choice: the chooser stands in for the page in both shells; nothing is decided.
    ctx.preview = true;
    remembered = null;
    const { container } = render(await page(true));
    const desk = container.querySelector(".desktop-shell")!;
    expect(desk.querySelector(".desktop-shell__paper .patient-chooser"), "the chooser in the paper column").not.toBeNull();
    expect(desk.querySelector("[data-slot=paper], [data-slot=talk]"), "no page content for nobody").toBeNull();
    expect(desk.querySelector(".desktop-sidebar__family [aria-current]"), "no patient marked").toBeNull();
    expect(desk.querySelector(".desktop-sidebar__ledger-title"), "no ledger named").toBeNull();
    expect(reads.some((r) => r.endsWith("/follow-ups")), "nobody's follow-ups read").toBe(false);
    expect(container.querySelector(".app-shell--phone .patient-chooser"), "the phone shell's chooser too").not.toBeNull();
    // Two shells, each id once: a heading named twice would name the hidden one.
    const ids = [...container.querySelectorAll("[id]")].map((e) => e.id);
    expect(ids.length, "the page has ids to compare").toBeGreaterThan(1);
    expect(ids.filter((id, i) => ids.indexOf(id) !== i), "ids said twice").toEqual([]);
  });
});

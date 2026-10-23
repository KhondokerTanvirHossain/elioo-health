import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test, type Page } from "@playwright/test";
import type { FamilyJson, MeJson } from "../src/api/types";
import { t } from "../src/copy";
import { useScenario } from "./support/browser-api";
import { FIXTURES } from "./support/scenarios.mjs";
import { screenChecks } from "./support/screen-checks";

/*
 * family and settings (plan W10) on the production build, against stub Java answering the bodies FamilyApiContractTest
 * wrote: the theme cookie read on the server, the profile address WhatsApp already sent, no family delete (S7), the
 * writes as the browser sends them, and the shared screen checks for the owner and the share member.
 */

const family = (fixture: string): FamilyJson => (JSON.parse(readFileSync(join(FIXTURES, fixture), "utf8")) as { body: FamilyJson }).body;
const OWNER = family("family/owner.json");
const OTHER_FAMILYS = "5e9edf6d-0a2a-4913-8675-9d07a4100099";

async function open(page: Page, scenario: string, path = "/app/family") {
  const recorder = await useScenario(page, scenario);
  const response = await page.goto(path);
  expect(response?.status(), `${path} under ${scenario}`).toBe(200);
  await page.waitForLoadState("load");
  return recorder;
}

/** The `<html …>` start tag of the first HTML response, as the server sent it (before any script ran). */
async function htmlTag(page: Page, path: string): Promise<string> {
  const response = await page.request.get(path);
  expect(response.status(), path).toBe(200);
  const tag = /<html[^>]*>/.exec(await response.text())?.[0];
  expect(tag, "the document has an <html> start tag").toBeDefined();
  return tag!;
}

async function setCookie(page: Page, name: string, value: string) {
  const base = new URL(test.info().project.use.baseURL!);
  await page.context().addCookies([{ name, value, domain: base.hostname, path: "/app" }]);
}

test.describe("the theme", () => {
  test("theThemeCookieIsReadOnTheServer", async ({ page }) => {
    await useScenario(page, "family-owner");
    expect(await htmlTag(page, "/app/family"), "no cookie: the phone's own scheme").not.toContain("data-theme");
    for (const theme of ["dark", "light"] as const) {
      await setCookie(page, "baymax_theme", theme);
      expect(await htmlTag(page, "/app/family"), `baymax_theme=${theme}`).toContain(`data-theme="${theme}"`);
    }
    // auto, and a value Java never sets, write nothing.
    for (const value of ["auto", "blue"]) {
      await setCookie(page, "baymax_theme", value);
      expect(await htmlTag(page, "/app/family"), `baymax_theme=${value}`).not.toContain("data-theme");
    }
  });

  test("choosingDarkPutsThePreferenceAndRepaintsAtOnce", async ({ page }) => {
    const recorder = await open(page, "family-owner");
    await page.getByRole("button", { name: t("bn", "theme.dark") }).click();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
    const sent = recorder.requests().filter((r) => r.path === "/app/api/preferences");
    expect(sent.map((r) => [r.method, r.headers["x-medioo"], r.headers["content-type"], r.body])).toEqual([
      ["PUT", "1", "application/json", { theme: "dark" }],
    ]);
    // The tokens followed: the page is painted dark now, without a reload.
    const bg = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue("--bg").trim());
    await page.evaluate(() => document.documentElement.setAttribute("data-theme", "light"));
    const light = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue("--bg").trim());
    expect(bg, "--bg differs between dark and light").not.toBe(light);
  });
});

test.describe("the pages", () => {
  test("theProfileLinkFromWhatsAppStillOpens", async ({ page }) => {
    const second = OWNER.patients[1]!;
    await open(page, "family-owner", `/app/patients/${second.id}/profile`);
    const cards = page.locator("[data-patient-card]");
    await expect(cards).toHaveCount(1);
    await expect(cards).toHaveAttribute("data-patient-card", second.id);
    await expect(page.getByRole("heading", { name: t("bn", "family.info.title", second.name_possessive) })).toBeVisible();
    // The number is the account's, and the account is the tab's: the profile page shows the patient only.
    await expect(page.getByText(OWNER.account.number)).toHaveCount(0);
  });

  test("aProfileLinkNamesItsOwnPatientInTheToolbar", async ({ page }) => {
    // Java's ProfilePages.profilePage chooses the patient the link names (302 to /app/family?patient={id}): a family
    // whose remembered patient is Ma, opening Baba's link, sees Baba named above Baba's card, never Ma.
    const me = (JSON.parse(readFileSync(join(FIXTURES, "me/family-owner.json"), "utf8")) as { body: MeJson }).body;
    const first = OWNER.patients[0]!;
    const second = OWNER.patients[1]!;
    expect(me.active_patient_id, "the scenario remembers the first patient").toBe(first.id);
    await open(page, "family-owner", `/app/patients/${second.id}/profile`);
    await expect(page.locator("[data-patient-card]")).toHaveAttribute("data-patient-card", second.id);
    await expect(page.locator(".patient-switcher__label")).toHaveText(t("bn", "switch.label", second.name));
    // Guard: the remembered patient is named on the tab itself, so the toolbar does follow the asked one here.
    await open(page, "family-owner");
    await expect(page.locator(".patient-switcher__label")).toHaveText(t("bn", "switch.label", first.name));
  });

  test("aProfileThisFamilyCannotSeeIsNotFound", async ({ page }) => {
    await useScenario(page, "family-owner");
    const response = await page.goto(`/app/patients/${OTHER_FAMILYS}/profile`);
    expect(response?.status()).toBe(404);
  });

  test("theTabIsAboutEveryoneSoTwoPatientsAndNoChoiceShowNoChooser", async ({ page }) => {
    await open(page, "family-two-no-choice");
    await expect(page.getByRole("heading", { name: t("bn", "switch.title") })).toHaveCount(0);
    await expect(page.locator("[data-patient-card]")).toHaveCount(OWNER.patients.length);
    // Nobody was decided for the family: the toolbar names no patient.
    await expect(page.locator(".patient-switcher")).toHaveCount(0);
  });

  test("noDeleteFamilyControlYet", async ({ page }) => {
    // S7: no family delete in W10. Read in English, so "delete", "remove" and "erase" are the words to look for.
    await useScenario(page, "family-owner");
    await setCookie(page, "baymax_lang", "en");
    const response = await page.goto("/app/family");
    expect(response?.status()).toBe(200);
    const names = await page
      .locator("button, a[href], [role=button], input[type=submit]")
      .evaluateAll((els) => els.map((e) => `${e.textContent ?? ""} ${e.getAttribute("aria-label") ?? ""} ${e.getAttribute("href") ?? ""}`.trim()));
    // Guard: the controls were read, in English (sign out is among them).
    expect(names, "the controls on the page").toContain(t("en", "app.logout"));
    expect(names.filter((n) => /delet|remov|eras/i.test(n)), "controls that name a delete").toEqual([]);
  });

  test("aFlagSaveIsOnePutWithTheWholeList", async ({ page }) => {
    const recorder = await open(page, "family-owner");
    const first = OWNER.patients[0]!;
    const card = page.locator(`[data-patient-card="${first.id}"]`);
    await card.getByRole("checkbox", { name: t("bn", "flag.kidney") }).check();
    await card.getByRole("button", { name: t("bn", "profile.save") }).click();
    await expect(card.getByText(t("bn", "profile.saved"))).toBeVisible();
    const sent = recorder.requests().filter((r) => r.path === `/app/api/patients/${first.id}/flags`);
    expect(sent.map((r) => [r.method, r.headers["x-medioo"], r.body])).toEqual([
      ["PUT", "1", { chronic_flags: [...first.chronic_flags.map((f) => f.value), "kidney"] }],
    ]);
    // The card now shows the list Java saved (stub Java derives it from the request, as FamilyApiHandler does).
    await expect(card.getByRole("checkbox", { name: t("bn", "flag.kidney") })).toBeChecked();
  });

  test("aTypedFlagIsSavedAndTheCardShowsWhatJavaStored", async ({ page }) => {
    const recorder = await open(page, "family-owner");
    const first = OWNER.patients[0]!;
    const card = page.locator(`[data-patient-card="${first.id}"]`);
    const field = card.getByRole("textbox", { name: t("bn", "flag.other") });
    await field.fill("  Migraine ");
    await card.getByRole("button", { name: t("bn", "profile.save") }).click();
    await expect(card.getByText(t("bn", "profile.saved"))).toBeVisible();
    const sent = recorder.requests().filter((r) => r.path === `/app/api/patients/${first.id}/flags`);
    expect(sent.map((r) => r.body)).toEqual([{ chronic_flags: [...first.chronic_flags.map((f) => f.value), "Migraine"] }]);
    // Java lower-cases what it stores (normalizeFlags): the card shows that, ticked, and the field is empty again.
    await expect(card.getByRole("checkbox", { name: "migraine", exact: true })).toBeChecked();
    await expect(card.getByRole("checkbox", { name: "Migraine", exact: true })).toHaveCount(0);
    await expect(field).toHaveValue("");
  });

  test("aSharedCardHasNoControl", async ({ page }) => {
    // D30: a share member sees the facts and nothing to press, on every card.
    await open(page, "family-share-member");
    await expect(page.locator("[data-patient-card]")).toHaveCount(family("family/share-member.json").patients.length);
    await expect(page.locator("[data-patient-card] input, [data-patient-card] button")).toHaveCount(0);
  });

  test("aViewOnlyRefusalOnSaveIsCopyNotACrash", async ({ page }) => {
    // An owner's save Java refuses VIEW_ONLY (the share withdrawn while the page was open): said in words, never a crash.
    const errors: string[] = [];
    page.on("pageerror", (e) => errors.push(e.message));
    const recorder = await open(page, "family-owner-view-only");
    const first = OWNER.patients[0]!;
    const card = page.locator(`[data-patient-card="${first.id}"]`);
    await card.getByRole("checkbox", { name: t("bn", "flag.kidney") }).check();
    await card.getByRole("button", { name: t("bn", "profile.save") }).click();
    await expect(card.locator(".family-flags__status")).toHaveText(t("bn", "app.err.forbidden"));
    await expect(card.getByText(t("bn", "profile.saved"))).toHaveCount(0);
    expect(errors, "page errors").toEqual([]);
    // Guard: the save was sent, so the words are the answer to it, not a form that never sent anything.
    expect(recorder.requests().filter((r) => r.path === `/app/api/patients/${first.id}/flags`).map((r) => r.method)).toEqual(["PUT"]);
    // The card is still the owner's form, ticks as they were before the save.
    await expect(card.getByRole("checkbox", { name: t("bn", "flag.kidney") })).toBeChecked();
  });

  test("theFamilyStopAsksFirstAndKeepSendsNothing", async ({ page }) => {
    const recorder = await open(page, "family-owner");
    const stops = () => recorder.requests().filter((r) => r.path === "/app/api/family/reminders");
    const account = page.locator("[data-family-reminders]");
    await account.getByRole("button", { name: t("bn", "optout.family") }).click();
    const dialog = page.getByRole("dialog", { name: t("bn", "optout.q") });
    await expect(dialog).toBeVisible();
    expect(stops(), "nothing sent on the first press").toEqual([]);
    await dialog.getByRole("button", { name: t("bn", "optout.keep") }).click();
    await expect(dialog).toBeHidden();
    expect(stops(), "nothing sent after keep").toEqual([]);
    // The confirm sends exactly one stop.
    await account.getByRole("button", { name: t("bn", "optout.family") }).click();
    await page.getByRole("dialog", { name: t("bn", "optout.q") }).getByRole("button", { name: t("bn", "optout.family") }).click();
    await expect.poll(() => stops().map((r) => [r.method, r.body])).toEqual([["PUT", { on: false }]]);
  });
});

// The pressed language and theme option: a tick (a shape) and an edge whose WCAG contrast against the track it sits on
// is at least 3:1 (1.4.11), each colour composited over what is painted behind it, in both schemes and at both widths.
test.describe("the segmented choice", () => {
  for (const colorScheme of ["light", "dark"] as const) {
    for (const viewport of [
      { width: 390, height: 844 },
      { width: 1280, height: 800 },
    ]) {
      test(`thePressedOptionsEdgeMeetsThreeToOne ${colorScheme} ${viewport.width}`, async ({ page }) => {
        await page.setViewportSize(viewport);
        await page.emulateMedia({ colorScheme });
        await open(page, "family-owner");
        const report = await page.evaluate(() => {
          const parse = (c: string) => {
            const m = /rgba?\(([^)]+)\)/.exec(c);
            const [r, g, b, a] = (m?.[1] ?? "0,0,0,0").split(/[\s,/]+/).filter(Boolean).map(Number);
            return { r: r!, g: g!, b: b!, a: a ?? 1 };
          };
          const over = (top: ReturnType<typeof parse>, under: ReturnType<typeof parse>) => ({
            r: top.r * top.a + under.r * (1 - top.a),
            g: top.g * top.a + under.g * (1 - top.a),
            b: top.b * top.a + under.b * (1 - top.a),
            a: 1,
          });
          const lum = (c: ReturnType<typeof parse>) => {
            const ch = (v: number) => {
              const s = v / 255;
              return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
            };
            return 0.2126 * ch(c.r) + 0.7152 * ch(c.g) + 0.0722 * ch(c.b);
          };
          const ratio = (x: ReturnType<typeof parse>, y: ReturnType<typeof parse>) => {
            const [hi, lo] = [lum(x), lum(y)].sort((p, q) => q - p);
            return (hi! + 0.05) / (lo! + 0.05);
          };
          // The opaque colour behind an element: its first ancestor with an opaque background, else the body's.
          const opaqueBehind = (el: Element | null): ReturnType<typeof parse> => {
            for (let e = el; e; e = e.parentElement) {
              const c = parse(getComputedStyle(e).backgroundColor);
              if (c.a === 1) return c;
            }
            return parse(getComputedStyle(document.body).backgroundColor);
          };
          return [...document.querySelectorAll<HTMLElement>('.family-choice__option[aria-pressed="true"]')].map((option) => {
            const track = option.closest<HTMLElement>(".family-choice")!;
            const trackPaint = over(parse(getComputedStyle(track).backgroundColor), opaqueBehind(track.parentElement));
            const shadow = getComputedStyle(option).boxShadow;
            const edge = /rgba?\([^)]*\)/.exec(shadow)?.[0] ?? "";
            const ring = over(parse(edge), trackPaint);
            const tick = option.querySelector(".family-choice__tick");
            return { name: option.textContent?.trim(), edge, ratio: ratio(ring, trackPaint), tick: tick !== null && tick.getBoundingClientRect().width > 0 };
          });
        });
        // Guard: both controls (language and theme) were measured.
        expect(report.length, "pressed options measured").toBe(2);
        for (const r of report) {
          expect(r.edge, `${r.name} draws an edge`).not.toBe("");
          expect(r.ratio, `${r.name}'s edge against its track`).toBeGreaterThanOrEqual(3);
          expect(r.tick, `${r.name} shows its tick`).toBe(true);
        }
      });
    }
  }
});

// The owner and the share member, each with its longest twin (LONGEST), whose long name must be drawn on a card; the
// stopped family; and one profile page. The theme and language tracks are judged as tracks (role=group, --fill).
const CARD = "[data-patient-card]";
test.describe("the owner", () => screenChecks("/app/family", "family-owner", { longest: true, longestIn: CARD, tracks: 2 }));
test.describe("the share member", () => screenChecks("/app/family", "family-share-member", { longest: true, longestIn: CARD, tracks: 2 }));
test.describe("reminders stopped", () => screenChecks("/app/family", "family-reminders-off", { tracks: 2 }));
test.describe("one profile", () => screenChecks(`/app/patients/${OWNER.patients[0]!.id}/profile`, "family-owner"));

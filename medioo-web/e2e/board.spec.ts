import { readFileSync } from "node:fs";
import { join } from "node:path";
import { test, type Page } from "@playwright/test";
import type { AskJson } from "../src/api/types";
import { t } from "../src/copy";
import { useScenario } from "./support/browser-api";
import { pngFor } from "./support/png.mjs";
import { FIXTURES, SCENARIOS, familyPatientId } from "./support/scenarios.mjs";

/*
 * The screenshot board (plan A7, Wave 3 Shared interfaces): each screen state at phone 390×844 and desktop 1280×800,
 * light and dark, beside the approved prototype scene it was built from. Runs only with BOARD=1 (the `board` project,
 * playwright.config.ts), into test-results/board/<screen>/, which git ignores; the folder is published privately and
 * linked from the PR. No screenshot goes into git.
 */

type Scene = {
  screen: string;
  state: string;
  route: string;
  scenario: string;
  /**
   * The prototype scene; `click`, a control of the prototype's own to press once the scene is on (its camera sheet);
   * `show`, an element of the prototype to show as its own script would (desktop.html's #drop while a file is dragged);
   * `act`, steps to take on the prototype when one click is not enough (the labs list, then its trend sheet).
   */
  proto: {
    file: "phone.html" | "desktop.html";
    scene: string;
    click?: string;
    show?: string;
    act?: (page: Page) => Promise<void>;
  };
  /** What to do on the page before the picture (type a number, press a button). */
  act?: (page: Page) => Promise<void>;
  /** The widths to take it at, when not the phone and desktop pair (the shell: 390, 1024 and 1440, plan W4). */
  viewports?: Record<string, { width: number; height: number }>;
  /** "catalog": the page exists only in the catalog build (/app/shell-test), served on the catalog server. */
  build?: "catalog";
  /**
   * Also the window as the page opens (`<state>-<layout>-<scheme>-fold.png`), beside the full page: where the page
   * opens scrolled, or docks a bar over its content (the chat page, W5), a full-page picture draws the docked bars
   * part way down and hides what a family sees first.
   */
  fold?: boolean;
  /** One element to take instead of the whole page: a component's catalog state (plan RANGE-web). */
  element?: string;
};

const number = async (page: Page) => {
  await page.locator('input[name="whatsapp_number"]').fill("01700000123");
  await page.getByRole("button", { name: t("bn", "login.next") }).click();
};
const code = async (page: Page) => {
  await page.locator('input[name="code"]').fill("123456");
  await page.getByRole("button", { name: t("bn", "login.enter") }).click();
};

export const BOARD_SCENES: Scene[] = [
  { screen: "signin", state: "welcome", route: "/app", scenario: "signed-out", proto: { file: "phone.html", scene: "welcome" } },
  {
    screen: "signin",
    state: "number",
    route: "/app",
    scenario: "signed-out",
    proto: { file: "desktop.html", scene: "signin" },
    act: async (page) => {
      await number(page);
      await page.locator("[data-number-back]").waitFor();
    },
  },
  {
    screen: "signin",
    state: "error",
    route: "/app",
    scenario: "signed-out-invalid",
    proto: { file: "phone.html", scene: "welcome" },
    act: async (page) => {
      await number(page);
      await page.locator('[aria-invalid="true"]').waitFor();
    },
  },
  { screen: "signin", state: "code-sent", route: "/app/verify", scenario: "state-pending-sent", proto: { file: "desktop.html", scene: "signin" } },
  {
    screen: "signin",
    state: "code-not-sent",
    route: "/app/verify",
    scenario: "state-pending-not-sent",
    proto: { file: "desktop.html", scene: "signin" },
  },
  {
    screen: "signin",
    state: "wrong-code",
    route: "/app/verify",
    scenario: "state-code-wrong",
    proto: { file: "desktop.html", scene: "signin" },
    act: async (page) => {
      await code(page);
      await page.locator('[aria-invalid="true"]').waitFor();
    },
  },
  {
    // Done: the right code pressed, the page leaving for the held place (held here, so the picture is of this page).
    screen: "signin",
    state: "done",
    route: "/app/verify",
    scenario: "state-pending-sent",
    proto: { file: "desktop.html", scene: "signin" },
    act: async (page) => {
      // A 204 to the navigation leaves this page where it is, the press done and the button still busy.
      await page.route("**/app/documents/**", (route) => route.fulfill({ status: 204 }));
      await code(page);
      await page.getByRole("button", { name: t("bn", "login.enter") }).and(page.locator(":disabled")).waitFor();
    },
  },
];

// The shell (plan W4): its layouts at the three widths that switch it, by patient count. 0, 1 and 2 patients; with two,
// the chooser (nobody remembered) and the switcher once one is asked for (the urgent mark in its sheet); the sidebar
// opened by the toolbar's button at 1024.
const SHELL_WIDTHS = {
  w390: { width: 390, height: 844 },
  w1024: { width: 1024, height: 800 },
  w1440: { width: 1440, height: 900 },
};
const TWO_SECOND = "5e9edf6d-0a2a-4913-8675-9d07a4100012";
const shell = (state: string, scenario: string, route = "/app/shell-test", act?: Scene["act"]): Scene => ({
  screen: "shell",
  state,
  route,
  scenario,
  build: "catalog",
  viewports: SHELL_WIDTHS,
  proto: { file: "desktop.html", scene: "app" },
  act,
});
BOARD_SCENES.push(
  { ...shell("one-patient", "me-one"), proto: { file: "phone.html", scene: "chat" } },
  shell("no-patients", "me-none"),
  shell("two-patients-chooser", "me-two"),
  shell("two-patients-chosen", "me-two", `/app/shell-test?patient=${TWO_SECOND}`),
  shell("two-patients-switcher-open", "me-two", `/app/shell-test?patient=${TWO_SECOND}`, async (page) => {
    await page.locator(".patient-switcher__button").click();
    await page.locator("dialog:modal").waitFor();
  }),
  shell("long-name", "me-long"),
  {
    ...shell("sidebar-open", "me-one", "/app/shell-test", async (page) => {
      await page.locator(".app-shell__menu").click();
      await page.locator(".app-shell__sidebar[data-open]").waitFor();
    }),
    viewports: { w1024: SHELL_WIDTHS.w1024 },
  },
);

// The add sheet (plan W7) on /app/add: the sheet, the chooser, the tray with three pages, the drop target (desktop),
// sending, slow, stuck with its retry, each refusal, a patient only to see, the cap reached. Beside phone.html scene
// `chat` with its camera sheet (or pages sheet) open, and desktop.html scene `app` with #drop showing.
const SHARED = "5e9edf6d-0a2a-4913-8675-9d07a4100012";
const REFUSAL_NAMES = Object.keys(
  (JSON.parse(readFileSync(join(__dirname, "..", "fixtures", "api", "uploads", "refusals.json"), "utf8")) as { body: object }).body,
).sort();
const threePages = async (page: Page) => {
  await page.locator("[data-upload-sheet][data-live]").waitFor();
  await page.locator('input[type=file][data-src="gallery"]').setInputFiles(
    [120, 200, 280].map((w, i) => ({ name: `page-${i + 1}.png`, mimeType: "image/png", buffer: pngFor({ crop: { src: `p${i}`, width: w, height: 160 } }) })),
  );
  await page.locator(".upload-tray__item").nth(2).waitFor();
};
const oneReport = async (page: Page) => page.getByRole("radio", { name: t("bn", "add.group.one") }).check();
const press = async (page: Page) => {
  await threePages(page);
  await oneReport(page);
  await page.getByRole("button", { name: t("bn", "add.send"), exact: true }).click();
};
/** The press never answered: the sheet stays on its way. */
const hold = async (page: Page) => page.route("**/app/api/uploads", () => {});
const upload = (state: string, scenario: string, extra: Partial<Scene> = {}): Scene => ({
  screen: "upload",
  state,
  route: "/app/add",
  scenario,
  proto: { file: "phone.html", scene: "chat", click: '[data-action="camera"]' },
  ...extra,
});
BOARD_SCENES.push(
  upload("sheet", "upload-owner-one"),
  upload("chooser", "upload-owner-two"),
  upload("tray-three-pages", "upload-owner-one", { act: threePages, proto: { file: "phone.html", scene: "chat", click: '[data-action="pages"]' } }),
  upload("upload-drop", "upload-owner-one", {
    viewports: { desktop: { width: 1280, height: 800 } },
    proto: { file: "desktop.html", scene: "app", show: "#drop" },
    act: async (page) => {
      await page.locator("[data-upload-sheet][data-live]").waitFor();
      const transfer = await page.evaluateHandle(() => {
        const dt = new DataTransfer();
        dt.items.add(new File([new Uint8Array([0xff, 0xd8])], "drop.jpg", { type: "image/jpeg" }));
        return dt;
      });
      await page.dispatchEvent("body", "dragenter", { dataTransfer: transfer });
      await page.locator("#drop").waitFor();
    },
  }),
  upload("sending", "upload-owner-one", {
    act: async (page) => {
      await hold(page);
      await press(page);
      await page.locator('.upload-progress[data-phase="sending"]').waitFor();
    },
  }),
  upload("slow", "upload-owner-one", {
    act: async (page) => {
      await hold(page);
      await press(page);
      await page.getByText(t("bn", "up.slow")).waitFor({ timeout: 20_000 });
    },
  }),
  upload("stuck-retry", "upload-stuck", {
    act: async (page) => {
      await press(page);
      await page.getByRole("button", { name: t("bn", "up.retry") }).waitFor();
    },
  }),
  ...REFUSAL_NAMES.map((name) =>
    upload(`refused-${name}`, `upload-refused-${name}`, {
      act: async (page) => {
        await press(page);
        await page.locator(".upload-sheet__alert:not(:empty)").waitFor();
      },
    }),
  ),
  upload("view-only", "upload-view-only", { route: `/app/add?patient=${SHARED}` }),
  upload("cap-reached", "upload-cap-reached"),
);

/**
 * A press of the composer (plan CHAT-web) with the question Java answered in the scenario's ask fixture (a fixed line for
 * a press that is cut or held): Java's answer drawn in place (`answered`), or only the pending bubble (a held request).
 */
const asking =
  (scenario: string, answered = true): Scene["act"] =>
  async (page) => {
    const value = SCENARIOS[scenario]?.["POST /app/api/patients/{id}/ask"];
    const question =
      typeof value === "string"
        ? (JSON.parse(readFileSync(join(FIXTURES, value), "utf8")) as { body: AskJson }).body.question
        : "Is this value all right?";
    await page.locator(".composer__field").fill(question);
    await page.locator(".composer__send").click();
    const recv = page.locator(".composer__lines .message-bubble--recv .message-bubble__body").last();
    await recv.waitFor();
    if (answered) await page.locator(".composer__send:enabled").waitFor();
  };

// The chat page (plan W5): default, NOW, retake and first run beside phone.html's scenes, and the attention stack in the
// conversation column and the first run beside desktop.html's.
const chat = (state: string, scenario: string, file: "phone.html" | "desktop.html", scene: string, act?: Scene["act"]): Scene => ({
  screen: "chat",
  state,
  route: "/app/home",
  scenario,
  proto: { file, scene },
  act,
  fold: true,
});
BOARD_SCENES.push(
  chat("default", "chat-default", "phone.html", "chat"),
  chat("now", "chat-now", "phone.html", "now"),
  chat("now-abroad", "chat-now-abroad", "phone.html", "now"),
  chat("retake", "chat-retake", "phone.html", "retake"),
  chat("first", "chat-first", "phone.html", "first"),
  chat("stack", "chat-stack", "desktop.html", "app"),
  chat("first-desktop", "chat-first", "desktop.html", "first"),
  // The composer asks (plan CHAT-web, PO ruling P7): each answer Java gives, drawn in place after a press; the pending
  // bubble while Java answers; the reply when the press dies; and the stored questions and answers after a reload.
  chat("asked-pending", "chat-ask-held", "phone.html", "chat", asking("chat-ask-held", false)),
  chat("asked-answer", "chat-ask-answer", "phone.html", "chat", asking("chat-ask-answer")),
  chat("asked-answer-desktop", "chat-ask-answer", "desktop.html", "app", asking("chat-ask-answer")),
  chat("asked-dose", "chat-ask-dose", "phone.html", "chat", asking("chat-ask-dose")),
  chat("asked-emergency", "chat-ask-emergency", "phone.html", "chat", asking("chat-ask-emergency")),
  chat("asked-emergency-abroad", "chat-ask-emergency-abroad", "phone.html", "chat", asking("chat-ask-emergency-abroad")),
  chat("asked-limit", "chat-ask-limit", "phone.html", "chat", asking("chat-ask-limit")),
  chat("asked-fallback", "chat-ask-fallback", "phone.html", "chat", asking("chat-ask-fallback")),
  chat("asked-declined", "chat-ask-declined", "phone.html", "chat", asking("chat-ask-declined")),
  chat("asked-offline", "chat-ask-offline", "phone.html", "chat", asking("chat-ask-offline")),
  chat("with-asks", "chat-with-asks", "phone.html", "chat"),
  // A NOW result while the patient shown is a first-run one: the banner is global, Mio's introduction stays still.
  {
    ...chat("first-now", "chat-first-now", "phone.html", "first"),
    route: `/app/home?patient=${TWO_SECOND}`,
  },
  // A NOW result past the thread's first page: the stack's NOW card, its not-a-doctor line, and the disclaimer.
  chat("now-past-the-page", "chat-now-past-the-page", "phone.html", "now"),
);

// family and settings (plan W10): the owner, the share member, reminders stopped, a typed flag (the fixture's own and
// one being typed), the theme chosen light and dark, and one patient's profile page, beside phone.html `family`/`theme`.
const family = (state: string, scenario: string, extra: Partial<Scene> = {}): Scene => ({
  screen: "family",
  state,
  route: "/app/family",
  scenario,
  proto: { file: "phone.html", scene: "family" },
  ...extra,
});
const chooseTheme = (name: "theme.light" | "theme.dark", value: string) => async (page: Page) => {
  await page.getByRole("button", { name: t("bn", name) }).click();
  await page.locator(`html[data-theme="${value}"]`).waitFor({ state: "attached" });
};
BOARD_SCENES.push(
  family("owner", "family-owner"),
  family("share-member", "family-share-member"),
  family("reminders-off", "family-reminders-off"),
  family("typed-flag", "family-owner", {
    act: async (page) => {
      const field = page.locator(".family-flags__input").first();
      await field.fill("migraine");
      await field.scrollIntoViewIfNeeded();
    },
  }),
  // The W10 review's states: a typed flag saved (Java's stored list, ticked, and the toast), the family stop's confirm
  // (nothing sent before it), a VIEW_ONLY refusal said in words, and the longest name on the cards, owned and shared.
  family("typed-flag-saved", "family-owner", {
    act: async (page) => {
      const card = page.locator("[data-patient-card]").first();
      await card.locator(".family-flags__input").fill("  Migraine ");
      await card.getByRole("button", { name: t("bn", "profile.save") }).click();
      await card.getByRole("checkbox", { name: "migraine", exact: true }).waitFor({ state: "attached" });
      await card.locator(".family-flags__save").scrollIntoViewIfNeeded();
    },
  }),
  family("stop-confirm", "family-owner", {
    act: async (page) => {
      await page.locator("[data-family-reminders]").getByRole("button", { name: t("bn", "optout.family") }).click();
      await page.locator("dialog:modal").waitFor();
    },
  }),
  family("view-only-refused", "family-owner-view-only", {
    act: async (page) => {
      const card = page.locator("[data-patient-card]").first();
      await card.getByRole("checkbox", { name: t("bn", "flag.kidney") }).check();
      await card.getByRole("button", { name: t("bn", "profile.save") }).click();
      await card.locator(".family-flags__status").filter({ hasText: t("bn", "app.err.forbidden") }).waitFor();
      await card.locator(".family-flags__save").scrollIntoViewIfNeeded();
    },
  }),
  family("long-name", "family-owner-long"),
  family("share-member-long", "family-share-member-long"),
  family("theme-light", "family-owner", { proto: { file: "phone.html", scene: "theme" }, act: chooseTheme("theme.light", "light") }),
  family("theme-dark", "family-owner", { proto: { file: "phone.html", scene: "theme" }, act: chooseTheme("theme.dark", "dark") }),
  // The second patient's profile while the first is remembered: the toolbar names the patient the link names.
  family("profile", "family-owner", { route: `/app/patients/${familyPatientId("family/owner.json", 1)}/profile` }),
);

// The health tab (plan W9): lab values, none yet, one name in two units, blood pressure, a marker read once, the latest
// prescription's lines, the newest one read with nothing on it, a newer one still being read, and no prescription yet,
// beside phone.html `health` with its labs list or its medicines sheet open (the prototype's own controls).
const protoOpens = (action: "labs" | "meds") => async (proto: Page) => {
  await proto.locator(`[data-action="${action}"]`).first().click();
};
const MEDICINES = "section[aria-labelledby=health-medicines]";
const health = (state: string, scenario: string, proto: "labs" | "meds", element?: string): Scene => ({
  screen: "health",
  state,
  route: "/app/health",
  scenario,
  proto: { file: "phone.html", scene: "health", act: protoOpens(proto) },
  element,
});
BOARD_SCENES.push(
  health("labs-some", "health-some", "labs"),
  health("labs-none", "health-none", "labs"),
  health("two-units", "health-two-units", "labs"),
  health("blood-pressure", "health-bp", "labs"),
  health("readings-as-bounds", "health-bounds", "labs"),
  health("one-reading", "health-some", "labs", "[data-lab-series][data-canonical=potassium]"),
  health("medicines-latest", "health-some", "meds", MEDICINES),
  health("latest-with-none", "health-empty-latest", "meds", MEDICINES),
  health("newer-unread", "health-newer-unread", "meds", MEDICINES),
  health("no-prescription", "health-none", "meds", MEDICINES),
  health("long-name", "health-long", "labs"),
  // Review of PR #160: a pair whose halves are judged apart, and a family's first prescription still being read.
  health("blood-pressure-flagged", "health-bp-flagged", "labs", "[data-bp]"),
  health("first-prescription-being-read", "health-first-unread", "meds", MEDICINES),
);

// TrendChart's catalog states (plan RANGE-web, PO ruling R3): each one alone, in each language, beside the approved
// prototype's HbA1c trend sheet. The catalog's Sheet/open states open as modals on hydration; they are closed first so
// their scrim does not grey the chart.
const TREND_STATES = [
  "rising",
  "with-unknown",
  "critical-low",
  "two-ranges",
  "one-sided-high",
  "one-sided-low",
  "range-withheld",
  "unknown-beside-range",
];
const closeCatalogSheets = async (page: Page) => {
  await page
    .waitForFunction(() => document.querySelectorAll("dialog[open]").length === 2, null, { timeout: 10_000 })
    .catch(() => undefined);
  await page.evaluate(() => document.querySelectorAll("dialog").forEach((d) => d.close()));
};
const trendSheet = async (proto: Page) => {
  await proto.locator('[data-action="labs"]').first().click();
  await proto.locator('[data-action="trend"]').first().click();
};
for (const state of TREND_STATES) {
  for (const lang of ["bn", "en"]) {
    BOARD_SCENES.push({
      screen: "trend-range",
      state: `${state}-${lang}`,
      route: "/app/catalog-test",
      scenario: "me-one",
      build: "catalog",
      element: `section[data-catalog-state="TrendChart/${state}"][lang="${lang}"]`,
      proto: { file: "phone.html", scene: "health", act: trendSheet },
      act: closeCatalogSheets,
    });
  }
}

// The papers page (plan W8), beside phone.html scene `papers`: no paper, one month, a busy month (the strip, the
// doctor's dates due and overdue, the calendar, two pages), forty-five papers, the forty-five under the longest name,
// the lab filter, and a share member's empty file (no send link); after review, papers still being read beside read
// ones (the counts line, a paper under review), a filter that matches nothing, a share member's busy file, and a
// patient whose chronic flags are set (no BMX-8 ask).
const papers = (state: string, scenario: string, route = "/app/timeline"): Scene => ({
  screen: "papers",
  state,
  route,
  scenario,
  proto: { file: "phone.html", scene: "papers" },
});
BOARD_SCENES.push(
  papers("empty", "papers-empty"),
  papers("one-month", "papers-one-month"),
  papers("busy-month", "papers-busy"),
  papers("forty-five", "papers-forty-five"),
  papers("forty-five-long-name", "papers-forty-five-long"),
  papers("filter-lab", "papers-busy", "/app/timeline?type=lab"),
  papers("empty-share-member", "papers-empty-member", "/app/timeline?patient=5e9edf6d-0a2a-4913-8675-9d07a4100012"),
  papers("being-read", "papers-in-flight"),
  papers("filter-none", "papers-filter-none", "/app/timeline?type=rx"),
  papers("share-member", "papers-member", "/app/timeline?patient=5e9edf6d-0a2a-4913-8675-9d07a4100012"),
  papers("flags-set", "papers-flags-set"),
);

const VIEWPORTS = { phone: { width: 390, height: 844 }, desktop: { width: 1280, height: 800 } } as const;
// The catalog build's server (playwright.config.ts starts it on the port after the production one).
const CATALOG_URL = process.env.E2E_CATALOG_URL ?? `http://127.0.0.1:${Number(process.env.E2E_PORT ?? 3217) + 1}`;
const PROTOTYPES = join(__dirname, "..", "..", "docs", "design", "2026-10-21-approved");
const OUT = join(__dirname, "..", "test-results", "board");

for (const scene of BOARD_SCENES) {
  for (const [layout, viewport] of Object.entries(scene.viewports ?? VIEWPORTS)) {
    for (const colorScheme of ["light", "dark"] as const) {
      test(`${scene.screen} ${scene.state} ${layout} ${colorScheme}`, async ({ page }) => {
        test.setTimeout(90_000);
        await page.setViewportSize(viewport);
        await page.emulateMedia({ colorScheme });
        await useScenario(page, scene.scenario);
        await page.goto(scene.build === "catalog" ? new URL(scene.route, CATALOG_URL).href : scene.route);
        await page.evaluate(() => document.fonts.ready.then(() => undefined));
        if (scene.act) await scene.act(page);
        if (scene.fold) {
          await page.waitForLoadState("load");
          await page.screenshot({ path: join(OUT, scene.screen, `${scene.state}-${layout}-${colorScheme}-fold.png`) });
        }
        const shot = join(OUT, scene.screen, `${scene.state}-${layout}-${colorScheme}.png`);
        if (scene.element) await page.locator(scene.element).screenshot({ path: shot });
        else await page.screenshot({ path: shot, fullPage: true });

        const proto = await page.context().newPage();
        await proto.setViewportSize(viewport);
        await proto.emulateMedia({ colorScheme });
        await proto.goto(`file://${join(PROTOTYPES, scene.proto.file)}`);
        // The scene bar can be hidden at a width the prototype was not drawn for; the click is the prototype's own.
        await proto.evaluate((s) => document.querySelector<HTMLElement>(`[data-scene="${s}"]`)?.click(), scene.proto.scene);
        await proto.waitForTimeout(600);
        if (scene.proto.click) {
          await proto.evaluate((s) => document.querySelector<HTMLElement>(s)?.click(), scene.proto.click);
          await proto.waitForTimeout(600);
        }
        if (scene.proto.show) await proto.evaluate((s) => document.querySelector(s)?.classList.add("on"), scene.proto.show);
        if (scene.proto.act) {
          await scene.proto.act(proto);
          await proto.waitForTimeout(600);
        }
        await proto.screenshot({ path: join(OUT, scene.screen, `${scene.state}-${layout}-${colorScheme}-prototype.png`) });
        await proto.close();
      });
    }
  }
}

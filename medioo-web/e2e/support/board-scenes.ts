import { readFileSync } from "node:fs";
import { join } from "node:path";
import type { Page } from "@playwright/test";
import { MAX_CHECKS, RELOAD_SECONDS, waitBefore } from "../../src/api/reading-check";
import type { AskJson } from "../../src/api/types";
import { t } from "../../src/copy";
import { pngFor } from "./png.mjs";
import { FIXTURES, PROTO_PAPERS, PROTO_PATIENTS, SCENARIOS, familyPatientId } from "./scenarios.mjs";

/*
 * The screenshot board's scenes (plan A7; board.spec.ts takes them). Kept apart from the spec, which registers
 * Playwright tests as it loads, so Vitest can judge the scene table itself (board-scenes.test.ts).
 */

/** A prototype scene and what to do on it before its picture. */
export type ProtoScene = {
  scene: string;
  click?: string;
  show?: string;
  act?: (page: Page) => Promise<void>;
};

/** The width from which Medioo is the desktop layout, and the board compares with desktop.html (plan 2026-11-07). */
export const DESKTOP_MIN_WIDTH = 900;

export type Scene = {
  screen: string;
  state: string;
  route: string;
  scenario: string;
  /**
   * The prototype scene; `click`, a control of the prototype's own to press once the scene is on (its camera sheet);
   * `show`, an element of the prototype to show as its own script would (desktop.html's #drop while a file is dragged);
   * `act`, steps to take on the prototype when one click is not enough (the labs list, then its trend sheet).
   * `desktop`, the desktop.html scene a capture at DESKTOP_MIN_WIDTH or wider is compared with, where `file` is
   * phone.html (protoFor).
   */
  proto: { file: "phone.html" | "desktop.html"; desktop?: ProtoScene } & ProtoScene;
  /** What to do on the page before the picture (type a number, press a button). */
  act?: (page: Page) => Promise<void>;
  /** Before the page is opened: the page's clock, made Playwright's, so `act` can move the page's time (PO ruling P6). */
  clock?: boolean;
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

/**
 * The prototype a capture `width` wide is compared with: desktop.html's scene from DESKTOP_MIN_WIDTH up (the scene's
 * own when it is already desktop.html, else its `desktop`), the scene's own below it. A scene with no desktop.html
 * counterpart answers phone.html here, which board-scenes.test.ts refuses for every capture at desktop width.
 */
export function protoFor(scene: Scene, width: number): { file: "phone.html" | "desktop.html" } & ProtoScene {
  const { desktop, ...own } = scene.proto;
  if (width >= DESKTOP_MIN_WIDTH && own.file !== "desktop.html" && desktop) return { file: "desktop.html", ...desktop };
  return own;
}

// desktop.html's own controls, pressed as a click would (the sidebar folds at some widths; the press is the
// prototype's). Its `app` scene opens on amma (the mother) and her blood test (cbc); `first` has no paper for anyone.
// A control the prototype does not have fails the capture: a picture of the wrong view would pass for the right one.
const pressAll = async (proto: Page, selectors: string[]) => {
  const missing = await proto.evaluate((all) => {
    for (const s of all) {
      const control = document.querySelector<HTMLElement>(s);
      if (!control) return s;
      control.click();
    }
    return null;
  }, selectors);
  if (missing) throw new Error(`desktop.html has no ${missing}`);
};
/** The sidebar's view: chat, papers (the calendar), labs, meds or settings. */
const deskView = (view: string, ...then: string[]) => async (proto: Page) =>
  pressAll(proto, [`.side .si[data-view="${view}"]`, ...then]);
/** One of amma's papers in the strip: lipid, creat, rx, cbc or blur (the retake). */
const deskPaper = (id: string) => async (proto: Page) => pressAll(proto, [`.film [data-pick="${id}"]`]);
/** abbu's (the father's) NOW paper (potas), opened from the NOW bar's own "see the paper" button. */
const deskNow = async (proto: Page) => pressAll(proto, ['[data-goto="abbu:potas"]']);
/**
 * The add dialog: the paperclip, then the file picker's answer. With `files`, the picker returns them (the tray);
 * without, it is cancelled and the prototype opens its own dialog with sample pages, as it does when a browser
 * refuses the picker.
 */
const deskUpload =
  (files?: { name: string; mimeType: string; buffer: Buffer }[]) =>
  async (proto: Page): Promise<void> => {
    if (!files) {
      await pressAll(proto, ['.composer [data-action="upload"]']);
      await proto.evaluate(() => document.getElementById("filein")?.dispatchEvent(new Event("cancel")));
      await proto.locator(".dlg.on").waitFor();
      return;
    }
    const [chooser] = await Promise.all([proto.waitForEvent("filechooser"), proto.locator('.composer [data-action="upload"]').click()]);
    await chooser.setFiles(files);
    await proto.locator(".dlg.on").waitFor();
  };
const DESK_CHAT: ProtoScene = { scene: "app", act: deskPaper("cbc") };
const DESK_FIRST: ProtoScene = { scene: "first" };
/** Family and account: desktop.html's settings view, from the sidebar's account row. */
const DESK_SETTINGS: ProtoScene = { scene: "app", act: deskView("settings") };
/** desktop.html's counterpart of a phone.html conversation scene: the same kind of paper, open beside the thread. */
const DESK_FOR: Record<string, ProtoScene> = {
  chat: DESK_CHAT,
  now: { scene: "app", act: deskNow },
  retake: { scene: "app", act: deskPaper("blur") },
  first: DESK_FIRST,
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
  { screen: "signin", state: "welcome", route: "/app", scenario: "signed-out", proto: { file: "phone.html", scene: "welcome", desktop: { scene: "signin" } } },
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
    proto: { file: "phone.html", scene: "welcome", desktop: { scene: "signin" } },
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
  { ...shell("one-patient", "me-one"), proto: { file: "phone.html", scene: "chat", desktop: DESK_CHAT } },
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
  (JSON.parse(readFileSync(join(FIXTURES, "uploads", "refusals.json"), "utf8")) as { body: object }).body,
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
  proto: { file: "phone.html", scene: "chat", click: '[data-action="camera"]', desktop: { scene: "app", act: deskUpload() } },
  ...extra,
});
BOARD_SCENES.push(
  upload("sheet", "upload-owner-one"),
  upload("chooser", "upload-owner-two"),
  upload("tray-three-pages", "upload-owner-one", {
    act: threePages,
    proto: {
      file: "phone.html",
      scene: "chat",
      click: '[data-action="pages"]',
      desktop: {
        scene: "app",
        act: deskUpload([120, 200, 280].map((w, i) => ({ name: `page-${i + 1}.png`, mimeType: "image/png", buffer: pngFor({ crop: { src: `p${i}`, width: w, height: 160 } }) }))),
      },
    },
  }),
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
    if (answered) await page.locator('.composer__send:not([aria-disabled="true"])').waitFor();
  };

// The chat page (plan W5): default, NOW, retake and first run beside phone.html's scenes, and the attention stack in the
// conversation column and the first run beside desktop.html's.
const chat = (
  state: string,
  scenario: string,
  file: "phone.html" | "desktop.html",
  scene: string,
  act?: Scene["act"],
  desktop: ProtoScene | undefined = DESK_FOR[scene],
): Scene => ({
  screen: "chat",
  state,
  route: "/app/home",
  scenario,
  proto: file === "phone.html" ? { file, scene, desktop } : { file, scene },
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
  chat("asked-dose", "chat-ask-dose", "phone.html", "chat", asking("chat-ask-dose"), { scene: "app", act: deskPaper("rx") }),
  chat("asked-emergency", "chat-ask-emergency", "phone.html", "chat", asking("chat-ask-emergency")),
  chat("asked-emergency-abroad", "chat-ask-emergency-abroad", "phone.html", "chat", asking("chat-ask-emergency-abroad")),
  chat("asked-limit", "chat-ask-limit", "phone.html", "chat", asking("chat-ask-limit")),
  chat("asked-fallback", "chat-ask-fallback", "phone.html", "chat", asking("chat-ask-fallback")),
  chat("asked-declined", "chat-ask-declined", "phone.html", "chat", asking("chat-ask-declined")),
  chat("asked-offline", "chat-ask-offline", "phone.html", "chat", asking("chat-ask-offline")),
  chat("with-asks", "chat-with-asks", "phone.html", "chat"),
  // Review of PR 166: the long answer read from its first line, the session that ended under a question, a stored dose
  // answer with its line's crop, and a first-run patient who asked before the first paper.
  chat("asked-long", "chat-ask-long", "phone.html", "chat", asking("chat-ask-long")),
  chat("asked-signed-out", "chat-ask-401", "phone.html", "chat", asking("chat-ask-401")),
  chat("with-asks-dose", "chat-with-asks-dose", "phone.html", "chat", undefined, { scene: "app", act: deskPaper("rx") }),
  chat("first-with-asks", "chat-first-with-asks", "phone.html", "first"),
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
  proto: { file: "phone.html", scene: "family", desktop: DESK_SETTINGS },
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
  family("theme-light", "family-owner", { proto: { file: "phone.html", scene: "theme", desktop: DESK_SETTINGS }, act: chooseTheme("theme.light", "light") }),
  family("theme-dark", "family-owner", { proto: { file: "phone.html", scene: "theme", desktop: DESK_SETTINGS }, act: chooseTheme("theme.dark", "dark") }),
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
// On desktop.html the same lists are the sidebar's labs and meds views (`first` where the family has none yet);
// a single marker is its row in the labs list.
const health = (
  state: string,
  scenario: string,
  proto: "labs" | "meds",
  element?: string,
  desktop: ProtoScene = { scene: "app", act: deskView(proto) },
): Scene => ({
  screen: "health",
  state,
  route: "/app/health",
  scenario,
  proto: { file: "phone.html", scene: "health", act: protoOpens(proto), desktop },
  element,
});
BOARD_SCENES.push(
  health("labs-some", "health-some", "labs"),
  health("labs-none", "health-none", "labs", undefined, { scene: "first", act: deskView("labs") }),
  health("two-units", "health-two-units", "labs"),
  health("blood-pressure", "health-bp", "labs"),
  health("readings-as-bounds", "health-bounds", "labs"),
  health("one-reading", "health-some", "labs", "[data-lab-series][data-canonical=potassium]", {
    scene: "app",
    act: deskView("labs", '[data-lab="Serum Potassium"]'),
  }),
  health("medicines-latest", "health-some", "meds", MEDICINES),
  health("latest-with-none", "health-empty-latest", "meds", MEDICINES),
  health("newer-unread", "health-newer-unread", "meds", MEDICINES),
  health("no-prescription", "health-none", "meds", MEDICINES, { scene: "first", act: deskView("meds") }),
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
      proto: { file: "phone.html", scene: "health", act: trendSheet, desktop: { scene: "app", act: deskView("labs", '[data-lab="HbA1c"]') } },
      act: closeCatalogSheets,
    });
  }
}

// The papers page (plan W8), beside phone.html scene `papers`: no paper, one month, a busy month (the strip, the
// doctor's dates due and overdue, the calendar, two pages), forty-five papers, the forty-five under the longest name,
// the lab filter, and a share member's empty file (no send link); after review, papers still being read beside read
// ones (the counts line, a paper under review), a filter that matches nothing, a share member's busy file, and a
// patient whose chronic flags are set (no BMX-8 ask).
// On desktop.html the papers are the sidebar's calendar (`first` where the patient has none).
const DESK_PAPERS: ProtoScene = { scene: "app", act: deskView("papers") };
const DESK_NO_PAPERS: ProtoScene = { scene: "first", act: deskView("papers") };
const papers = (state: string, scenario: string, route = "/app/timeline", desktop = DESK_PAPERS): Scene => ({
  screen: "papers",
  state,
  route,
  scenario,
  proto: { file: "phone.html", scene: "papers", desktop },
});
BOARD_SCENES.push(
  papers("empty", "papers-empty", undefined, DESK_NO_PAPERS),
  papers("one-month", "papers-one-month"),
  papers("busy-month", "papers-busy"),
  papers("forty-five", "papers-forty-five"),
  papers("forty-five-long-name", "papers-forty-five-long"),
  papers("filter-lab", "papers-busy", "/app/timeline?type=lab"),
  papers("empty-share-member", "papers-empty-member", "/app/timeline?patient=5e9edf6d-0a2a-4913-8675-9d07a4100012", DESK_NO_PAPERS),
  papers("being-read", "papers-in-flight"),
  papers("filter-none", "papers-filter-none", "/app/timeline?type=rx"),
  papers("share-member", "papers-member", "/app/timeline?patient=5e9edf6d-0a2a-4913-8675-9d07a4100012"),
  papers("flags-set", "papers-flags-set"),
);

// The papers page's quiet check (PO ruling P6), its own board: two papers being read with JavaScript on (no reload, an
// empty live region); the same page re-rendered in place once Java has read them (the region says so, unseen); and the
// check stopped after its cap, offering the plain link to this page.
const refresh = (state: string, act?: (page: Page) => Promise<void>): Scene => ({
  screen: "papers-refresh",
  state,
  route: "/app/timeline",
  scenario: "papers-in-flight",
  proto: { file: "phone.html", scene: "papers", desktop: DESK_PAPERS },
  clock: true,
  act,
});
BOARD_SCENES.push(
  refresh("being-read"),
  refresh("read-in-place", async (page) => {
    const url = new URL(page.url());
    await page.context().addCookies([{ name: "e2e_scenario", value: "papers-settled", domain: url.hostname, path: "/app" }]);
    await page.clock.fastForward(RELOAD_SECONDS * 1000);
    await page.locator(".papers [role='status']").filter({ hasText: t("bn", "status.DONE") }).waitFor({ state: "attached" });
    await page.locator(".month-list", { hasNotText: t("bn", "timeline.reading") }).waitFor();
  }),
  refresh("checks-stopped", async (page) => {
    const again = page.getByRole("link", { name: t("bn", "attn.reading.action") });
    for (let i = 0; i < MAX_CHECKS && (await again.count()) === 0; i++) {
      await page.clock.fastForward(waitBefore(i));
      // Each check's answers land before the next wait is moved through.
      await page.waitForTimeout(150);
    }
    await again.waitFor();
  }),
);

// The paper (plan W6, master W6's board): every gate state, NOW at home and abroad, the third retake, failed, being
// read, the multi-page /all, a value's sheet, the delete confirm, and gone. Beside phone.html's `now` and `retake`, and
// desktop.html's `app` (the paper column).
const PAPER_PATH = "/app/documents/5e9edf6d-0a2a-4913-8675-9d07a4100003";
const paperScene = (state: string, scenario: string, scene: string, route = PAPER_PATH, act?: Scene["act"]): Scene => ({
  screen: "paper",
  state,
  route,
  scenario,
  proto: scene === "app" ? { file: "desktop.html", scene } : { file: "phone.html", scene, desktop: DESK_FOR[scene] },
  act,
});
BOARD_SCENES.push(
  paperScene("released", "paper-routine", "app"),
  paperScene("pending", "paper-pending", "app"),
  paperScene("withheld-rejected", "paper-withheld-rejected", "app"),
  paperScene("withheld-failed-safety", "paper-failed-safety", "app"),
  paperScene("now", "paper-now-bd", "now"),
  paperScene("now-abroad", "paper-now-abroad", "now"),
  paperScene("retake-third", "paper-retake-third", "retake"),
  paperScene("failed", "paper-failed", "retake"),
  paperScene("reading", "paper-processing", "app"),
  paperScene("all-multipage", "paper-58", "app", `${PAPER_PATH}/all`),
  paperScene("value-sheet", "sheet-earlier-three", "app", `${PAPER_PATH}/values/7af69ec6-676f-3bb9-b0bb-3e665c36ed57`),
  paperScene("delete-confirm", "paper-routine", "app", PAPER_PATH, async (page) => {
    await page.getByRole("button", { name: t("bn", "detail.delete") }).click();
    await page.locator("dialog:modal").waitFor();
  }),
  // After review: a delete whose token Java refused, said inside the open sheet.
  paperScene("delete-refused", "paper-delete-refused", "app", PAPER_PATH, async (page) => {
    await page.getByRole("button", { name: t("bn", "detail.delete") }).click();
    await page.getByRole("button", { name: t("bn", "doc.delete.yes") }).click();
    await page.locator("dialog:modal [role=status]:not(:empty)").waitFor();
  }),
  paperScene("gone", "paper-gone", "app"),
);

// The desktop layout, Phase 1 (plan desktop-parity Task 9): talk-and-papers (desk.nav.talk) on preview pages (/app/preview/..., the only
// requests it renders for until the flip) over desktop.html's pretend family (the `proto` scenarios), each capture beside
// desktop.html's own scene with the same patient and the same paper: the mother's five papers one by one, the father's
// NOW paper (the bar, the red card), the NOW paper from a link with its panel, a panel over the conversation, the bar
// seen, the longest name over the longest strip, and a first visit; at 1440 and 1280, and the narrow desktop (1024 and
// 900, the sidebar behind its button) for the main states.
const DESK_WIDE = { w1440: { width: 1440, height: 900 }, w1280: { width: 1280, height: 800 } };
const DESK_NARROW = { w1024: { width: 1024, height: 768 }, w900: { width: 900, height: 800 } };
const AMMA = PROTO_PATIENTS.amma;
const ABBU = PROTO_PATIENTS.abbu;
/** desktop.html's NOW bar's "seen" for the father's potassium, once the bar is on (the prototype's own control). */
const deskSeen = async (proto: Page) => {
  await deskNow(proto);
  await proto.waitForTimeout(300);
  await pressAll(proto, ['[data-seen="abbu:potas"]']);
};
/** desktop.html's sidebar, opened by its own menu button (900 to 1199 px). */
const deskSide = (then?: (proto: Page) => Promise<void>) => async (proto: Page) => {
  if (then) await then(proto);
  await proto.waitForTimeout(300);
  await pressAll(proto, ['.menu-btn[data-action="openside"]']);
};
const desk = (state: string, scenario: string, route: string, proto: ProtoScene, extra: Partial<Scene> = {}): Scene => ({
  screen: "desk",
  state,
  route,
  scenario,
  viewports: DESK_WIDE,
  proto: { file: "desktop.html", ...proto },
  ...extra,
});
const deskHome = (query: string) => `/app/preview/home?${query}`;
const seeBarGo = async (page: Page) => {
  await page.locator(".desktop-shell .now-banner--bar").getByRole("button", { name: t("bn", "now.banner.seen") }).click();
  await page.locator(".desktop-shell .now-banner--bar").waitFor({ state: "detached" });
};
BOARD_SCENES.push(
  desk("amma-newest-retake", "proto", deskHome(`patient=${AMMA}`), { scene: "app", act: deskPaper("blur") }),
  desk("amma-cbc", "proto", deskHome(`patient=${AMMA}&paper=${PROTO_PAPERS.cbc}`), DESK_CHAT),
  desk("amma-creat-this-week", "proto", deskHome(`patient=${AMMA}&paper=${PROTO_PAPERS.creat}`), { scene: "app", act: deskPaper("creat") }),
  desk("amma-rx", "proto", deskHome(`patient=${AMMA}&paper=${PROTO_PAPERS.rx}`), { scene: "app", act: deskPaper("rx") }),
  desk("amma-lipid", "proto", deskHome(`patient=${AMMA}&paper=${PROTO_PAPERS.lipid}`), { scene: "app", act: deskPaper("lipid") }),
  desk("abbu-now", "proto", deskHome(`patient=${ABBU}`), { scene: "app", act: deskNow }),
  desk("abbu-sugar", "proto", deskHome(`patient=${ABBU}&paper=${PROTO_PAPERS.sugar}`), {
    scene: "app",
    act: async (proto) => {
      await deskNow(proto);
      await proto.waitForTimeout(300);
      await pressAll(proto, ['.film [data-pick="sugar"]']);
    },
  }),
  desk("now-link-panel", "proto", `/app/preview/documents/${PROTO_PAPERS.potas}`, { scene: "app", act: deskNow }),
  desk("amma-cbc-panel", "proto", deskHome(`patient=${AMMA}&paper=${PROTO_PAPERS.cbc}&panel=1`), DESK_CHAT),
  desk("abbu-now-seen", "proto", deskHome(`patient=${ABBU}`), { scene: "app", act: deskSeen }, { act: seeBarGo }),
  desk("long-name-long-strip", "proto-long", "/app/preview/home", DESK_CHAT),
  desk("first-visit", "desk-first", "/app/preview/home", DESK_FIRST),
  desk("narrow-amma-cbc", "proto", deskHome(`patient=${AMMA}&paper=${PROTO_PAPERS.cbc}`), DESK_CHAT, { viewports: DESK_NARROW }),
  desk("narrow-abbu-now", "proto", deskHome(`patient=${ABBU}`), { scene: "app", act: deskNow }, { viewports: DESK_NARROW }),
  desk("narrow-now-link-panel", "proto", `/app/preview/documents/${PROTO_PAPERS.potas}`, { scene: "app", act: deskNow }, { viewports: DESK_NARROW }),
  desk(
    "narrow-sidebar-open",
    "proto",
    deskHome(`patient=${AMMA}&paper=${PROTO_PAPERS.cbc}`),
    { scene: "app", act: deskSide(deskPaper("cbc")) },
    {
      viewports: { w1024: DESK_NARROW.w1024 },
      act: async (page) => {
        await page.locator(".desktop-shell__menu").click();
        await page.locator(".desktop-shell__sidebar[data-open]").waitFor();
      },
    },
  ),
);

export const VIEWPORTS = { phone: { width: 390, height: 844 }, desktop: { width: 1280, height: 800 } } as const;

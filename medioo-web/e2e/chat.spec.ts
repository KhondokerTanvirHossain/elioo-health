import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test, type Locator, type Page } from "@playwright/test";
import type { AskJson, AttentionJson, MeJson, NowBannerJson, ThreadJson } from "../src/api/types";
import { t, type CopyKey } from "../src/copy";
import { NEXT_PAGES, signInPath } from "../src/routing/paths";
import { useScenario, type CropRow } from "./support/browser-api";
import { concretePath } from "./support/budget";
import { forQuery } from "./support/java-stub.mjs";
import {
  FIXTURES,
  PAGE_MARKERS,
  PAGE_PATHS,
  PAGE_SCENARIOS,
  SCENARIOS,
  withNowName,
  withStaleNowName,
  type Value,
} from "./support/scenarios.mjs";
import { screenChecks } from "./support/screen-checks";

/*
 * The chat page (plan W5) on /app/home against the production build and stub Java: the global NOW banner and its seen button, the
 * thread as WhatsApp delivered it (and the questions asked here, plan CHAT-web), the attention stack and the composer
 * that asks Java (PO ruling P7, DR-50). Every body is one Java's contract tests wrote (fixtures/api/**); scenarios.mjs
 * says which answers which route.
 */

const body = <T>(file: string): T => (JSON.parse(readFileSync(join(FIXTURES, file), "utf8")) as { body: T }).body;
/**
 * What a scenario answers a route with, as stub Java would serve it (a fixture file read, or a literal), for the query
 * the page reads it with (the chat page reads its thread with `asks=1`, plan CHAT-web).
 */
function answerOf<T>(scenario: string, key: string, query = "asks=1"): T {
  const table = SCENARIOS[scenario]?.[key];
  if (table === undefined) throw new Error(`${scenario} does not answer ${key}`);
  const value = forQuery(table, query) as Exclude<Value, { byQuery: unknown }>;
  const first = Array.isArray(value) ? value[0]! : value;
  if (typeof first === "function") throw new Error(`${scenario} answers ${key} from the request`);
  if (typeof first === "string") return body<T>(first);
  if (!("body" in first)) throw new Error(`${scenario} answers ${key} with no body (a cut or held request)`);
  return first.body as T;
}

const ONE = body<MeJson>("me/one-patient.json");
const TWO = body<MeJson>("me/two-patients.json");
const CATALOG_URL = process.env.E2E_CATALOG_URL ?? `http://127.0.0.1:${Number(process.env.E2E_PORT ?? 3217) + 1}`;

/**
 * The signed-in pages of this build: every NEXT_PAGES entry but the sign-in pages, each with the path it is opened at
 * (a dynamic pattern filled from PAGE_PATHS, the fixture id its scenario serves; concretePath refuses one left unfilled).
 */
const SIGN_IN_PAGES = ["/app", "/app/verify"];
function signedInPages(): Array<{ pattern: string; path: string }> {
  return NEXT_PAGES.filter((p) => !SIGN_IN_PAGES.includes(p)).map((pattern) => ({ pattern, path: concretePath(pattern, PAGE_PATHS) }));
}

async function open(page: Page, scenario: string, path = "/app/home") {
  const recorder = await useScenario(page, scenario);
  const response = await page.goto(path);
  expect(response?.status(), `${path} under ${scenario}`).toBe(200);
  await page.waitForLoadState("load");
  return recorder;
}

// The banner's alert, not Next's own route announcer (a role="alert" region the App Router adds to every page).
const alert = (page: Page) => page.locator('section[role="alert"]');
/** Every tel: link on the page, by its href, so a failure names the number it offered. */
const telLinks = (page: Page) => page.locator('a[href^="tel:"]').evaluateAll((as) => as.map((a) => a.getAttribute("href")));
const seenButton = (page: Page) => alert(page).getByRole("button", { name: t("bn", "now.banner.seen") });

/**
 * Types `question` into the composer and presses send; resolves with Medioo's bubble once it is no longer the pending one
 * (the answer, or the reply), its text as drawn: innerText, which follows the rendered line breaks. textContent keeps
 * Java's "\n" whether or not `white-space: pre-line` applies, so the answer, the disclaimer and the red-flag line run
 * together in one paragraph on screen and textContent still reads them as three lines (review of PR 166, finding 3).
 */
async function ask(page: Page, question: string): Promise<string> {
  await page.locator(".composer__field").fill(question);
  await page.getByRole("button", { name: t("bn", "add.send") }).click();
  const pending = t("bn", "chat.composer.pending", ONE.patients[0]!.name_possessive);
  const bubble = page.locator(".composer__lines .message-bubble--recv .message-bubble__body").last();
  await expect.poll(async () => ((await bubble.count()) === 0 ? pending : await bubble.textContent()), { message: "Medioo answered" }).not.toBe(pending);
  return bubble.innerText();
}

/**
 * The quoted line's crop in an answer (review of PR 166, finding 5): its src, whether the image the stub served loaded,
 * and whether it is drawn before Medioo's bubble and outside it.
 */
async function cropBeforeBubble(scope: Locator) {
  const img = scope.locator(".ask-answer img");
  await expect(img).toHaveCount(1);
  await expect.poll(() => img.evaluate((i: HTMLImageElement) => i.complete && i.naturalWidth > 0), { message: "the crop loaded" }).toBe(true);
  return img.evaluate((i: HTMLImageElement) => {
    const bubble = i.closest(".ask-answer")!.querySelector(".message-bubble--recv")!;
    return {
      src: i.getAttribute("src"),
      loaded: i.naturalWidth > 0,
      before: Boolean(i.compareDocumentPosition(bubble) & Node.DOCUMENT_POSITION_FOLLOWING) && !bubble.contains(i),
    };
  });
}

/**
 * Java's answer as drawn: line for line what Java sent, its last two lines the disclaimer and then the red-flag line,
 * each on its own line on screen (`drawn` is innerText).
 */
function drawnAsSent(drawn: string, answer: string, what: string) {
  expect(drawn.split("\n"), `${what}: line for line`).toEqual(answer.split("\n"));
  expect(drawn.split("\n").slice(-2), `${what}: the two appended lines, last, each its own`).toEqual([
    t("bn", "disclaimer"),
    t("bn", "safety.red_flag_line"),
  ]);
}

test.describe("the NOW banner", () => {
  test("theNowLineIsShippedVerbatimAndTheNameIsTheTitles", async ({ page }) => {
    const banner = answerOf<NowBannerJson>("chat-now", "GET /app/api/now-banner");
    expect(banner.line_key, "Java names the shipped NOW sentence").toBe("urgency.now");
    await open(page, "chat-now");
    await expect(alert(page)).toHaveCount(1);
    const line = alert(page).locator(".now-banner__line");
    await expect(line).toHaveText(t("bn", banner.line_key as CopyKey), { useInnerText: false });
    const name = ONE.patients[0]!.name;
    const titleId = await alert(page).getAttribute("aria-labelledby");
    const title = page.locator(`[id="${titleId}"]`);
    await expect(title).toContainText(name);
    expect(await line.textContent(), "the name is the title's, never inside the safety sentence").not.toContain(name);
  });

  test("999OnlyWhenJavaSaysBangladesh", async ({ page }) => {
    expect(answerOf<NowBannerJson>("chat-now", "GET /app/api/now-banner").call_999).toBe(true);
    await open(page, "chat-now");
    await expect(alert(page).locator('a[href="tel:999"]')).toHaveCount(1);
    expect(answerOf<NowBannerJson>("chat-now-abroad", "GET /app/api/now-banner").call_999).toBe(false);
    await open(page, "chat-now-abroad");
    await expect(alert(page)).toHaveCount(1);
    expect(await telLinks(page), "tel: links anywhere on the page abroad").toEqual([]);
  });

  test("seenSendsTheSeenSetAsReceived", async ({ page }) => {
    const banner = answerOf<NowBannerJson>("chat-now-ack", "GET /app/api/now-banner");
    const recorder = await open(page, "chat-now-ack", `/app/home?patient=${TWO.patients[0]!.id}`);
    await seenButton(page).click();
    await expect(alert(page)).toHaveCount(0);
    const puts = recorder.requests().filter((r) => r.method === "PUT" && r.path === "/app/api/now-banner/ack");
    expect(puts.length).toBe(1);
    // Parsed, deep-equal: the list Java sent, entry for entry (document_id, urgency, reasons_digest, release_state).
    expect(puts[0]!.body).toEqual({ seen: banner.seen });
    expect(puts[0]!.headers["x-medioo"]).toBe("1");
  });

  test("seenHidesOnlyWhatTheServerSays", async ({ page }) => {
    const ma = TWO.patients[0]!;
    expect(ma.urgent, "Ma is urgent in me/two-patients").toBe(true);
    await open(page, "chat-now-ack", `/app/home?patient=${ma.id}`);
    await seenButton(page).click();
    await expect(alert(page), "Java answered show: false").toHaveCount(0);
    // The banner only: Ma keeps her red mark in the switcher.
    await page.getByRole("button", { name: t("bn", "switch.label", ma.name) }).click();
    const option = page.locator(`dialog [data-patient="${ma.id}"]`);
    await expect(option.locator(".urgent-mark")).toHaveCount(1);
    await expect(option).toContainText(t("bn", "attn.strip"));
  });

  test("aStaleSeenKeepsTheBanner", async ({ page }) => {
    expect(answerOf<NowBannerJson>("chat-now-ack-stale", "PUT /app/api/now-banner/ack").show).toBe(true);
    await open(page, "chat-now-ack-stale", `/app/home?patient=${TWO.patients[0]!.id}`);
    // The page is re-read from Java (router.refresh) after the stale answer, and the banner is still there.
    const refreshed = page.waitForRequest((r) => r.headers()["rsc"] === "1" && new URL(r.url()).pathname === "/app/home");
    await seenButton(page).click();
    await refreshed;
    await page.waitForLoadState("networkidle");
    await expect(alert(page)).toHaveCount(1);
    await expect(seenButton(page)).toBeEnabled();
  });

  test("aSeenTapOnAnExpiredSessionGoesToSignInAndKeepsTheBanner", async ({ page }) => {
    // Review Focus 3. At the moment the 401 arrives the banner is still rendered: nothing was hidden on the tap.
    await open(page, "chat-now-ack-401");
    let atTheAnswer = -1;
    await page.route("**/app/api/now-banner/ack", async (route) => {
      atTheAnswer = await alert(page).count();
      await route.fulfill({ status: 401, contentType: "application/json", body: JSON.stringify({ reason: "UNAUTHENTICATED" }) });
    });
    await seenButton(page).click();
    await page.waitForURL((url) => url.pathname === "/app/" && url.search === signInPath("/app/home").slice("/app/".length));
    expect(atTheAnswer, "alerts on the page when the 401 arrived").toBe(1);
  });

  test("theBannerIsOnEveryPage", async ({ page }) => {
    const pages = signedInPages();
    expect(pages.length, "signed-in pages in NEXT_PAGES").toBeGreaterThan(0);
    for (const { pattern, path } of pages) {
      // Each page under its own scenario (PAGE_SCENARIOS), with an unseen NOW paper for its patient (withNow), so the
      // page makes its own reads and is not a not-found page drawn from another page's scenario.
      const own = PAGE_SCENARIOS[pattern];
      expect(own, `PAGE_SCENARIOS["${pattern}"]`).toBeDefined();
      await open(page, withNowName(own!), path);
      await expect(alert(page), path).toHaveCount(1);
      // The page itself, not a not-found page carrying the shell's banner: its PAGE_MARKERS attribute is rendered.
      const marker = PAGE_MARKERS[pattern];
      expect(marker, `PAGE_MARKERS["${pattern}"]`).toBeDefined();
      await expect(page.locator(`[${marker}]`), `${path} rendered itself`).toHaveCount(1);
    }
    // And on the shell's own catalog page (the catalog build, W4).
    await useScenario(page, "chat-now");
    const response = await page.goto(new URL("/app/shell-test", CATALOG_URL).href);
    expect(response?.status()).toBe(200);
    await expect(alert(page), "/app/shell-test").toHaveCount(1);
  });

  test("aNowArrivingDuringSoftNavigationShowsTheBanner", async ({ page }) => {
    // A preview page (the chat tab is Next's there, so its link is a soft navigation). No NOW at first.
    const patient = body<MeJson>("me/preview-on.json").patients[0]!;
    await open(page, "chat-preview", `/app/preview/home?patient=${patient.id}`);
    await expect(alert(page)).toHaveCount(0);
    await page.evaluate(() => ((window as unknown as { __noHardLoad: number }).__noHardLoad = 1));
    // A NOW paper arrives: stub Java's banner now answers one.
    const base = new URL(page.url());
    await page.context().addCookies([{ name: "e2e_scenario", value: "chat-preview-now", domain: base.hostname, path: "/app" }]);
    await page.locator("a.tab-bar__link:visible", { hasText: t("bn", "tab.chat") }).first().click();
    await page.waitForURL((url) => url.pathname === "/app/preview/home" && url.search === "");
    await expect(alert(page)).toHaveCount(1);
    expect(await page.evaluate(() => (window as unknown as { __noHardLoad?: number }).__noHardLoad), "no hard load").toBe(1);
  });

  test("mioDoesNotMoveOnNow", async ({ page }) => {
    // Every Mio on a page that shows a NOW result, not only the banner's: the thread's NOW card has one beside it, and a
    // first-run patient's introduction is on a NOW page while the family has a NOW paper for someone else.
    const mios = (scope: Page | ReturnType<typeof alert>) =>
      scope.locator("img.mio-avatar").evaluateAll((imgs) =>
        imgs.map((i) => `${i.closest("[class]")?.className ?? "?"}: ${getComputedStyle(i).animationName}`),
      );
    const cases: Array<[string, string, string]> = [
      ["chat-now", "/app/home", "[data-thread-item='paper'] img.mio-avatar"],
      ["chat-first-now", `/app/home?patient=${TWO.patients[1]!.id}`, ".chat__intro img.mio-avatar"],
    ];
    const moving: string[] = [];
    for (const [scenario, path, beyond] of cases) {
      await open(page, scenario, path);
      const inBanner = await mios(alert(page));
      const all = await mios(page);
      expect(inBanner.length, `${scenario}: Mio is on the banner`).toBeGreaterThan(0);
      // Guard: the page's Mios include the one beyond the banner, or this only re-checked the banner.
      await expect(page.locator(beyond), `${scenario}: ${beyond}`).toHaveCount(1);
      expect(all.length, `${scenario}: Mios beyond the banner's`).toBeGreaterThan(inBanner.length);
      moving.push(...all.filter((m) => !m.endsWith(": none")).map((m) => `${scenario} ${m}`));
    }
    expect(moving, "a Mio that moves on a NOW page").toEqual([]);
  });

  test("aStaleSeenReReadsNoPaper", async ({ page }) => {
    // The stale answer re-runs the page's server reads (router.refresh). On a page that reads a paper, that read records
    // a DOCUMENT_VIEW (Global Constraints: one per open), so the refresh must never re-read a paper: a page that reads
    // GET /app/api/documents/{id} renders the stale answer some other way. Every signed-in page is held to it (W6 too).
    const pages = signedInPages();
    expect(pages.length, "signed-in pages in NEXT_PAGES").toBeGreaterThan(0);
    for (const { pattern, path } of pages) {
      const own = PAGE_SCENARIOS[pattern];
      expect(own, `PAGE_SCENARIOS["${pattern}"]`).toBeDefined();
      const recorder = await open(page, withStaleNowName(own!), path);
      const before = (await recorder.java()).length;
      const refreshed = page.waitForRequest((r) => r.headers()["rsc"] === "1" && new URL(r.url()).pathname === new URL(page.url()).pathname);
      await seenButton(page).click();
      await refreshed;
      await page.waitForLoadState("networkidle");
      await expect(alert(page), `${path}: the stale banner stays`).toHaveCount(1);
      const after = (await recorder.java()).slice(before).map((r) => `${r.method} ${r.path}`);
      // Guard: the refresh did re-read the page from Java, or this saw nothing.
      expect(after, `${path}: the refresh re-read the banner`).toContain("GET /app/api/now-banner");
      expect(after.filter((r) => /^GET \/app\/api\/documents\//.test(r)), `${path}: papers re-read by the refresh`).toEqual([]);
    }
  });
});

test.describe("the thread and the composer", () => {
  test("noTelLinkInTheThreadAbroad", async ({ page }) => {
    // Each precondition on its own, so a fixture whose paper is not a DONE NOW one fails here instead of making the
    // page-wide check below pass with nothing that could have offered a tel: link.
    const abroad = answerOf<ThreadJson>("chat-thread-abroad", "GET /app/api/patients/{id}/thread").items.find((i) => i.kind === "paper");
    expect(abroad?.kind, "thread/now-abroad holds a paper").toBe("paper");
    if (abroad?.kind !== "paper") return;
    expect(abroad.card.status).toBe("DONE");
    if (abroad.card.status !== "DONE") return;
    expect(abroad.card.now, "a NOW paper").not.toBeNull();
    expect(abroad.card.now!.call_999, "a viewer outside Bangladesh").toBe(false);
    await open(page, "chat-thread-abroad");
    await expect(page.locator("article.verdict-card")).toHaveCount(1);
    expect(await telLinks(page), "tel: links on the page").toEqual([]);

    // The other direction, in the thread alone (not the banner's): a viewer in Bangladesh has exactly the 999 link there.
    const bd = answerOf<ThreadJson>("chat-now", "GET /app/api/patients/{id}/thread").items.find((i) => i.kind === "paper");
    expect(bd?.kind === "paper" && bd.card.status === "DONE" ? bd.card.now?.call_999 : null, "thread/now: in Bangladesh").toBe(true);
    await open(page, "chat-now");
    const inThread = await page
      .locator("[data-thread-item='paper'] a[href^='tel:']")
      .evaluateAll((as) => as.map((a) => a.getAttribute("href")));
    expect(inThread, "tel: links in the thread").toEqual(["tel:999"]);
  });

  test("the999LinkOnAThreadCardIsItsOwnTarget", async ({ page }) => {
    // The card's whole-card link (.verdict-card__open::after, inset 0) lies over the article; a tap on the 999 link must
    // still reach the tel: link, and a tap on the card's body must reach the paper. Judged on what the browser hits.
    await open(page, "chat-now");
    const card = page.locator("[data-thread-item='paper'] article.verdict-card");
    await expect(card).toHaveCount(1);
    const paperHref = await card.locator("a.verdict-card__open").getAttribute("href");
    expect(paperHref, "the card opens its paper").toMatch(/^\/app\/documents\//);
    const hitAt = async (selector: string) => {
      const target = card.locator(selector);
      await expect(target, selector).toHaveCount(1);
      // To the middle of the window, clear of the docked composer and the tab bar, which lie over the page's foot.
      await target.evaluate((el) => el.scrollIntoView({ block: "center" }));
      const box = await target.boundingBox();
      expect(box, `${selector} is laid out`).not.toBeNull();
      return page.evaluate(
        ({ x, y }) => {
          const a = document.elementFromPoint(x, y)?.closest("a");
          return a ? `${a.className} ${a.getAttribute("href")}` : "no link";
        },
        { x: box!.x + box!.width / 2, y: box!.y + box!.height / 2 },
      );
    };
    expect(await hitAt("a.verdict-card__call[href='tel:999']"), "a tap on the 999 link").toBe("verdict-card__call tel:999");
    expect(await hitAt(".verdict-card__notdoctor"), "a tap on the card's body").toBe(`verdict-card__open ${paperHref}`);
  });

  test("theStackIsShownOnceAtEveryWidth", async ({ page }) => {
    // home.css hides the inline stack from 900 px and AppShell.css shows the conversation column from 900 px: both
    // copies are in the DOM at every width, so exactly one must be visible on each side of the breakpoints.
    const items = answerOf<AttentionJson>("chat-stack", "GET /app/api/patients/{id}/attention").items;
    expect(items.length, "full-stack holds cards").toBeGreaterThan(0);
    await open(page, "chat-stack");
    for (const width of [899, 900, 1199, 1280]) {
      await page.setViewportSize({ width, height: 800 });
      await expect(page.locator("[data-attention]:visible"), `visible attention cards at ${width} px`).toHaveCount(items.length);
    }
  });

  test("thePageOpensOnTheNewestExchange", async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== "phone", "the phone's one column; the desktop thread fits beside the stack");
    // The thread ends on the newest exchange, and the docked composer and its camera are in view, over the tab bar.
    await open(page, "chat-long");
    const thread = answerOf<ThreadJson>("chat-long", "GET /app/api/patients/{id}/thread");
    expect(thread.items.length, "a thread longer than a screen").toBeGreaterThanOrEqual(10);
    await expect
      .poll(() => page.evaluate(() => window.scrollY), { message: "the page opened scrolled down to the newest exchange" })
      .toBeGreaterThan(0);
    const where = await page.evaluate(() => {
      const items = [...document.querySelectorAll("[data-thread-item]")];
      const rect = (el: Element | null) => (el ? el.getBoundingClientRect().toJSON() : null);
      return {
        newest: rect(items[items.length - 1] ?? null) as DOMRect | null,
        composer: rect(document.querySelector(".composer")) as DOMRect | null,
        camera: rect(document.querySelector(".composer__camera")) as DOMRect | null,
        tabs: rect(document.querySelector(".app-shell__tabs")) as DOMRect | null,
        height: window.innerHeight,
      };
    });
    expect(where.newest && where.composer && where.camera && where.tabs, "every part is laid out").toBeTruthy();
    // The newest item ends above the composer, inside the window; the camera is wholly inside it, above the tab bar.
    expect(where.newest!.bottom, "the newest item's foot is in view").toBeGreaterThan(0);
    expect(where.newest!.bottom, "and not under the composer").toBeLessThanOrEqual(where.composer!.top + 1);
    expect(where.camera!.top, "the camera is in view").toBeGreaterThanOrEqual(0);
    expect(where.camera!.bottom, "and not under the tab bar").toBeLessThanOrEqual(where.tabs!.top + 1);
    expect(where.composer!.bottom, "the composer sits on the tab bar, not under it").toBeLessThanOrEqual(where.tabs!.top + 1);
  });

  test("aNowResultIsNeverScrolledAway", async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== "phone", "the phone's one column");
    // With a NOW result on the page the page stays at the top, where the banner is, and the composer is still docked.
    for (const scenario of ["chat-now", "chat-now-past-the-page"]) {
      await open(page, scenario);
      await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
      expect(await page.evaluate(() => window.scrollY), `${scenario}: the page did not scroll`).toBe(0);
      const nowCard = page.locator(scenario === "chat-now" ? 'section[role="alert"]' : '[data-attention="urgent"]').first();
      await expect(nowCard, `${scenario}: the NOW result is in view`).toBeInViewport();
      await expect(page.locator(".composer__standing"), `${scenario}: the docked composer is in view`).toBeInViewport();
    }
  });

  test("theThreadIsWhatJavaSent", async ({ page }) => {
    const thread = answerOf<ThreadJson>("chat-default", "GET /app/api/patients/{id}/thread");
    await open(page, "chat-default");
    const bodies = await page.locator("[data-thread-item='message'] .message-bubble__body").allTextContents();
    expect(bodies).toEqual([...thread.items].reverse().flatMap((i) => (i.kind === "message" ? [i.body] : [])));
    await expect(page.locator("[data-thread-item='paper'] article.verdict-card")).toHaveCount(
      thread.items.filter((i) => i.kind === "paper").length,
    );
  });

  test("aQuestionIsAnsweredFromJava", async ({ page }) => {
    // Plan CHAT-web (PO ruling P7, DR-50): the typed question goes to Java once, and Medioo's bubble is Java's answer as
    // sent, ending with the disclaimer and the red-flag line Java's code appended. The page itself never navigates.
    const answer = answerOf<AskJson>("chat-ask-answer", "POST /app/api/patients/{id}/ask");
    const recorder = await open(page, "chat-ask-answer");
    // The field appears once the page has hydrated, after the App Router's own same-document replaceState of the address
    // (a framenavigated event of its own): navigations are counted from there, so only the press's can be seen.
    await page.locator(".composer__field").waitFor();
    const before = new URL(page.url());
    const navigations: string[] = [];
    page.on("framenavigated", (frame) => {
      if (frame === page.mainFrame()) navigations.push(frame.url());
    });
    const reply = await ask(page, answer.question);
    const after = new URL(page.url());
    expect([after.pathname, after.search], "the address after the send").toEqual([before.pathname, before.search]);
    expect(navigations, "navigations after the send").toEqual([]);
    await expect(page.locator(".composer__lines .message-bubble--sent")).toHaveText(answer.question);
    drawnAsSent(reply, answer.answer, "Medioo's bubble");
    const posts = recorder.requests().filter((r) => r.method !== "GET");
    expect(posts.map((r) => `${r.method} ${r.path} ${r.answered}`)).toEqual([`POST /app/api/patients/${ONE.patients[0]!.id}/ask 200`]);
    expect(posts[0]!.body, "the parsed body").toEqual({ question: answer.question });
    expect(posts[0]!.headers["x-medioo"]).toBe("1");
    await expect(page.locator(".composer__send"), "send is back for the next question").toBeEnabled();
  });

  test("aFailedAskShowsTheReplyNeverAnEmptyBubble", async ({ page }) => {
    // A press whose connection dies, and a route Java refuses (chat-default does not answer the ask: 404): both show the
    // reply WhatsApp gives, with the red-flag line, and never an empty or a stuck bubble.
    const fallback = `${t("bn", "chat.ask.reply")}\n${t("bn", "safety.red_flag_line")}`;
    for (const [scenario, answered] of [
      ["chat-ask-offline", "aborted"],
      ["chat-default", 404],
    ] as const) {
      const recorder = await open(page, scenario);
      expect(await ask(page, "Is this all right?"), scenario).toBe(fallback);
      expect(recorder.requests().filter((r) => r.method === "POST").map((r) => r.answered), scenario).toEqual([answered]);
    }
  });

  test("anEmergencyAnswerOffers999OnlyWhenJavaSays", async ({ page }) => {
    const home = answerOf<AskJson>("chat-ask-emergency", "POST /app/api/patients/{id}/ask");
    expect([home.kind, home.call_999]).toEqual(["emergency", true]);
    await open(page, "chat-ask-emergency");
    expect(await telLinks(page), "before the question").toEqual([]);
    drawnAsSent(await ask(page, home.question), home.answer, "the emergency answer");
    expect(await page.locator(".composer__lines a[href^='tel:']").evaluateAll((as) => as.map((a) => a.getAttribute("href")))).toEqual([
      "tel:999",
    ]);
  });

  test("theAnswerIsInViewAboveTheComposer", async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== "phone", "the phone's one column, where the docked composer lies over the thread");
    // The emergency answer is the one that must be read at once: once drawn, its foot (and its 999 call) is inside the
    // window and not under the docked composer, without the family scrolling (the board showed it under the bar).
    const home = answerOf<AskJson>("chat-ask-emergency", "POST /app/api/patients/{id}/ask");
    await open(page, "chat-ask-emergency");
    await ask(page, home.question);
    await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
    const where = await page.evaluate(() => {
      const rect = (el: Element | null) => (el ? el.getBoundingClientRect().toJSON() : null);
      return {
        answer: rect(document.querySelector(".composer__lines .ask-answer:last-of-type")) as DOMRect | null,
        composer: rect(document.querySelector(".chat > .composer")) as DOMRect | null,
      };
    });
    expect(where.answer && where.composer, "both laid out").toBeTruthy();
    expect(where.answer!.bottom, "the answer's foot is in the window").toBeGreaterThan(0);
    expect(where.answer!.bottom, "and not under the composer").toBeLessThanOrEqual(where.composer!.top + 1);
  });

  test("aLongAnswerIsReadFromItsFirstLine", async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== "phone", "the phone's one column, where a long answer is taller than the window");
    // Review of PR 166, finding 9: an answer near Java's cap is taller than the space between the toolbar and the docked
    // composer. The window then goes to the sent question, so the answer's first line is in view, not its disclaimer.
    const long = answerOf<AskJson>("chat-ask-long", "POST /app/api/patients/{id}/ask");
    expect(long.answer.length, "answer-long is near the cap").toBeGreaterThan(900);
    await open(page, "chat-ask-long");
    drawnAsSent(await ask(page, long.question), long.answer, "the long answer");
    await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
    const where = await page.evaluate(() => {
      const body = document.querySelector(".composer__lines > li:last-child .ask-answer .message-bubble__body")!;
      const first = document.createRange();
      first.setStart(body.firstChild!, 0);
      first.setEnd(body.firstChild!, 1);
      return {
        exchange: document.querySelector(".composer__lines > li:last-child")!.getBoundingClientRect().height,
        firstLine: first.getBoundingClientRect().toJSON() as DOMRect,
        toolbar: document.querySelector(".app-shell__toolbar")?.getBoundingClientRect().bottom ?? 0,
        composer: document.querySelector(".chat > .composer")!.getBoundingClientRect().top,
      };
    });
    expect(where.exchange, "the precondition: taller than the room above the composer").toBeGreaterThan(where.composer - where.toolbar);
    expect(where.firstLine.top, "the answer's first line is below the toolbar").toBeGreaterThanOrEqual(where.toolbar - 1);
    expect(where.firstLine.bottom, "and above the composer").toBeLessThanOrEqual(where.composer + 1);
  });

  test("theSendKeyKeepsTheKeyboardsFocus", async ({ page }) => {
    // Review of PR 166, finding 6 (WCAG 2.4.3): a keyboard user who tabs to send and presses it keeps focus there while
    // Java answers and after; a native `disabled` would blur the button and drop focus to the page.
    for (const [scenario, settled] of [
      ["chat-ask-held", false],
      ["chat-ask-answer", true],
    ] as const) {
      await open(page, scenario);
      await page.locator(".composer__field").fill(answerOf<AskJson>("chat-ask-answer", "POST /app/api/patients/{id}/ask").question);
      await page.locator(".composer__field").press("Tab");
      const send = page.getByRole("button", { name: t("bn", "add.send") });
      await expect(send, `${scenario}: on send`).toBeFocused();
      await page.keyboard.press("Enter");
      await expect(page.locator(".composer__lines .message-bubble--sent"), scenario).toHaveCount(1);
      const pending = t("bn", "chat.composer.pending", ONE.patients[0]!.name_possessive);
      const recv = page.locator(".composer__lines .message-bubble--recv .message-bubble__body");
      if (settled) await expect(recv, `${scenario}: answered`).not.toHaveText(pending);
      else await expect(recv, `${scenario}: pending`).toHaveText(pending);
      const focused = await page.evaluate(() => document.activeElement?.className || document.activeElement?.tagName);
      expect(focused, `${scenario}: focus after the press`).toBe("composer__send");
      // The press is still refused while pending, by aria-disabled (and the island's own guard), never natively.
      if (!settled) await expect(send).toHaveAttribute("aria-disabled", "true");
    }
  });

  test("anExpiredSessionKeepsTheRedFlagLineAndOffersSignIn", async ({ page }) => {
    // Review of PR 166, finding 14: Java's guard answers 401 before its emergency pre-check, so an emergency typed after the
    // session ended never reaches the emergency line. The reply, ending with the red-flag line, stays readable, with the
    // sign-in link beside it; the page does not leave by itself.
    await open(page, "chat-ask-401");
    await page.locator(".composer__field").waitFor();
    const navigations: string[] = [];
    page.on("framenavigated", (frame) => {
      if (frame === page.mainFrame()) navigations.push(frame.url());
    });
    const reply = await ask(page, answerOf<AskJson>("chat-ask-emergency", "POST /app/api/patients/{id}/ask").question);
    expect(reply.split("\n")).toEqual([t("bn", "chat.ask.reply"), t("bn", "safety.red_flag_line")]);
    await expect(page.locator(".composer__lines .message-bubble--recv").last()).toBeVisible();
    const signIn = page.locator(".composer__lines a.composer__signin");
    await expect(signIn).toHaveText(t("bn", "login.enter"));
    await expect(signIn).toHaveAttribute("href", signInPath("/app/home"));
    expect(navigations, "no navigation by itself").toEqual([]);
    expect(new URL(page.url()).pathname).toBe("/app/home");
  });

  test("aQuotedLineIsShownWithItsCrop", async ({ page }) => {
    // Review of PR 166, finding 5 (DR-12, DR-16): the dose answer's transcribed line comes with its crop, before the bubble.
    const dose = answerOf<AskJson>("chat-ask-dose", "POST /app/api/patients/{id}/ask");
    expect(dose.medicines.length, "answer-dose quotes a line").toBe(1);
    await open(page, "chat-ask-dose");
    drawnAsSent(await ask(page, dose.question), dose.answer, "the dose answer");
    expect(await cropBeforeBubble(page.locator(".composer__lines > li").last())).toEqual({
      src: dose.medicines[0]!.crop.src,
      loaded: true,
      before: true,
    });
  });

  test("anEmergencyAbroadHasNoTelLink", async ({ page }) => {
    const abroad = answerOf<AskJson>("chat-ask-emergency-abroad", "POST /app/api/patients/{id}/ask");
    expect([abroad.kind, abroad.call_999]).toEqual(["emergency", false]);
    await open(page, "chat-ask-emergency-abroad");
    drawnAsSent(await ask(page, abroad.question), abroad.answer, "the emergency answer abroad");
    expect(await telLinks(page), "tel: links on the page").toEqual([]);
  });

  test("theAnswerSurvivesAReload", async ({ page }) => {
    // The stored turns, read by the page's server render (?asks=1), oldest first as the thread reads.
    const stored = answerOf<ThreadJson>("chat-with-asks", "GET /app/api/patients/{id}/thread")
      .items.filter((i) => i.kind === "ask")
      .reverse();
    expect(stored.length, "with-asks holds answered turns").toBeGreaterThan(0);
    await open(page, "chat-with-asks");
    // innerText, as drawn (finding 3): each stored answer's lines on their own lines on screen, the two appended last.
    const shown = await page.locator("[data-thread-item='ask']").evaluateAll((items) =>
      items.map((el) => [...el.querySelectorAll<HTMLElement>(".message-bubble__body")].map((b) => b.innerText)),
    );
    expect(shown.map((pair) => pair[0])).toEqual(stored.map((a) => (a.kind === "ask" ? a.question : "")));
    stored.forEach((a, k) => {
      if (a.kind === "ask") drawnAsSent(shown[k]![1]!, a.answer, `stored answer ${k}`);
    });
    // The stored emergency, read in Bangladesh, keeps its 999 link; the stored answer has none.
    expect(
      await page.locator("[data-thread-item='ask'] a[href^='tel:']").evaluateAll((as) => as.map((a) => a.getAttribute("href"))),
    ).toEqual(stored.filter((a) => a.kind === "ask" && a.call_999).map(() => "tel:999"));
  });

  test("thePageAsksForAsks", async ({ page }) => {
    // Two reads (review of PR 166, findings 1, 7, 13): without asks, the papers and messages that decide a first-run patient
    // and that no question pushes off the page; with asks=1, the questions and Java's answers.
    const recorder = await open(page, "chat-with-asks");
    const reads = (await recorder.java()).filter((r) => r.method === "GET" && /^\/app\/api\/patients\/[^/]+\/thread$/.test(r.path));
    expect(reads.length, "the page's server reads of the thread").toBeGreaterThan(0);
    const thread = `/app/api/patients/${ONE.patients[0]!.id}/thread`;
    expect([...new Set(reads.map((r) => `${r.path}?${r.query}`))].sort()).toEqual([`${thread}?`, `${thread}?asks=1`]);
  });

  test("aStoredQuoteIsShownWithItsCropAfterAReload", async ({ page }) => {
    // Review of PR 166, finding 5: the stored dose answer, server-rendered, has the quoted line's crop before the bubble,
    // the prescription's own image of it, loaded (not a broken image).
    const dose = answerOf<ThreadJson>("chat-with-asks-dose", "GET /app/api/patients/{id}/thread").items.find((i) => i.kind === "ask");
    expect(dose?.kind === "ask" ? dose.medicines.length : 0, "with-asks-dose quotes a line").toBe(1);
    if (dose?.kind !== "ask") return;
    await open(page, "chat-with-asks-dose");
    const item = page.locator("[data-thread-item='ask']");
    await expect(item).toHaveCount(1);
    expect(await cropBeforeBubble(item)).toEqual({ src: dose.medicines[0]!.crop.src, loaded: true, before: true });
  });

  test("aFirstRunPatientGetsTheIntroAndOneCamera", async ({ page }) => {
    await open(page, "chat-first");
    await expect(page.locator(".message-bubble__body", { hasText: t("bn", "chat.intro") })).toHaveCount(1);
    const cameras = page.locator('a[href^="/app/add"]');
    await expect(cameras).toHaveCount(1);
    await expect(cameras).toHaveAttribute("aria-label", t("bn", "chat.camera"));
    // A share member's patient: the introduction, and no camera at all.
    const shared = TWO.patients.find((p) => !p.owner)!;
    await open(page, "chat-share-first", `/app/home?patient=${shared.id}`);
    await expect(page.locator(".message-bubble__body", { hasText: t("bn", "chat.intro") })).toHaveCount(1);
    await expect(page.locator('a[href^="/app/add"]')).toHaveCount(0);
  });

  test("aQuestionBeforeTheFirstPaperKeepsTheIntro", async ({ page }) => {
    // Review of PR 166, findings 1 and 13, both directions. A first-run patient whose owner asked one question (Java stores
    // the turn even with answers off): still Mio's introduction and the one camera, and no link to an empty Papers page;
    // the stored question is drawn too.
    const asked = answerOf<ThreadJson>("chat-first-with-asks", "GET /app/api/patients/{id}/thread");
    expect(asked.items.map((i) => i.kind), "the first-run page holding one ask").toEqual(["ask"]);
    expect(answerOf<ThreadJson>("chat-first-with-asks", "GET /app/api/patients/{id}/thread", "").items, "and no paper").toEqual([]);
    await open(page, "chat-first-with-asks");
    await expect(page.locator(".message-bubble__body", { hasText: t("bn", "chat.intro") })).toHaveCount(1);
    await expect(page.locator('a[href^="/app/add"]')).toHaveCount(1);
    await expect(page.locator(".chat__papers-link")).toHaveCount(0);
    await expect(page.locator("[data-thread-item='ask']")).toHaveCount(1);
    // The other direction: a patient with a paper (and asks) gets no introduction and the link to the papers.
    expect(answerOf<ThreadJson>("chat-with-asks", "GET /app/api/patients/{id}/thread", "").items.some((i) => i.kind === "paper")).toBe(true);
    await open(page, "chat-with-asks");
    await expect(page.locator(".message-bubble__body", { hasText: t("bn", "chat.intro") })).toHaveCount(0);
    await expect(page.locator(".chat__papers-link")).toHaveCount(1);
  });

  test("theJavaScriptPageNeverSaysToTurnJavaScriptOn", async ({ page }) => {
    // Review of PR 166, finding 10: the server HTML a family with JavaScript on reads before hydration carries
    // chat.composer.nojs as markup only inside a <noscript>; with JavaScript on it is never drawn. (The React flight data,
    // inside <script> elements, carries every server-rendered string, the noscript's included: it is never drawn.)
    await useScenario(page, "chat-default");
    const response = await page.goto("/app/home");
    const html = (await response!.text()).replace(/<!-- -->/g, "");
    const nojs = t("bn", "chat.composer.nojs");
    const markup = html.replace(/<script\b[\s\S]*?<\/script>/g, "");
    expect(markup, "the line is in the page, for a browser without JavaScript").toContain(nojs);
    expect(markup.replace(/<noscript>[\s\S]*?<\/noscript>/g, ""), "and only inside a <noscript>").not.toContain(nojs);
    await page.waitForLoadState("load");
    await expect(page.getByText(nojs)).toHaveCount(0);
    await expect(page.locator(".composer__standing")).toHaveText(t("bn", "chat.composer.hint"));
  });

  test("theDisclaimerIsOnThePage", async ({ page }) => {
    for (const scenario of ["chat-default", "chat-now", "chat-retake", "chat-failed"]) {
      await open(page, scenario);
      expect(await page.locator("article.verdict-card").count(), `${scenario} shows a paper card`).toBeGreaterThan(0);
      await expect(page.getByText(t("bn", "disclaimer"), { exact: true }), scenario).toHaveCount(1);
    }
  });

  test("aNowResultPastTheFirstPageStillSaysNotADoctor", async ({ page }) => {
    // The thread's first page holds only messages; the NOW paper is older and shows only in the attention stack.
    const thread = answerOf<ThreadJson>("chat-now-past-the-page", "GET /app/api/patients/{id}/thread");
    expect(thread.items.length, "the thread holds messages").toBeGreaterThan(0);
    expect(thread.items.filter((i) => i.kind !== "message"), "and no paper").toEqual([]);
    const stack = answerOf<AttentionJson>("chat-now-past-the-page", "GET /app/api/patients/{id}/attention");
    expect(stack.items.map((i) => (i.kind === "urgent" ? i.level : i.kind))).toEqual(["now"]);
    await open(page, "chat-now-past-the-page");
    await expect(page.locator("[data-attention='urgent'] .verdict-card--now:visible")).toHaveCount(1);
    await expect(page.getByText(t("bn", "disclaimer"), { exact: true })).toHaveCount(1);
    await expect(page.locator(".verdict-card__notdoctor:visible")).toHaveText(t("bn", "now.notdoctor"));
  });
});

test.describe("without JavaScript", () => {
  test.use({ javaScriptEnabled: false });

  test("aNowResultIsReadableWithoutJavaScript", async ({ page }) => {
    const banner = answerOf<NowBannerJson>("chat-now", "GET /app/api/now-banner");
    await open(page, "chat-now");
    await expect(page.locator(".now-banner__line")).toHaveText(t("bn", banner.line_key as CopyKey));
    const card = page.locator("[data-thread-item='paper'] article.verdict-card");
    await expect(card.locator(".verdict-card__band")).toHaveCount(1);
    await expect(card).toContainText(t("bn", "now.notdoctor"));
    await expect(page.getByRole("button", { name: t("bn", "now.banner.seen") }), "no seen button without JavaScript").toHaveCount(0);
    // And the composer is no form that could post the typed text anywhere.
    expect(await page.locator("form").count()).toBe(0);
    await expect(page.locator(".composer__nojs")).toHaveText(t("bn", "chat.composer.nojs"));
  });

  test("withoutJavaScriptThereIsNoFieldAndTheLineSaysHow", async ({ page }) => {
    // DR-42 item 1: no field and no form that could post a question; the standing line says how to ask; and a stored
    // answer, which the server renders, is readable as it was shown.
    const recorder = await open(page, "chat-with-asks");
    expect(await page.locator("input").count(), "inputs").toBe(0);
    expect(await page.locator("form").count(), "forms").toBe(0);
    await expect(page.locator(".composer__nojs")).toHaveText(t("bn", "chat.composer.nojs"));
    const stored = answerOf<ThreadJson>("chat-with-asks", "GET /app/api/patients/{id}/thread").items.find((i) => i.kind === "ask");
    expect(stored?.kind, "with-asks holds an ask").toBe("ask");
    if (stored?.kind !== "ask") return;
    const answers = await page.locator("[data-thread-item='ask'] .message-bubble--recv .message-bubble__body").allInnerTexts();
    expect(answers.length, "the stored answers on the page").toBeGreaterThan(0);
    // Drawn as sent, line for line, without JavaScript too (innerText: what is on screen, finding 3).
    drawnAsSent(answers.at(-1)!, stored.answer, "the newest stored answer");
    expect(recorder.requests().filter((r) => r.method !== "GET"), "nothing posted").toEqual([]);
  });
});

// The routed page's content checks, both schemes, with each scenario's longest twin.
const nowRows = (answerOf<ThreadJson>("chat-now", "GET /app/api/patients/{id}/thread").items.flatMap((i) =>
  i.kind === "paper" && i.card.status === "DONE" ? i.card.values : [],
) ?? []) as CropRow[];
const followUps = answerOf<AttentionJson>("chat-stack", "GET /app/api/patients/{id}/attention").items.flatMap((i) =>
  i.kind === "follow_up" ? [{ crop: { src: i.crop.src, width: null, height: null } }] : [],
);
// chat-default's thread holds no value row, so it has no crop check (cropRowsReport refuses an empty fixture: it would
// check nothing); chat-now's does, and its twin keeps that thread. chat-stack shows the follow-up's crop outside a row.
test.describe("chat-default", () => screenChecks("/app/home", "chat-default", { longest: true }));
test.describe("chat-now", () => screenChecks("/app/home", "chat-now", { longest: true, crops: nowRows }));
test.describe("chat-stack", () => screenChecks("/app/home", "chat-stack", { images: followUps }));
test.describe("chat-first", () => screenChecks("/app/home", "chat-first"));
test.describe("chat-now-past-the-page", () => screenChecks("/app/home", "chat-now-past-the-page"));
// Plan CHAT-web: the stored questions and answers (and their longest twin), and the page after an emergency answer was
// drawn in place (the answer bubble and its 999 call judged like the rest of the page).
test.describe("chat-with-asks", () => screenChecks("/app/home", "chat-with-asks", { longest: true }));
test.describe("chat-ask-emergency", () =>
  screenChecks("/app/home", "chat-ask-emergency", {
    act: {
      state: "an emergency answer",
      run: async (page) => {
        await ask(page, answerOf<AskJson>("chat-ask-emergency", "POST /app/api/patients/{id}/ask").question);
      },
    },
  }));
// Review of PR 166: a dose answer with its quoted line's crop, as it arrives and after a reload (finding 5: each crop
// decodes at its own width, and no other image is on the page); a first-run patient who asked (findings 1, 13); the
// long answer drawn in place (finding 9); and the session that ended under a question (finding 14).
const doseCrops = (medicines: { crop: { src: string } }[]): CropRow[] =>
  medicines.map((m) => ({ crop: { src: m.crop.src, width: null, height: null } }));
const askedDose = answerOf<AskJson>("chat-ask-dose", "POST /app/api/patients/{id}/ask");
test.describe("chat-ask-dose", () =>
  screenChecks("/app/home", "chat-ask-dose", {
    images: doseCrops(askedDose.medicines),
    act: {
      state: "a dose answer",
      run: async (page) => {
        await ask(page, askedDose.question);
      },
    },
  }));
test.describe("chat-with-asks-dose", () =>
  screenChecks("/app/home", "chat-with-asks-dose", {
    images: answerOf<ThreadJson>("chat-with-asks-dose", "GET /app/api/patients/{id}/thread").items.flatMap((i) =>
      i.kind === "ask" ? doseCrops(i.medicines) : [],
    ),
  }));
test.describe("chat-first-with-asks", () => screenChecks("/app/home", "chat-first-with-asks"));
test.describe("chat-ask-long", () =>
  screenChecks("/app/home", "chat-ask-long", {
    act: {
      state: "a long answer",
      run: async (page) => {
        await ask(page, answerOf<AskJson>("chat-ask-long", "POST /app/api/patients/{id}/ask").question);
      },
    },
  }));
test.describe("chat-ask-401", () =>
  screenChecks("/app/home", "chat-ask-401", {
    act: {
      state: "a question after the session ended",
      run: async (page) => {
        await ask(page, answerOf<AskJson>("chat-ask-emergency", "POST /app/api/patients/{id}/ask").question);
      },
    },
  }));

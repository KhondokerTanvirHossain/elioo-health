import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test, type Page } from "@playwright/test";
import { t, type CopyKey } from "../src/copy";
import { useScenario } from "./support/browser-api";
import { FIXTURES } from "./support/scenarios.mjs";
import { screenChecks } from "./support/screen-checks";

/*
 * The sign-in pages (plan W3) against stub Java: `/app` and `/app/` (the welcome and the number), `/app/verify` (the
 * code). Every answer is a fixture Java's contract tests wrote (fixtures/api/me, fixtures/api/sign-in, W3-api), and
 * each assertion reads the field it is about: the number shown back is the fixture's `number_display`, the place a
 * signed-in visitor lands is the fixture's `next`.
 */

const body = <T>(fixture: string): T => (JSON.parse(readFileSync(join(FIXTURES, fixture), "utf8")) as { body: T }).body;
const seam = JSON.parse(readFileSync(join(FIXTURES, "..", "seams", "noscript-href.json"), "utf8")) as { held: string; href: string };

async function typeNumber(page: Page, number: string) {
  await page.locator('input[name="whatsapp_number"]').fill(number);
  await page.getByRole("button", { name: t("bn", "login.next") }).click();
}

/** Says yes to the number, and waits for the page to leave for the code page (stub Java's state stays its own). */
async function confirmAndLeave(page: Page) {
  const leaving = page.waitForRequest((r) => r.isNavigationRequest() && new URL(r.url()).pathname === "/app/verify");
  await page.getByRole("button", { name: t("bn", "login.number.yes") }).click();
  await leaving;
}

test.describe("the number page", () => {
  test("bothSpellingsOfTheSignInPageAnswer200", async ({ page }) => {
    await useScenario(page, "signed-out");
    for (const path of ["/app", "/app/"]) {
      const response = await page.request.get(path, { maxRedirects: 0 });
      expect(response.status(), `${path} is the page itself, not a redirect`).toBe(200);
      expect(await response.text(), `${path} holds the number field`).toContain('name="whatsapp_number"');
    }
  });

  test("aSignedInVisitorIsSentToJavasCheckedPlace", async ({ page }) => {
    const recorder = await useScenario(page, "signed-in");
    const response = await page.request.get("/app/?next=%2Fapp%2Fdocuments%2Fx", { maxRedirects: 0 });
    expect(response.status()).toBe(307);
    expect(response.headers()["location"]).toBe(body<{ next: string }>("sign-in/next-held.json").next);
    const asked = (await recorder.java()).filter((r) => r.path === "/app/api/sign-in/next");
    expect(asked.map((r) => [r.method, r.query])).toEqual([["GET", "next=%2Fapp%2Fdocuments%2Fx"]]);
    // Next forwards the cookie and nothing else a browser sent.
    expect(asked[0]!.headerNames).not.toContain("origin");
    expect(asked[0]!.headerNames).not.toContain("x-medioo-preview");

    const hostile = await useScenario(page, "signed-in-hostile");
    const landed = await page.request.get("/app/?next=%2F%2Fevil.example", { maxRedirects: 0 });
    expect(landed.headers()["location"]).toBe(body<{ next: string }>("sign-in/next-hostile.json").next);
    expect(landed.headers()["location"]).toBe("/app/home");
    // The hop never goes through the no-JS fallback: no request of this test named /app/basic.
    expect((await hostile.java()).filter((r) => r.path.startsWith("/app/basic"))).toEqual([]);
  });

  test("theNumberIsShownBackBeforeAnythingIsSent", async ({ page }) => {
    const recorder = await useScenario(page, "signed-out");
    await page.goto("/app");
    await typeNumber(page, "01700000123");
    const shown = body<{ confirm: { number_display: string } }>("sign-in/confirm.json").confirm.number_display;
    await expect(page.locator("[data-number-back]")).toHaveText(shown);
    await expect(page.getByRole("button", { name: t("bn", "login.number.yes") })).toBeVisible();
    const first = recorder.requests().filter((r) => r.path === "/app/api/sign-in/request");
    expect(first.length, "one request so far").toBe(1);
    expect(first[0]!.body).toEqual({ whatsapp_number: "01700000123" });
    expect(first[0]!.headers["x-medioo"]).toBe("1");

    await confirmAndLeave(page);
    const both = recorder.requests().filter((r) => r.path === "/app/api/sign-in/request");
    expect(both.map((r) => (r.body as { confirmed?: boolean }).confirmed)).toEqual([undefined, true]);
  });

  test("anInvalidNumberIsSaidUnderTheField", async ({ page }) => {
    await useScenario(page, "signed-out-invalid");
    await page.goto("/app");
    await typeNumber(page, "12");
    const input = page.locator('input[name="whatsapp_number"]');
    await expect(input).toHaveAttribute("aria-invalid", "true");
    await expect(page.locator(".phone-field__error")).toHaveText(t("bn", "login.number.invalid"));
  });

  test("theNextIsSentToJavaUnjudged", async ({ page }) => {
    const recorder = await useScenario(page, "signed-out");
    await page.goto("/app/?next=%2F%2Fevil.example");
    await typeNumber(page, "01700000123");
    await confirmAndLeave(page);
    const sent = recorder.requests().filter((r) => r.path === "/app/api/sign-in/request");
    expect(sent.map((r) => (r.body as { next?: string }).next)).toEqual(["//evil.example", "//evil.example"]);
  });

  test("aNotSentAnswerAlsoGoesToTheCodePage", async ({ page }) => {
    await useScenario(page, "signed-out-not-sent");
    await page.goto("/app");
    await typeNumber(page, "01700000123");
    await confirmAndLeave(page);
  });
});

test.describe("the code page", () => {
  const sentLine = t("bn", "login.code.sent");

  test("sentSaysSentAndNotSentNeverDoes", async ({ page }) => {
    await useScenario(page, "state-pending-sent");
    await page.goto("/app/verify");
    await expect(page.getByRole("status").filter({ hasText: sentLine })).toHaveText(sentLine);

    await useScenario(page, "state-pending-not-sent");
    await page.goto("/app/verify");
    const state = body<{ wa_link: string; number_tail: string }>("sign-in/state-pending-not-sent.json");
    await expect(page.locator(".sign-in__lede")).toHaveText(`${t("bn", "login.code.say")} ${t("bn", "login.code.word")}`);
    await expect(page.locator(".sign-in__wa")).toHaveAttribute("href", state.wa_link);
    expect(await page.locator("body").innerText()).not.toContain(sentLine);

    await useScenario(page, "state-pending-not-sent-no-link");
    await page.goto("/app/verify");
    await expect(page.locator(".sign-in__lede")).toHaveText(`${t("bn", "login.code.say")} ${t("bn", "login.code.word")}`);
    await expect(page.locator('a[href^="https://wa.me/"]')).toHaveCount(0);
    expect(await page.locator("body").innerText()).not.toContain(sentLine);
  });

  for (const [scenario, fixture, key] of [
    ["state-code-wrong", "code-wrong", "login.bad.wrong"],
    ["state-code-expired", "code-expired", "login.bad.expired"],
    ["state-code-burned", "code-burned", "login.bad.burned"],
    ["state-code-format", "code-format", "login.bad.format"],
  ] as const) {
    test(`eachCodeRefusalIsItsOwnLine ${fixture}`, async ({ page }) => {
      await useScenario(page, scenario);
      await page.goto("/app/verify");
      await page.locator('input[name="code"]').fill("123456");
      await page.getByRole("button", { name: t("bn", "login.enter") }).click();
      // BURNED says how many wrong tries it took: the number the state GET carried, filled on the server.
      const attempts = body<{ max_attempts: number }>("sign-in/state-pending-sent.json").max_attempts;
      const want = key === "login.bad.burned" ? t("bn", key, attempts) : t("bn", key as CopyKey);
      expect(body<{ reason: string }>(`sign-in/${fixture}.json`).reason).toMatch(/^CODE_/);
      await expect(page.locator(".otp-input__error")).toHaveText(want);
      await expect(page.locator('input[name="code"]')).toHaveAttribute("aria-invalid", "true");
    });
  }

  test("restartGoesBackToTheNumber", async ({ page }) => {
    await useScenario(page, "state-restart");
    await page.goto("/app/verify");
    await page.locator('input[name="code"]').fill("123456");
    await page.getByRole("button", { name: t("bn", "login.enter") }).click();
    await page.waitForURL((url) => url.pathname === "/app/");
    await expect(page.locator('input[name="whatsapp_number"]')).toBeVisible();
  });

  test("verifiedOpensTheHeldPlace", async ({ page }) => {
    await useScenario(page, "state-pending-sent");
    await page.goto("/app/verify");
    // The held place is Java's page; this suite has no Java pages, so the navigation itself is what is checked.
    await page.route("**/app/documents/**", (route) => route.fulfill({ status: 200, contentType: "text/html", body: "<p>held</p>" }));
    await page.locator('input[name="code"]').fill("123456");
    await page.getByRole("button", { name: t("bn", "login.enter") }).click();
    const next = body<{ next: string }>("sign-in/verified.json").next;
    await page.waitForURL((url) => url.pathname === next);
    expect(new URL(page.url()).pathname).toBe(next);
  });

  test("theCodePageSurvivesALeaveToWhatsApp", async ({ page }) => {
    await useScenario(page, "state-pending-not-sent");
    await page.goto("/app/verify");
    const tail = body<{ number_tail: string }>("sign-in/state-pending-not-sent.json").number_tail;
    await expect(page.locator(".sign-in__mask")).toHaveText(t("bn", "login.code.number", "••••••••" + tail));
    await expect(page.locator(".sign-in__lede")).toContainText(t("bn", "login.code.say"));

    await useScenario(page, "state-none");
    const none = await page.request.get("/app/verify", { maxRedirects: 0 });
    expect(none.status()).toBe(307);
    expect(none.headers()["location"]).toBe("/app/");

    // Under the preview path the redirect stays inside the preview (a preview family, signed in).
    await useScenario(page, "preview-state-none");
    const preview = await page.request.get("/app/preview/verify", { maxRedirects: 0 });
    expect(preview.status()).toBe(307);
    expect(preview.headers()["location"]).toBe("/app/preview/");
  });
});

test.describe("the preview gate", () => {
  test("aNonPreviewFamilyOnAPreviewPathReadsOnlyMe", async ({ page }) => {
    const recorder = await useScenario(page, "preview-off");
    const response = await page.request.get("/app/preview", { maxRedirects: 0 });
    expect(response.status()).toBe(404);
    expect((await recorder.java()).map((r) => r.path)).toEqual(["/app/api/me"]);
  });

  test("aPreviewFamilyIsSentOnFromThePreview", async ({ page }) => {
    const recorder = await useScenario(page, "preview-signed-in");
    const response = await page.request.get("/app/preview?next=%2Fapp%2Fdocuments%2Fx", { maxRedirects: 0 });
    expect(response.status()).toBe(307);
    // The held paper is Java's page, so the preview does not wrap it.
    expect(response.headers()["location"]).toBe(body<{ next: string }>("sign-in/next-held.json").next);
    expect((await recorder.java()).map((r) => r.path)).toEqual(["/app/api/me", "/app/api/sign-in/next"]);
  });
});

test.describe("without JavaScript", () => {
  test.use({ javaScriptEnabled: false });

  test("theNoScriptLinkCarriesTheHeldPlace", async ({ page }) => {
    await useScenario(page, "signed-out");
    expect(seam.href.startsWith("/app/basic?next=%2F"), "the seam is a held place under /app/basic").toBe(true);
    await page.goto(`/app/?next=${encodeURIComponent(seam.held)}`);
    const link = page.locator("noscript a, a.sign-in__nojs");
    await expect(link).toHaveCount(1);
    await expect(link).toHaveAttribute("href", seam.href);
    await expect(link).toHaveText(t("bn", "login.nojs"));
    // The number field without JavaScript is in no form: nothing it holds can be submitted into a URL.
    expect(await page.locator("form").count()).toBe(0);
  });
});

test.describe("Java failing", () => {
  // Visited so the `logs` project can read what the Next server logged about it (server-log.spec.ts).
  test("aJavaErrorIsAnErrorPageAndNamesNothing", async ({ page }) => {
    await useScenario(page, "java-502");
    const gate = await page.request.get("/app/preview?next=%2Fapp%2Fdocuments%2Fx", { maxRedirects: 0 });
    expect(gate.status(), "me answered 502 behind the preview gate").toBe(500);
    await useScenario(page, "next-500");
    const held = await page.request.get("/app/?next=%2Fapp%2Fdocuments%2Fx", { maxRedirects: 0 });
    expect(held.status(), "the held-place read answered 500").toBe(500);
    // The error page carries no Java body either.
    expect(await held.text()).not.toContain("failed for");
  });
});

screenChecks("/app", "signed-out", { fields: ["phone-field__input"] });
screenChecks("/app/verify", "state-pending-sent", { fields: ["otp-input"] });
screenChecks("/app/verify", "state-pending-not-sent");

import { cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { JavaAnswer } from "@/api/java";
import type { MeJson, PaperResultJson } from "@/api/types";
import { t, type CopyKey } from "@/copy";
import { href } from "@/routing/paths";
import { NOW_ALLOWED } from "@/styles/now-usage";
import potas from "../../../../../../fixtures/api/documents/proto-potas.json";
import followUps from "../../../../../../fixtures/api/follow-ups/proto-abbu.json";
import proto from "../../../../../../fixtures/api/me/proto.json";
import nowBanner from "../../../../../../fixtures/api/now-banner/proto.json";
import thread from "../../../../../../fixtures/api/thread/proto-abbu.json";

/*
 * `/app/documents/{id}` on the desktop layout (plan desktop-parity Task 8; Review Focus 1): a paper opened from a
 * WhatsApp link lands on the talk-and-papers screen with that paper in the viewer, its card outlined in the
 * conversation and its details panel already open, under the NOW bar. Never just the image. Below 900 px, and for
 * every live request until the flip, today's paper page, unchanged. Over the prototype family as Java wrote it: Abbu's
 * potassium paper is NOW, with call_999 (fixtures/api/documents/proto-potas.json).
 */

const ME = proto.body as MeJson;
const POTAS = potas.body as PaperResultJson;
const ABBU = ME.patients.find((p) => p.id === POTAS.patient.id)!;
const ctx = { lang: "bn" as const, preview: true, here: `/app/preview/documents/${POTAS.id}` };
let reads: string[] = [];
let marker: string | undefined;

vi.mock("@/api/request", () => ({ requestContext: async () => ctx }));
vi.mock("next/headers", () => ({
  cookies: async () => ({ get: (name: string) => (name === "baymax_banner_only" && marker !== undefined ? { value: marker } : undefined) }),
}));
vi.mock("next/navigation", async (actual) => ({
  ...(await actual<typeof import("next/navigation")>()),
  useRouter: () => ({ refresh: () => undefined, push: () => undefined }),
}));
vi.mock("@/api/browser", () => ({ apiGet: vi.fn(async () => ({ kind: "ok", status: 200, body: { status: "DONE" } })), apiSend: vi.fn() }));
vi.mock("@/api/java", async (actual) => ({
  ...(await actual<typeof import("@/api/java")>()),
  javaGet: async (path: string): Promise<JavaAnswer<unknown>> => {
    reads.push(path);
    if (path === "/app/api/me") return { status: 200, body: ME };
    if (path === "/app/api/now-banner") return { status: 200, body: nowBanner.body };
    if (path === `/app/api/documents/${POTAS.id}`) return { status: 200, body: POTAS };
    if (path.startsWith(`/app/api/patients/${ABBU.id}/thread`)) return { status: 200, body: thread.body };
    if (path === `/app/api/patients/${ABBU.id}/follow-ups`) return { status: 200, body: followUps.body };
    throw new Error(`unexpected read ${path}`);
  },
}));

const { default: PaperPage } = await import("./page");

beforeEach(() => {
  reads = [];
  marker = undefined;
  ctx.preview = true;
  ctx.here = `/app/preview/documents/${POTAS.id}`;
  vi.stubGlobal("location", { pathname: ctx.here, search: "", assign: vi.fn(), reload: vi.fn() });
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const open = async (id = POTAS.id) =>
  render(await PaperPage({ params: Promise.resolve({ id }), searchParams: Promise.resolve({}) })).container;

/** The NOW banner's bar modifier (NowBanner variant "bar"), read off the class list the safety stylesheet allows. */
const BAR = ["now", "banner--bar"].join("-");

const count = (text: string, part: string) => text.split(part).length - 1;

/** The elements whose whole text is `sentence`, innermost: a warning line, not a message that quotes it among others. */
const warningLines = (root: ParentNode, sentence: string) =>
  [...root.querySelectorAll<HTMLElement>("*")].filter(
    (e) => e.textContent?.trim() === sentence && ![...e.children].some((c) => c.textContent?.trim() === sentence),
  );

describe("/app/documents/{id} on the desktop layout", () => {
  it("aNowPaperFromALinkOpensWithItsPanelAndItsWarning", async () => {
    const container = await open();
    const desk = container.querySelector<HTMLElement>(".desktop-shell");
    expect(desk, "the desktop screen, not today's paper page alone").not.toBeNull();
    const sentence = t("bn", POTAS.banner!.line_key as CopyKey);

    // The bar: the patient's name first, then Java's sentence verbatim.
    const bar = desk!.querySelector<HTMLElement>(".desktop-shell__bar [role=alert]");
    expect(bar, "the NOW bar").not.toBeNull();
    expect(bar!.classList.contains(BAR), "the bar, not the block").toBe(true);
    const said = bar!.textContent ?? "";
    expect(said.indexOf(ABBU.name), "the name is in the bar").toBeGreaterThanOrEqual(0);
    expect(said.indexOf(ABBU.name), "the name before the sentence").toBeLessThan(said.indexOf(sentence));

    // The viewer shows this paper; the conversation outlines its red card, with 999.
    expect(desk!.querySelector(".desktop-shell__paper [data-paper]")!.getAttribute("data-paper")).toBe(POTAS.id);
    const item = desk!.querySelector<HTMLElement>(`.desktop-shell__talk [data-talk-item="paper"][data-paper="${POTAS.id}"]`);
    expect(item, "the paper's card in the conversation").not.toBeNull();
    const card = item!.querySelector<HTMLElement>(".verdict-card");
    expect(card, "the paper's card").not.toBeNull();
    expect([...card!.classList].some((c) => NOW_ALLOWED.test(`.${c}`)), "the red card").toBe(true);
    expect(card!.classList.contains("verdict-card--selected"), "outlined: it is the paper in view").toBe(true);
    expect(card!.querySelector(".verdict-card__band")!.textContent).toBe(sentence);
    expect(card!.querySelector('a[href="tel:999"]'), "999 on the card").not.toBeNull();

    // The panel is already open, with the paper's NOW block, and its close link keeps the paper in view.
    const panel = desk!.querySelector<HTMLElement>(".desktop-shell__panel");
    expect(panel, "the details panel, open").not.toBeNull();
    expect(panel!.querySelector("[data-now-block]"), "the panel's NOW block").not.toBeNull();
    expect(panel!.querySelector("a.paper-panel__close")!.getAttribute("href")).toBe(
      href(`/app/home?patient=${ABBU.id}&paper=${POTAS.id}`, true),
    );

    // The warning twice on the screen, the bar and the card: the panel's NOW block uses its own keys, and the
    // attention stack's urgent card is not drawn in any column. Counted as warning lines, each an element saying the
    // sentence and nothing else: Medioo's released message quotes it among its own lines, on the card and in the panel
    // as on today's page, and that message is the gate's block, never dropped.
    const lines = warningLines(desk!, sentence);
    expect(lines.length, "warning lines on the desktop screen").toBe(2);
    expect(bar!.contains(lines[0]!) || bar!.contains(lines[1]!), "one is the bar's").toBe(true);
    expect(card!.contains(lines[0]!) || card!.contains(lines[1]!), "one is the card's").toBe(true);
    expect(count(desk!.textContent ?? "", sentence), "and the released message is shown where today's page shows it").toBe(
      2 + count(POTAS.wording ?? "", sentence) * 2,
    );
    expect(desk!.querySelector(".attention-stack"), "no attention stack on the desktop").toBeNull();

    // Below 900 px: today's paper page, whole, in the phone shell.
    expect(container.querySelector(".app-shell--phone article.paper [data-now-block]"), "today's page below 900 px").not.toBeNull();
    const ids = [...container.querySelectorAll("[id]")].map((e) => e.id);
    expect(ids.length).toBeGreaterThan(5);
    expect(ids.filter((id, i) => ids.indexOf(id) !== i), "ids said twice").toEqual([]);
    // One read of the paper: the thread's card is not a second DOCUMENT_VIEW.
    expect(reads.filter((r) => r.startsWith("/app/api/documents/")).length).toBe(1);
  });

  it("aLiveLinkIsTodaysPaperPage", async () => {
    ctx.preview = false;
    ctx.here = `/app/documents/${POTAS.id}`;
    const container = await open();
    expect(container.querySelector(".desktop-shell"), "no desktop for a live request").toBeNull();
    expect(container.querySelector(".app-shell--phone"), "today's shell at every width").toBeNull();
    expect(container.querySelector("article.paper [data-now-block]")).not.toBeNull();
    expect(reads.some((r) => r.includes("/thread")), "no read only the desktop needs").toBe(false);
  });

  it("aBannerOnlyRefreshKeepsTheDesktopAndReadsNoPaper", async () => {
    // A stale "seen" tap re-reads the page banner-only (banner-only.ts): no paper read, no thread read, and the desktop
    // screen stays, holding what it showed, under the banner as Java now says it.
    marker = encodeURIComponent(JSON.stringify({ path: ctx.here, patient: ABBU.id }));
    const container = await open();
    expect(reads.some((r) => r.startsWith("/app/api/documents/")), "no paper read").toBe(false);
    expect(reads.some((r) => r.includes("/thread")), "no thread read").toBe(false);
    const desk = container.querySelector(".desktop-shell");
    expect(desk, "the desktop screen stays").not.toBeNull();
    expect(desk!.querySelector(".desktop-shell__bar [role=alert]")?.classList.contains(BAR), "the bar, re-read").toBe(true);
    expect(desk!.querySelector(".desktop-shell__panel"), "the panel's place is kept").not.toBeNull();
  });
});

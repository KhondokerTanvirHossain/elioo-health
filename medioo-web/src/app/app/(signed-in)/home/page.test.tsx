import { cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { JavaAnswer } from "@/api/java";
import type { MeJson, PaperJson, ThreadJson } from "@/api/types";
import { href } from "@/routing/paths";
import attention from "../../../../../fixtures/api/attention/proto-amma.json";
import cbc from "../../../../../fixtures/api/documents/proto-cbc.json";
import potas from "../../../../../fixtures/api/documents/proto-potas.json";
import followUps from "../../../../../fixtures/api/follow-ups/proto-amma.json";
import proto from "../../../../../fixtures/api/me/proto.json";
import nowBanner from "../../../../../fixtures/api/now-banner/proto.json";
import ammaThread from "../../../../../fixtures/api/thread/proto-amma.json";

/*
 * `/app/home` on the desktop layout (plan desktop-parity Task 8): the talk-and-papers screen. `?paper=` names the paper
 * in the viewer (the newest when none), whose card the conversation outlines; `panel=1` opens its details over the
 * conversation, and the panel's close drops `panel`. Below 900 px, and on every live request until the flip, today's
 * chat, unchanged. Over the prototype family as Java wrote it (Amma: five papers, the newest a retake).
 */

const ME = proto.body as MeJson;
const AMMA = ME.patients.find((p) => p.age === 68)!;
const THREAD = ammaThread.body as ThreadJson;
const CARDS = THREAD.items.flatMap((i) => (i.kind === "paper" ? [i.card] : []));
const NEWEST = CARDS[0]!;
const CBC = cbc.body as PaperJson;
const ctx = { lang: "bn" as const, preview: true, here: "/app/preview/home" };
let reads: string[] = [];
let thread: ThreadJson = THREAD;

vi.mock("@/api/request", () => ({ requestContext: async () => ctx }));
vi.mock("next/navigation", async (actual) => ({
  ...(await actual<typeof import("next/navigation")>()),
  useRouter: () => ({ refresh: () => undefined, push: () => undefined }),
}));
vi.mock("@/api/browser", () => ({ apiGet: vi.fn(async () => ({ kind: "ok", status: 200, body: { status: "DONE" } })), apiSend: vi.fn() }));
vi.mock("@/api/java", async (actual) => ({
  ...(await actual<typeof import("@/api/java")>()),
  javaGet: async (path: string): Promise<JavaAnswer<unknown>> => {
    reads.push(path);
    if (path === "/app/api/me") return { status: 200, body: { ...ME, active_patient_id: AMMA.id } };
    if (path === "/app/api/now-banner") return { status: 200, body: nowBanner.body };
    if (path === `/app/api/patients/${AMMA.id}/attention`) return { status: 200, body: attention.body };
    if (path.startsWith(`/app/api/patients/${AMMA.id}/thread`)) return { status: 200, body: thread };
    if (path === `/app/api/patients/${AMMA.id}/follow-ups`) return { status: 200, body: followUps.body };
    if (path === `/app/api/documents/${CBC.id}`) return { status: 200, body: CBC };
    if (path === `/app/api/documents/${potas.body.id}`) return { status: 200, body: potas.body };
    throw new Error(`unexpected read ${path}`);
  },
}));

const { default: HomePage } = await import("./page");

beforeEach(() => {
  reads = [];
  thread = THREAD;
  ctx.preview = true;
  ctx.here = "/app/preview/home";
  vi.stubGlobal("location", { pathname: ctx.here, search: "", assign: vi.fn(), reload: vi.fn() });
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const home = async (query: Record<string, string> = {}) =>
  render(await HomePage({ searchParams: Promise.resolve(query) })).container;

const inView = (root: ParentNode) => root.querySelector(".desktop-shell__paper [data-paper]")?.getAttribute("data-paper");
const outlined = (root: ParentNode) =>
  [...root.querySelectorAll(".desktop-shell__talk [data-talk-selected]")].map((e) => e.getAttribute("data-paper"));
const paperLink = (id: string) => href(`/app/home?patient=${AMMA.id}&paper=${id}`, true);

describe("/app/home on the desktop layout", () => {
  it("homeWithoutPaperSelectsTheNewest", async () => {
    const container = await home();
    expect(container.querySelector(".desktop-shell"), "the desktop screen").not.toBeNull();
    expect(inView(container), "the newest paper in the viewer").toBe(NEWEST.id);
    expect(outlined(container), "and its card outlined").toEqual([NEWEST.id]);
    expect(container.querySelector(".desktop-shell__panel"), "no panel unless asked").toBeNull();
    // The strip and the cards go to this screen with that paper named.
    for (const card of CARDS) {
      expect(container.querySelector(`.desktop-shell__paper a[href="${paperLink(card.id)}"]`), `strip: ${card.id}`).not.toBeNull();
      expect(container.querySelector(`.desktop-shell__talk a[href="${paperLink(card.id)}"]`), `card: ${card.id}`).not.toBeNull();
    }
    expect(container.querySelector(".desktop-shell .attention-stack"), "no attention stack on the desktop").toBeNull();
  });

  it("aNamedPaperIsInTheViewerAndItsPanelOpensAndCloses", async () => {
    const container = await home({ patient: AMMA.id, paper: CBC.id, panel: "1" });
    expect(inView(container)).toBe(CBC.id);
    expect(outlined(container)).toEqual([CBC.id]);
    const panel = container.querySelector(".desktop-shell__panel");
    expect(panel, "panel=1 opens the panel").not.toBeNull();
    expect(panel!.querySelector("article.paper")!.getAttribute("data-paper")).toBe(CBC.id);
    // Its close keeps the paper and drops the panel.
    expect(panel!.querySelector("a.paper-panel__close")!.getAttribute("href")).toBe(paperLink(CBC.id));
    cleanup();
    const shut = await home({ patient: AMMA.id, paper: CBC.id });
    expect(shut.querySelector(".desktop-shell__panel"), "without panel=1, no panel").toBeNull();
    expect(inView(shut)).toBe(CBC.id);
  });

  it("aPaperBeyondTheThreadsFirstPageIsReadAndShown", async () => {
    thread = { ...THREAD, items: THREAD.items.filter((i) => !(i.kind === "paper" && i.card.id === CBC.id)) };
    const container = await home({ patient: AMMA.id, paper: CBC.id });
    expect(reads).toContain(`/app/api/documents/${CBC.id}`);
    expect(inView(container)).toBe(CBC.id);
    expect(outlined(container), "its card is drawn in the conversation too").toEqual([CBC.id]);
  });

  it("anotherPatientsPaperIsNeverShownAsThisPatients", async () => {
    // Abbu's paper named on Amma's screen: the viewer stays on Amma's newest, and Abbu's card is drawn nowhere.
    const container = await home({ patient: AMMA.id, paper: potas.body.id });
    expect(inView(container), "the viewer stays on this patient's newest paper").toBe(NEWEST.id);
    expect(container.querySelector(`.desktop-shell [data-paper="${potas.body.id}"]`)).toBeNull();
  });

  it("aNamedPaperOnTheFirstPageIsNotReadAgain", async () => {
    await home({ patient: AMMA.id, paper: CBC.id });
    expect(reads.some((r) => r.startsWith("/app/api/documents/")), "the thread's card is the paper").toBe(false);
  });

  it("belowNineHundredHomeIsTodaysChat", async () => {
    // Live: today's chat and nothing else.
    ctx.preview = false;
    ctx.here = "/app/home";
    const live = await home();
    expect(live.querySelector(".desktop-shell")).toBeNull();
    // React's own ids (useId) count the components rendered before; they are the only other difference.
    const ids = (html: string) => html.replace(/_r_\w+_/g, "_r_");
    const today = ids(live.querySelector("[data-page=chat]")!.outerHTML);
    cleanup();
    // Preview: the same chat, in the phone shell that stands below 900 px. Only the preview's own links differ.
    ctx.preview = true;
    ctx.here = "/app/preview/home";
    const desk = await home();
    const phone = desk.querySelector(".app-shell--phone [data-page=chat]");
    expect(phone, "today's chat below 900 px").not.toBeNull();
    const unpreviewed = ids(phone!.outerHTML.replaceAll('href="/app/preview/', 'href="/app/'));
    expect(unpreviewed).toBe(today);
    expect(desk.querySelector(".desktop-shell [data-page=chat]"), "not inside the desktop screen").toBeNull();
  });
});

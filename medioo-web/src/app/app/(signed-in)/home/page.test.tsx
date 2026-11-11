import { act, cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from "vitest";
import type { JavaAnswer } from "@/api/java";
import type { MeJson, NowBannerJson, PaperJson, ThreadJson } from "@/api/types";
import { t, type CopyKey } from "@/copy";
import { deskAddHref, href } from "@/routing/paths";
import { NOW_ALLOWED } from "@/styles/now-usage";
import abbuAttention from "../../../../../fixtures/api/attention/proto-abbu.json";
import attention from "../../../../../fixtures/api/attention/proto-amma.json";
import cbc from "../../../../../fixtures/api/documents/proto-cbc.json";
import potas from "../../../../../fixtures/api/documents/proto-potas.json";
import abbuFollowUps from "../../../../../fixtures/api/follow-ups/proto-abbu.json";
import followUps from "../../../../../fixtures/api/follow-ups/proto-amma.json";
import proto from "../../../../../fixtures/api/me/proto.json";
import nowBanner from "../../../../../fixtures/api/now-banner/proto.json";
import abbuThread from "../../../../../fixtures/api/thread/proto-abbu.json";
import ammaThread from "../../../../../fixtures/api/thread/proto-amma.json";
import uploadOptions from "../../../../../fixtures/api/upload-options/owner-one.json";

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
const POTAS = potas.body as PaperJson;
const ABBU = ME.patients.find((p) => p.id === POTAS.patient.id)!;
const ctx = { lang: "bn" as const, preview: true, here: "/app/preview/home" };
let reads: string[] = [];
let thread: ThreadJson = THREAD;
let abbusThread: ThreadJson = abbuThread.body as ThreadJson;
let shown: { id: string } = AMMA;
let banner: NowBannerJson = nowBanner.body as NowBannerJson;
/** Amma is the viewer's own (true) or shared with them (false: a share member). */
let ammaOwned = true;

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
    if (path === "/app/api/me") {
      const patients = ME.patients.map((p) => (p.id === AMMA.id ? { ...p, owner: ammaOwned } : p));
      return { status: 200, body: { ...ME, patients, active_patient_id: shown.id } };
    }
    if (path === "/app/api/now-banner") return { status: 200, body: banner };
    if (path === `/app/api/patients/${AMMA.id}/attention`) return { status: 200, body: attention.body };
    if (path.startsWith(`/app/api/patients/${AMMA.id}/thread`)) return { status: 200, body: thread };
    if (path === `/app/api/patients/${AMMA.id}/follow-ups`) return { status: 200, body: followUps.body };
    if (path === `/app/api/patients/${ABBU.id}/attention`) return { status: 200, body: abbuAttention.body };
    if (path.startsWith(`/app/api/patients/${ABBU.id}/thread`)) return { status: 200, body: abbusThread };
    if (path === `/app/api/patients/${ABBU.id}/follow-ups`) return { status: 200, body: abbuFollowUps.body };
    if (path === `/app/api/documents/${CBC.id}`) return { status: 200, body: CBC };
    if (path === `/app/api/documents/${potas.body.id}`) return { status: 200, body: potas.body };
    if (path.startsWith("/app/api/upload-options")) return { status: 200, body: uploadOptions.body };
    throw new Error(`unexpected read ${path}`);
  },
}));

const { default: HomePage } = await import("./page");

beforeEach(() => {
  reads = [];
  thread = THREAD;
  abbusThread = abbuThread.body as ThreadJson;
  shown = AMMA;
  banner = nowBanner.body as NowBannerJson;
  ammaOwned = true;
  ctx.preview = true;
  ctx.here = "/app/preview/home";
  vi.stubGlobal("location", { pathname: ctx.here, search: "", assign: vi.fn(), reload: vi.fn() });
  // jsdom has no scrollIntoView, which the conversation calls to open on the paper in view; a test that watches it
  // sets its own.
  const had = Element.prototype.scrollIntoView;
  Element.prototype.scrollIntoView = () => undefined;
  onTestFinished(() => {
    Element.prototype.scrollIntoView = had;
  });
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const cardsIn = (th: ThreadJson) => th.items.flatMap((i) => (i.kind === "paper" ? [i.card.id] : []));

const home = async (query: Record<string, string> = {}) =>
  render(await HomePage({ searchParams: Promise.resolve(query) })).container;

const inView = (root: ParentNode) => root.querySelector(".desktop-shell__paper [data-paper]")?.getAttribute("data-paper");
const outlined = (root: ParentNode) =>
  [...root.querySelectorAll(".desktop-shell__talk [data-talk-selected]")].map((e) => e.getAttribute("data-paper"));
const paperLink = (id: string) => href(`/app/home?patient=${AMMA.id}&paper=${id}`, true);

/** The elements whose whole text is `sentence`, innermost: a warning line, not a message that quotes it among others. */
const warningLines = (root: ParentNode, sentence: string) =>
  [...root.querySelectorAll<HTMLElement>("*")].filter(
    (e) => e.textContent?.trim() === sentence && ![...e.children].some((c) => c.textContent?.trim() === sentence),
  );

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
    expect(container.querySelector(".desktop-shell .attention-stack, .desktop-shell [data-attention]"), "no attention card on the desktop").toBeNull();
  });

  it("theScreenHasOneHeadingOneNamingThePatient", async () => {
    // desktop.html's chatView(): an h1 for the screen, out of sight (desk.talk.title over the possessive). The phone's own
    // h1 is not drawn from 900 px, so without it the desktop page has no level-one heading (axe page-has-heading-one).
    const container = await home();
    const h1s = [...container.querySelectorAll(".desktop-shell h1")].map((h) => h.textContent);
    expect(h1s).toEqual([t("bn", "desk.talk.title", AMMA.name_possessive)]);
  });

  it("aShareMemberWithNoPaperHasNoUploadButton", async () => {
    // Review Focus 2: a share member never sees the paperclip. A shared patient whose thread holds no paper puts the
    // first visit in the viewer; for a share member it says view-only (add.viewonly) and links nowhere to /app/add.
    ammaOwned = false;
    thread = { items: [], next_cursor: null };
    const container = await home();
    const viewer = container.querySelector(".desktop-shell__paper")!;
    expect(viewer.querySelector(".paper-viewer__first"), "the first visit is in the viewer").not.toBeNull();
    expect(viewer.querySelector("a.paper-viewer__pick"), "a share member is offered the upload link").toBeNull();
    expect(container.querySelector(`.desktop-shell a[href*="/app/add"]`), "a share member has a way to /app/add").toBeNull();
    expect(container.querySelector(`.desktop-shell a[href*="add=1"]`), "a share member has a way to the add dialog").toBeNull();
    expect(container.querySelector(".desk-drop"), "nor a drop frame").toBeNull();
    expect(viewer.querySelector(".paper-viewer__first p")!.textContent).toBe(t("bn", "add.viewonly"));
    cleanup();
    // The owner of the same empty thread keeps the invitation.
    ammaOwned = true;
    const own = await home();
    // The owner's pick opens the add sheet as a dialog over this screen (plan desktop-parity U1).
    expect(own.querySelector(".desktop-shell__paper a.paper-viewer__pick")!.getAttribute("href")).toBe(deskAddHref(AMMA.id, null, true));
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

  it("aNowPaperBeyondTheThreadsFirstPageStillHasItsWarningAndItsLink", async () => {
    // Abbu's NOW paper, pushed off the thread's first page by newer papers and messages, and the bar already seen
    // (`now.banner.seen`): the phone still shows Java's NOW card with its sentence and its link (the attention stack, filtered in
    // SQL so no number of newer papers can hide it). The desktop must too, in the conversation, and read no paper for it.
    shown = ABBU;
    abbusThread = { ...abbusThread, items: abbusThread.items.filter((i) => !(i.kind === "paper" && i.card.id === POTAS.id)) };
    banner = { ...banner, show: false };
    const urgent = abbuAttention.body.items.find((i) => i.kind === "urgent" && i.document_id === POTAS.id);
    expect(urgent?.level, "the attention fixture holds the NOW paper").toBe("now");
    expect(cardsIn(abbusThread), "the thread does not").not.toContain(POTAS.id);
    // jsdom has no scrollIntoView: the column's opening scroll is watched instead.
    const into = vi.fn();
    const before = Element.prototype.scrollIntoView;
    Element.prototype.scrollIntoView = into;
    onTestFinished(() => {
      Element.prototype.scrollIntoView = before;
    });

    const container = await home();
    await act(async () => undefined);
    const desk = container.querySelector<HTMLElement>(".desktop-shell");
    expect(desk, "the desktop screen").not.toBeNull();
    expect(desk!.querySelector(".desktop-shell__bar [role=alert]"), "the bar is seen: not shown").toBeNull();
    const talk = desk!.querySelector<HTMLElement>(".desktop-shell__talk")!;
    const sentence = t("bn", POTAS.banner!.line_key as CopyKey);
    expect(warningLines(talk, sentence).length, "the NOW sentence in the conversation").toBe(1);
    expect(talk.querySelector(`a[href="${href(`/app/documents/${POTAS.id}`, true)}"]`), "and the way to the paper").not.toBeNull();
    const red = [...talk.querySelectorAll(".verdict-card")].filter((c) => [...c.classList].some((k) => NOW_ALLOWED.test(`.${k}`)));
    expect(red.length, "on the red card").toBe(1);
    expect(
      [...talk.querySelectorAll('[data-attention="urgent"]')].map((e) => e.getAttribute("data-document")),
      "pinned once, for that paper",
    ).toEqual([POTAS.id]);
    // The column opens where that card is, at its top, as the phone does with a NOW card in its stack: it is not
    // scrolled to the newest paper, and Mio holds still.
    expect(into, "the column holds still at the NOW card").not.toHaveBeenCalled();
    expect(talk.querySelector(".conversation__head .mio-avatar--hop, .conversation__head .mio-avatar--wave"), "Mio still").toBeNull();
    expect(reads.some((r) => r.startsWith("/app/api/documents/")), "no paper read: no DOCUMENT_VIEW nobody asked for").toBe(false);
  });

  it("theNowBarDoesNotHoldTheDesktopConversation", async () => {
    // The bar lies across both columns, outside the conversation, and stays in sight whatever the column does, so the
    // column opens on the paper in view (desktop.html's goto: the card centred), as with no bar (PO 2026-11-07: the
    // conversation follows the papers). Mio still holds still while the bar is shown.
    expect(banner.show, "the prototype's NOW bar is shown").toBe(true);
    const into = vi.fn();
    const before = Element.prototype.scrollIntoView;
    Element.prototype.scrollIntoView = into;
    onTestFinished(() => {
      Element.prototype.scrollIntoView = before;
    });
    const container = await home({ patient: AMMA.id, paper: CBC.id });
    await act(async () => undefined);
    expect(container.querySelector(".desktop-shell__bar [role=alert]"), "the bar is there").not.toBeNull();
    expect(into.mock.instances.map((el) => (el as HTMLElement).dataset.paper), "the column opens on the paper in view").toEqual([CBC.id]);
    expect(into.mock.calls[0]![0]).toEqual({ block: "center", behavior: "instant" });
    const talk = container.querySelector(".desktop-shell__talk")!;
    expect(talk.querySelector(".conversation__head .mio-avatar--hop, .conversation__head .mio-avatar--wave"), "Mio still").toBeNull();
  });

  it("belowNineHundredHomeIsTodaysChat", async () => {
    // Live, since the flip (DR-51): the desktop screen, and below 900 px today's chat in the phone shell.
    ctx.preview = false;
    ctx.here = "/app/home";
    const live = await home();
    expect(live.querySelector(".desktop-shell"), "the desktop screen for a live request").not.toBeNull();
    const livePhone = live.querySelector(".app-shell--phone [data-page=chat]");
    expect(livePhone, "today's chat below 900 px").not.toBeNull();
    expect(live.querySelector(".desktop-shell [data-page=chat]"), "not inside the desktop screen").toBeNull();
    // React's own ids (useId) count the components rendered before; they are the only other difference.
    const ids = (html: string) => html.replace(/_r_\w+_/g, "_r_");
    const today = ids(livePhone!.outerHTML);
    cleanup();
    // Preview: the very same chat in the same phone shell. Only the preview's own links differ.
    ctx.preview = true;
    ctx.here = "/app/preview/home";
    const desk = await home();
    const phone = desk.querySelector(".app-shell--phone [data-page=chat]");
    expect(phone, "today's chat below 900 px").not.toBeNull();
    const unpreviewed = ids(phone!.outerHTML.replaceAll('href="/app/preview/', 'href="/app/'));
    expect(unpreviewed).toBe(today);
  });
});

describe("the add sheet as a dialog (plan desktop-parity U1; desktop.html uploadDlg)", () => {
  it("addOpensTheSheetOverTheScreenWithItsOwnReadAndClosesBackToThePaper", async () => {
    ctx.here = `/app/preview/home?patient=${AMMA.id}&paper=${CBC.id}&add=1`;
    const container = await home({ patient: AMMA.id, paper: CBC.id, add: "1" });
    const dialog = container.querySelector<HTMLDialogElement>(".desktop-shell dialog.desk-upload");
    expect(dialog, "the dialog over the desktop screen").not.toBeNull();
    expect(dialog!.getAttribute("aria-label")).toBe(t("bn", "add.title"));
    // Today's sheet, every rule of it: the add page's own read, for the patient in view (Java decides whom it opens on).
    expect(dialog!.querySelector("[data-upload-sheet]"), "the add sheet").not.toBeNull();
    expect(reads.filter((r) => r.startsWith("/app/api/upload-options"))).toEqual([`/app/api/upload-options?patient=${AMMA.id}`]);
    // Its close is the same screen on the same paper, without the dialog.
    expect(dialog!.querySelector(".upload-sheet__close")!.getAttribute("href")).toBe(`/app/preview/home?patient=${AMMA.id}&paper=${CBC.id}`);
    // The screen is still drawn behind it, on that paper.
    expect(inView(container)).toBe(CBC.id);
  });

  it("noAddNoDialogAndNoRead", async () => {
    const container = await home();
    expect(container.querySelector("dialog.desk-upload")).toBeNull();
    expect(reads.some((r) => r.startsWith("/app/api/upload-options")), "no options read for a dialog nobody asked for").toBe(false);
  });

  it("theOwnerCanDropAPaperAnywhereAShareMemberCannot", async () => {
    const own = await home();
    const frame = own.querySelector(".desktop-shell .desk-drop");
    expect(frame, "the drop frame for the owner").not.toBeNull();
    expect(frame!.textContent).toBe(t("bn", "desk.drop"));
    cleanup();
    ammaOwned = false;
    const member = await home();
    expect(member.querySelector(".desk-drop"), "no drop frame for a share member").toBeNull();
  });
});

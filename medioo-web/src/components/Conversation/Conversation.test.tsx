import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { act, cleanup, fireEvent, render } from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { MeJson, MePatient, PaperJson, PaperResultJson, ThreadItemJson, ThreadJson } from "@/api/types";
import { formatDayMonth, formatTime, t, type CopyKey, type Lang } from "@/copy";
import { VerdictCard } from "@/components/safety/VerdictCard/VerdictCard";
import { call999Words } from "@/components/safety/AskAnswer/call999";
import { deskAddHref, href } from "@/routing/paths";
import { NOW_ALLOWED } from "@/styles/now-usage";
import proto from "../../../fixtures/api/me/proto.json";
import { Conversation } from "./Conversation";

/*
 * The conversation column of the talk-and-papers screen (plan desktop-parity Task 7; desktop.html `chatView()`'s
 * `.convo`): the header (whose papers, not a doctor), the thread as the desktop draws it (each paper the photo the
 * family sent, then Medioo's card; a delivered message's `web_body`; an ask as today), a day line before each day, the
 * retake card's two actions, and the composer. Over the bodies Java's contract tests wrote (fixtures/api/thread/), so
 * every case is what Java actually sends. No Bangla is typed here (the bangla-literal lint scans tests too).
 */

const api = join(__dirname, "..", "..", "..", "fixtures", "api");
const LANGS: readonly Lang[] = ["bn", "en"];

function thread(name: string): ThreadItemJson[] {
  return (JSON.parse(readFileSync(join(api, "thread", `${name}.json`), "utf8")) as { body: ThreadJson }).body.items;
}
const THREADS = readdirSync(join(api, "thread"))
  .filter((f) => f.endsWith(".json"))
  .map((f) => f.replace(/\.json$/, ""))
  .sort();

const PROTO = proto.body as MeJson;
const AMMA = PROTO.patients.find((p) => p.age === 68)!;

type PaperItem = Extract<ThreadItemJson, { kind: "paper" }>;
const papersOf = (items: ThreadItemJson[]) => items.filter((i): i is PaperItem => i.kind === "paper").map((i) => i.card);
const resultOf = (items: ThreadItemJson[], test: (p: PaperResultJson) => boolean): PaperResultJson => {
  const found = papersOf(items).find((p): p is PaperResultJson => "date" in p && test(p));
  if (!found) throw new Error("fixture: no such paper");
  return found;
};

/** The patient a thread is about: the proto patient's fields, the name forms of the thread's own papers. */
function patientOf(items: ThreadItemJson[], extra: Partial<MePatient> = {}): MePatient {
  const card = papersOf(items)[0];
  if (!card) return { ...AMMA, ...extra };
  const p = card.patient;
  return { ...AMMA, id: p.id, name: p.name, name_possessive: p.name_possessive, name_objective: p.name_objective, owner: card.owner, ...extra };
}

const hrefFor = (id: string) => `/app/home?paper=${id}`;

function draw(
  items: ThreadItemJson[],
  { lang = "bn", selectedId = null, patient, preview = false, hold }: { lang?: Lang; selectedId?: string | null; patient?: MePatient; preview?: boolean; hold?: boolean } = {},
) {
  return render(
    <Conversation
      lang={lang}
      preview={preview}
      patient={patient ?? patientOf(items)}
      items={items}
      selectedId={selectedId}
      hrefFor={hrefFor}
      hold={hold}
    />,
  ).container;
}

/** The thread's own items in DOM order (day lines and the composer's exchanges are not among them). */
const talkItems = (root: ParentNode) => [...root.querySelectorAll<HTMLElement>("[data-talk-item]")];
const itemOfPaper = (root: ParentNode, id: string) => root.querySelector<HTMLElement>(`[data-talk-item="paper"][data-paper="${id}"]`)!;
const before = (a: Element, b: Element) => (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;

// jsdom's dialog has no showModal() or close(): the platform's documented effect on the element, nothing else
// (Sheet.test.tsx's stand-in). scrollIntoView is not implemented either; OpenAtNewest calls it.
const noScroll = () => undefined;
beforeAll(() => {
  Element.prototype.scrollIntoView = noScroll;
  const proto = HTMLDialogElement.prototype as HTMLDialogElement & { showModal?: () => void; close?: () => void };
  if (typeof proto.showModal !== "function") {
    proto.showModal = function (this: HTMLDialogElement) {
      this.setAttribute("open", "");
    };
  }
  if (typeof proto.close !== "function") {
    proto.close = function (this: HTMLDialogElement) {
      if (!this.hasAttribute("open")) return;
      this.removeAttribute("open");
      this.dispatchEvent(new Event("close"));
    };
  }
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("Conversation", () => {
  it("noRawLinkIsEverShown", () => {
    // The WhatsApp bodies carry the timeline link and the opt-out link (`https://…`): the desktop shows `web_body`,
    // which Java sends without them. Guard: the fixtures do carry links in `body`, so this can fail.
    const linked = THREADS.filter((name) => thread(name).some((i) => i.kind === "message" && /https?:\/\//.test(i.body)));
    expect(linked.length, "some thread fixture holds a message whose WhatsApp body has a link").toBeGreaterThan(0);
    expect(THREADS.length).toBeGreaterThanOrEqual(8);
    for (const name of THREADS) {
      for (const lang of LANGS) {
        const text = draw(thread(name), { lang }).textContent ?? "";
        const raw = /https?:\/\/\S*/.exec(text);
        expect(raw?.[0] ?? null, `${name} (${lang}): a raw link is shown in the conversation`).toBeNull();
        cleanup();
      }
    }
  });

  it("aCardsWordingLosesItsLinkLinesAndNothingElse", () => {
    // The other direction of noRawLinkIsEverShown: the released wording stays on the card, every line but the link's,
    // the NOW paper's urgency and emergency lines included.
    const amma = thread("proto-amma");
    const creat = resultOf(amma, (p) => p.banner?.level === "this_week");
    const abbu = thread("proto-abbu");
    const potas = resultOf(abbu, (p) => p.banner?.level === "now");
    for (const [items, paper] of [
      [amma, creat],
      [abbu, potas],
    ] as const) {
      expect(paper.gate).toBe("RELEASED");
      const lines = paper.wording!.split("\n");
      const kept = lines.filter((l) => !/https?:\/\//.test(l));
      const root = draw(items);
      const shown = itemOfPaper(root, paper.id).querySelector(".verdict-card__wording")!.textContent;
      expect(shown, paper.id).toBe(kept.join("\n"));
    }
    expect(creat.wording!.split("\n").some((l) => l.includes("https://")), "creat's wording held a link line").toBe(true);
    expect(potas.wording!.includes("http"), "the NOW wording holds none: shown whole").toBe(false);
  });

  it("aMessageShowsItsWebBodyAndADetailReplyIsLeftOut", () => {
    const items = thread("default");
    const messages = items.filter((i): i is Extract<ThreadItemJson, { kind: "message" }> => i.kind === "message");
    const detail = messages.filter((m) => m.message_kind === "detail");
    const shown = messages.filter((m) => m.message_kind !== "detail");
    expect(detail.length, "default holds a detail reply").toBeGreaterThan(0);
    expect(shown.length, "and another message").toBeGreaterThan(0);
    const root = draw(items);
    const bubbles = talkItems(root)
      .filter((el) => el.dataset.talkItem === "message")
      .map((el) => el.querySelector(".message-bubble--recv .message-bubble__body")!.textContent);
    // Oldest first, as the conversation reads.
    expect(bubbles).toEqual([...shown].reverse().map((m) => m.web_body));
    for (const m of detail) expect(root.textContent).not.toContain(m.web_body);
  });

  it("aPaperIsASentPhotoThenACard", () => {
    const items = thread("proto-amma");
    const cards = papersOf(items);
    expect(cards.length).toBe(5);
    const root = draw(items);
    for (const card of cards) {
      const item = itemOfPaper(root, card.id);
      expect(item, card.id).not.toBeNull();
      const photo = item.querySelector<HTMLAnchorElement>(".conversation__photo");
      expect(photo, `${card.id}: the photo the family sent`).not.toBeNull();
      expect(photo!.closest(".conversation__sent"), "on the family's side").not.toBeNull();
      expect(photo!.getAttribute("href")).toBe(hrefFor(card.id));
      const img = photo!.querySelector("img")!;
      expect(img.getAttribute("src"), "its first page").toBe(card.pages[0]!.src);
      expect(img.getAttribute("alt")).toBe(t("bn", "retake.photo", 1));
      const article = item.querySelector("article.verdict-card");
      expect(article, `${card.id}: Medioo's card`).not.toBeNull();
      expect(before(photo!, article!), "the photo, then the card").toBe(true);
    }
    // The newest paper is the last item, beside the composer.
    const order = talkItems(root)
      .filter((el) => el.dataset.talkItem === "paper")
      .map((el) => el.dataset.paper);
    expect(order).toEqual([...cards].reverse().map((c) => c.id));
  });

  it("theCardSaysTypeDateAndLabAndOffersEverything", () => {
    const items = thread("proto-amma");
    const cbc = resultOf(items, (p) => p.document_type === "lab_report" && p.date === "2026-10-20");
    for (const lang of LANGS) {
      const root = draw(items, { lang });
      const item = itemOfPaper(root, cbc.id);
      expect(item.querySelector(".verdict-card__sub")!.textContent).toBe(
        `${t(lang, "type.lab_report")} · ${formatDayMonth(lang, "2026-10-20")} · Shapla Diagnostics`,
      );
      const title = item.querySelector<HTMLAnchorElement>("a.verdict-card__open")!;
      expect(title.getAttribute("href")).toBe(hrefFor(cbc.id));
      const more = [...item.querySelectorAll<HTMLAnchorElement>("a.verdict-card__more")];
      expect(more.map((a) => [a.getAttribute("href"), a.textContent])).toEqual([[`${hrefFor(cbc.id)}&panel=1`, t(lang, "desk.panel.open")]]);
      cleanup();
    }
  });

  it("theSelectedCardIsOutlined", () => {
    const items = thread("proto-amma");
    const cbc = resultOf(items, (p) => p.document_type === "lab_report" && p.date === "2026-10-20");
    const root = draw(items, { selectedId: cbc.id });
    const outlined = [...root.querySelectorAll(".verdict-card--selected")];
    expect(outlined.length, "exactly one card").toBe(1);
    expect(outlined[0]!.closest<HTMLElement>("[data-talk-item]")!.dataset.paper).toBe(cbc.id);
    const photos = [...root.querySelectorAll<HTMLElement>(".conversation__photo[aria-current]")];
    expect(photos.map((p) => p.getAttribute("href")), "and its photo").toEqual([hrefFor(cbc.id)]);
    cleanup();
    const none = draw(items, { selectedId: null });
    expect(none.querySelectorAll(".verdict-card--selected").length, "nothing selected, nothing outlined").toBe(0);
    expect(none.querySelectorAll(".conversation__photo[aria-current]").length).toBe(0);
  });

  it("aNowCardKeepsItsWarningAndCall999Verbatim", () => {
    for (const [name, call] of [
      ["proto-abbu", true],
      ["now", true],
      ["now-abroad", false],
    ] as const) {
      const items = thread(name);
      const nowCard = resultOf(items, (p) => p.banner?.level === "now");
      expect(nowCard.now?.call_999, `${name}: Java's call_999`).toBe(call);
      for (const lang of LANGS) {
        const root = draw(items, { lang });
        const item = itemOfPaper(root, nowCard.id);
        const card = item.querySelector("article.verdict-card")!;
        expect([...card.classList].some((c) => NOW_ALLOWED.test(`.${c}`)), `${name}: the red card`).toBe(true);
        expect(card.querySelector(".verdict-card__band")!.textContent, `${name} (${lang}): Java's sentence`).toBe(t(lang, nowCard.banner!.line_key));
        // The band and the safety lines exactly as VerdictCard draws them on the phone.
        const alone = render(<VerdictCard paper={nowCard} lang={lang} link="/x" />).container;
        for (const part of [".verdict-card__band", ".verdict-card__notdoctor", ".verdict-card__call"]) {
          expect(card.querySelector(part)?.outerHTML, `${name} (${lang}) ${part}`).toBe(alone.querySelector(part)?.outerHTML);
        }
        const tel = [...root.querySelectorAll<HTMLAnchorElement>('a[href^="tel:"]')].map((a) => [a.getAttribute("href"), a.textContent]);
        expect(tel, `${name} (${lang}): 999 only when Java says so`).toEqual(call ? [["tel:999", call999Words(lang)]] : []);
        cleanup();
      }
    }
  });

  it("aRetakeCardOffersResendAndTips", () => {
    const items = thread("proto-amma");
    const blur = papersOf(items).find((p): p is PaperResultJson => p.status === "NEEDS_RETAKE")!;
    expect(blur.retake?.replaced, "proto-amma's blur is not yet replaced").toBe(false);
    for (const preview of [false, true]) {
      const root = draw(items, { preview });
      const actions = [...root.querySelectorAll<HTMLElement>(".quick-actions")];
      expect(actions.length, "one set of actions, the retake card's").toBe(1);
      expect(actions[0]!.closest<HTMLElement>("[data-talk-item]")!.dataset.paper).toBe(blur.id);
      const chips = [...actions[0]!.querySelectorAll<HTMLElement>(".quick-actions__chip")];
      expect(chips.map((c) => c.textContent)).toEqual([t("bn", "desk.quick.resend"), t("bn", "desk.quick.tips")]);
      expect(chips[0]!.getAttribute("href")).toBe(
        `${href("/app/add", preview)}?patient=${encodeURIComponent(blur.patient.id)}&replaces=${encodeURIComponent(blur.id)}`,
      );
      // The tips chip opens the add page's own tips, in a sheet over the conversation.
      expect(chips[1]!.tagName, "a button once the page runs").toBe("BUTTON");
      fireEvent.click(chips[1]!);
      const dialog = root.querySelector("dialog")!;
      expect(dialog.hasAttribute("open")).toBe(true);
      expect(dialog.querySelector(".sheet__title")!.textContent).toBe(t("bn", "add.tips"));
      expect([...dialog.querySelectorAll("li")].map((li) => li.textContent)).toEqual(
        (["add.tip.flat", "add.tip.light", "add.tip.frame"] as CopyKey[]).map((k) => t("bn", k)),
      );
      cleanup();
    }
    // Sent again already (Java's `replaced`): nothing more to send for it; the tips stay.
    const replaced = items.map((i) => (i.kind === "paper" && i.card.id === blur.id ? { ...i, card: { ...blur, retake: { ...blur.retake!, replaced: true } } } : i));
    const again = draw(replaced);
    expect([...again.querySelectorAll(".quick-actions__chip")].map((c) => c.textContent)).toEqual([t("bn", "desk.quick.tips")]);
    cleanup();
    // A share member sends nothing (Java refuses theirs): no actions at all.
    const member = items.map((i) => (i.kind === "paper" ? { ...i, card: { ...i.card, owner: false } as PaperJson } : i));
    expect(draw(member, { patient: { ...patientOf(items), owner: false } }).querySelectorAll(".quick-actions").length).toBe(0);
  });

  it("onlyTheNewestPaperOffersQuickActions", () => {
    // desktop.html's `.suggest` comes once, after the newest paper, and only when that paper is a retake: a retake card
    // further up the thread (sent again since, or followed by other papers) offers nothing in the conversation.
    const items = thread("proto-amma");
    const blur = papersOf(items).find((p): p is PaperResultJson => p.status === "NEEDS_RETAKE")!;
    const cbc = resultOf(items, (p) => p.status === "DONE");
    expect(papersOf(items)[0]!.id, "the blur is proto-amma's newest paper").toBe(blur.id);
    const newer = (card: PaperJson): ThreadItemJson => ({ kind: "paper", at: "2026-10-21T04:00:00Z", card });

    // A routine paper sent after the retake: no actions anywhere.
    const afterRoutine = draw([newer({ ...cbc, id: "newer-cbc" }), ...items]);
    expect(afterRoutine.querySelectorAll(".quick-actions").length, "an older retake card offers nothing").toBe(0);
    cleanup();

    // A second retake after the first: the actions under the newest only.
    const afterRetake = draw([newer({ ...blur, id: "newer-blur" }), ...items]);
    const actions = [...afterRetake.querySelectorAll<HTMLElement>(".quick-actions")];
    expect(actions.map((a) => a.closest<HTMLElement>("[data-talk-item]")!.dataset.paper), "one set, under the newest retake").toEqual(["newer-blur"]);
    expect(actions[0]!.querySelector(".quick-actions__chip")!.getAttribute("href")).toContain(`replaces=${encodeURIComponent("newer-blur")}`);
  });

  it("aRoutineCardOffersNoQuickReplies", () => {
    for (const name of ["proto-abbu", "default", "share-member", "now", "with-asks"]) {
      const items = thread(name);
      expect(papersOf(items).some((p) => p.status === "NEEDS_RETAKE"), `${name} holds no retake`).toBe(false);
      const root = draw(items);
      expect(root.querySelectorAll(".quick-actions").length, name).toBe(0);
      expect(root.textContent, name).not.toContain(t("bn", "desk.quick.tips"));
      cleanup();
    }
  });

  it("headerSaysWhoseAndNotADoctor", () => {
    for (const lang of LANGS) {
      const root = draw(thread("proto-amma"), { lang });
      const head = root.querySelector(".conversation__head")!;
      expect(head.querySelector(".conversation__sub")!.textContent).toBe(t(lang, "desk.talk.sub", AMMA.name_possessive));
      // The footer's disclaimer is gone on desktop (X1) and so is the composer's line under the field.
      expect(root.querySelector(".composer__standing")).toBeNull();
      cleanup();
    }
  });

  it("aShareMemberHasNoPaperclip", () => {
    const items = thread("share-member");
    const owner = draw(items, { patient: patientOf(items, { owner: true }) });
    const clips = [...owner.querySelectorAll<HTMLAnchorElement>(".composer__attach")];
    // The paperclip opens the add sheet as a dialog over this screen (plan desktop-parity U1), for this patient.
    expect(clips.map((a) => [a.getAttribute("href"), a.getAttribute("aria-label")])).toEqual([
      [deskAddHref(patientOf(items).id, null, false), t("bn", "desk.attach")],
    ]);
    cleanup();
    const member = draw(items, { patient: patientOf(items, { owner: false }) });
    expect(member.querySelectorAll(".composer__attach").length, "a share member: no paperclip").toBe(0);
    expect(member.querySelectorAll(".composer__camera").length).toBe(0);
    // They still see the paper and can still ask.
    expect(member.querySelectorAll("article.verdict-card").length).toBe(1);
    expect(member.querySelector(".composer__field")).not.toBeNull();
  });

  it("anAskIsDrawnAsToday", () => {
    const items = thread("with-asks");
    const asks = items.filter((i): i is Extract<ThreadItemJson, { kind: "ask" }> => i.kind === "ask");
    expect(asks.length).toBeGreaterThanOrEqual(2);
    const root = draw(items);
    const drawn = talkItems(root).filter((el) => el.dataset.talkItem === "ask");
    expect(
      drawn.map((el) => [...el.querySelectorAll(".message-bubble")].map((b) => b.querySelector(".message-bubble__body")!.textContent)),
    ).toEqual([...asks].reverse().map((a) => [a.question, a.answer]));
  });

  it("daySeparatorsAppearOncePerDay", () => {
    // proto-amma: 20 Oct (the cbc, the blurry photo and its retake message), 15, 14 and 13 Oct, on Dhaka's calendar.
    const items = thread("proto-amma");
    for (const lang of LANGS) {
      const root = draw(items, { lang });
      const days = [...root.querySelectorAll<HTMLElement>(".day-separator")];
      expect(days.map((d) => d.dataset.day)).toEqual(["2026-10-13", "2026-10-14", "2026-10-15", "2026-10-20"]);
      // Each comes straight before the first item of its day: weekday, day and month, then that item's time.
      const oldestFirst = [...items].reverse();
      const firstOfDay = ["2026-10-13T05:02:00Z", "2026-10-14T03:12:00Z", "2026-10-15T11:40:00Z", "2026-10-20T02:30:00Z"];
      const weekdays: CopyKey[] = ["calendar.weekday.tue", "calendar.weekday.wed", "calendar.weekday.thu", "calendar.weekday.tue"];
      days.forEach((d, k) => {
        const at = firstOfDay[k]!;
        expect(oldestFirst.some((i) => i.at === at), at).toBe(true);
        expect(d.querySelector("b")!.textContent, `${lang} ${at}`).toBe(`${t(lang, weekdays[k]!)}, ${formatDayMonth(lang, d.dataset.day!)}`);
        expect(d.textContent).toBe(`${t(lang, weekdays[k]!)}, ${formatDayMonth(lang, d.dataset.day!)} ${formatTime(lang, at)}`);
        expect((d.nextElementSibling as HTMLElement).dataset.at, "the line is just before its day's first item").toBe(at);
      });
      cleanup();
    }
    // Dhaka's day, not UTC's: 19 Oct 17:00 UTC is 19 Oct 23:00 in Dhaka, but 19 Oct 19:00 UTC is 20 Oct 01:00, the
    // same Dhaka day as 20 Oct 04:00 UTC. So the 20 Oct line comes before the 19:00 UTC item, not the 04:00 one.
    const [cbc] = papersOf(items).filter((p) => "date" in p && p.date === "2026-10-20" && p.status === "DONE");
    const at = (id: string, when: string) => ({ kind: "paper", at: when, card: { ...cbc!, id } as PaperJson }) as ThreadItemJson;
    const root = draw([at("later", "2026-10-20T04:00:00Z"), at("late", "2026-10-19T19:00:00Z"), at("early", "2026-10-19T17:00:00Z")]);
    expect(
      [...root.querySelectorAll<HTMLElement>(".day-separator")].map((d) => [d.dataset.day, (d.nextElementSibling as HTMLElement).dataset.paper]),
      "each Dhaka day's line before that day's first item",
    ).toEqual([
      ["2026-10-19", "early"],
      ["2026-10-20", "late"],
    ]);
  });

  it("aListOutOfOrderStillDrawsWithUniqueKeys", () => {
    // Conversation trusts its caller's order (mergeAsks sorts by `at`); a list that is not in order shows a day's line
    // again where the day comes back, and React must still be able to tell those lines apart.
    const items = thread("proto-amma");
    const cbc = resultOf(items, (p) => p.status === "DONE");
    const at = (id: string, when: string) => ({ kind: "paper", at: when, card: { ...cbc, id } as PaperJson }) as ThreadItemJson;
    const errors = vi.spyOn(console, "error").mockImplementation(() => undefined);
    try {
      const root = draw([at("c", "2026-10-20T05:00:00Z"), at("b", "2026-10-19T17:00:00Z"), at("a", "2026-10-20T04:00:00Z")]);
      expect([...root.querySelectorAll<HTMLElement>(".day-separator")].map((d) => d.dataset.day)).toEqual(["2026-10-20", "2026-10-19", "2026-10-20"]);
      const keyWarnings = errors.mock.calls.filter((c) => c.some((a) => String(a).includes("same key")));
      expect(keyWarnings, "two children with the same key").toEqual([]);
    } finally {
      errors.mockRestore();
    }
  });

  it("theConversationOpensOnTheSelectedCardElseTheNewest", async () => {
    const into = vi.fn();
    Element.prototype.scrollIntoView = into;
    const items = thread("proto-amma");
    const cbc = resultOf(items, (p) => p.document_type === "lab_report" && p.date === "2026-10-20");
    const scroll = (root: HTMLElement) => root.querySelector<HTMLElement>(".conversation")!;

    let root = draw(items, { selectedId: cbc.id });
    await act(async () => undefined);
    expect(into.mock.instances.map((el) => (el as HTMLElement).dataset.paper), "the selected card").toEqual([cbc.id]);
    expect(into.mock.calls[0]![0]).toEqual({ block: "center", behavior: "instant" });
    cleanup();

    into.mockClear();
    const height = vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockReturnValue(4321);
    root = draw(items, { selectedId: null });
    await act(async () => undefined);
    expect(into, "nothing selected: no card").not.toHaveBeenCalled();
    expect(scroll(root).scrollTop, "the newest, at the column's end").toBe(4321);
    cleanup();

    // A NOW card pinned at the head of the column (PinnedUrgent): the column holds still there, whatever is selected.
    root = draw(items, { selectedId: cbc.id, hold: true });
    await act(async () => undefined);
    expect(into).not.toHaveBeenCalled();
    expect(scroll(root).scrollTop).toBe(0);
    height.mockRestore();
    Element.prototype.scrollIntoView = noScroll;
  });

  it("theConversationFollowsThePaperTheViewerShows", async () => {
    // desktop.html's select(): moving to another paper (the strip, the arrows, the keys) brings its card into view,
    // the shortest way (`nearest`), smoothly unless the family asked for less motion, and pulses it once. The family
    // chose that paper, so it follows even where the column held still on opening (`hold`).
    const into = vi.fn();
    Element.prototype.scrollIntoView = into;
    const items = thread("proto-amma");
    const papers = papersOf(items);
    const [a, b] = [papers[0]!, papers[2]!];
    const view = (selectedId: string, hold = false) => (
      <Conversation lang="bn" preview={false} patient={patientOf(items)} items={items} selectedId={selectedId} hrefFor={hrefFor} hold={hold} />
    );
    const { rerender, container } = render(view(a.id, true));
    await act(async () => undefined);
    expect(into, "held on opening").not.toHaveBeenCalled();

    rerender(view(b.id, true));
    await act(async () => undefined);
    expect(into.mock.instances.map((el) => (el as HTMLElement).dataset.paper), "the chosen paper's item").toEqual([b.id]);
    expect(into.mock.calls[0]![0]).toEqual({ block: "nearest", behavior: "smooth" });
    expect(itemOfPaper(container, b.id).classList.contains("conversation__item--pulse"), "its card pulses").toBe(true);

    // The same paper again (a refresh of the screen): nothing moves.
    into.mockClear();
    rerender(view(b.id, true));
    await act(async () => undefined);
    expect(into).not.toHaveBeenCalled();

    // Less motion asked for: the jump, not the glide.
    vi.stubGlobal("matchMedia", (q: string) => ({ matches: q.includes("reduce"), media: q, addEventListener() {}, removeEventListener() {} }));
    rerender(view(a.id, true));
    await act(async () => undefined);
    expect(into.mock.calls[0]![0]).toEqual({ block: "nearest", behavior: "auto" });
    vi.unstubAllGlobals();
    Element.prototype.scrollIntoView = noScroll;
  });

  it("aCardTallerThanTheColumnIsBroughtInAtItsTopWhereItsWarningIs", async () => {
    // A NOW card with its crops is taller than the column: centred, its band and Java's sentence sat above the fold
    // (seen on the board at 1440 px). Taller than the column, the card itself is brought in at its top, opening and
    // following alike; one that fits is brought in whole, as before.
    const into = vi.fn();
    Element.prototype.scrollIntoView = into;
    const items = thread("proto-amma");
    const [a, b] = [papersOf(items)[0]!, papersOf(items)[2]!];
    const column = vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockImplementation(function (this: HTMLElement) {
      return this.classList.contains("conversation") ? 700 : 0;
    });
    const rect = vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
      const h = this.classList.contains("verdict-card") ? 1500 : this.dataset.talkItem === "paper" ? 1700 : 0;
      return { height: h, width: 300, top: 0, left: 0, bottom: h, right: 300, x: 0, y: 0, toJSON: () => ({}) } as DOMRect;
    });
    const view = (selectedId: string) => (
      <Conversation lang="bn" preview={false} patient={patientOf(items)} items={items} selectedId={selectedId} hrefFor={hrefFor} />
    );
    const { rerender } = render(view(a.id));
    await act(async () => undefined);
    const opened = into.mock.instances[0] as HTMLElement;
    expect(opened.classList.contains("verdict-card"), "the card, not the photo above it").toBe(true);
    expect(opened.closest("[data-paper]")!.getAttribute("data-paper")).toBe(a.id);
    expect(into.mock.calls[0]![0]).toEqual({ block: "start", behavior: "instant" });
    into.mockClear();
    rerender(view(b.id));
    await act(async () => undefined);
    expect((into.mock.instances[0] as HTMLElement).closest("[data-paper]")!.getAttribute("data-paper")).toBe(b.id);
    expect(into.mock.calls[0]![0]).toEqual({ block: "start", behavior: "smooth" });
    column.mockRestore();
    rect.mockRestore();
    Element.prototype.scrollIntoView = noScroll;
  });

  it("mioHoldsStillWhileANowResultIsShownEvenWhenTheColumnScrolls", () => {
    const items = thread("proto-amma");
    const root = render(
      <Conversation lang="bn" preview={false} patient={patientOf(items)} items={items} selectedId={null} hrefFor={hrefFor} still />,
    ).container;
    expect(root.querySelector(".conversation__head .mio-avatar--hop, .conversation__head .mio-avatar--wave"), "Mio still").toBeNull();
    cleanup();
    // The other direction: with no NOW result, Mio moves, so the check above can fail.
    const calm = render(<Conversation lang="bn" preview={false} patient={patientOf(items)} items={items} selectedId={null} hrefFor={hrefFor} />).container;
    expect(calm.querySelector(".conversation__head .mio-avatar--hop, .conversation__head .mio-avatar--wave"), "Mio moves").not.toBeNull();
  });
});

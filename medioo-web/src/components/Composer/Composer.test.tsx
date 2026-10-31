import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { act, cleanup, fireEvent, render } from "@testing-library/react";
import { renderToString } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from "vitest";
import type { AskJson } from "@/api/types";
import { composerWords } from "@/app/app/(signed-in)/home/chat-words";
import { banglaDigits, t, type Lang } from "@/copy";
import { signInPath } from "@/routing/paths";
import { call999Words } from "@/components/safety/AskAnswer/call999";
import { ASK_TIMEOUT_MS } from "./ask-limits";
import { Composer, type ComposerWords } from "./Composer";

/*
 * The composer asks (plan CHAT-web, PO ruling P7, DR-50): a typed question is posted to Java once, a pending bubble
 * stands while Java answers, Java's `answer` is shown exactly as sent (it already ends with the disclaimer and the
 * red-flag line, appended by Java's code), and every failure (a refusal, a dead network, the timeout) shows the reply
 * WhatsApp gives, never an empty bubble. The answers are the bodies Java's AskApiContractTest wrote (fixtures/api/ask/).
 */

const api = join(__dirname, "..", "..", "..", "fixtures", "api");
const LANGS: readonly Lang[] = ["bn", "en"];
const PATIENT = "5e9edf6d-0a2a-4913-8675-9d07a4100002";
/** The possessive the chat page passes (MePatient.name_possessive), as Java wrote it for me/one-patient.json. */
const POSSESSIVE = (
  JSON.parse(readFileSync(join(api, "me", "one-patient.json"), "utf8")) as { body: { patients: { name_possessive: string }[] } }
).body.patients[0]!.name_possessive;

/** Words that name their own role, so a test sees which one landed where (the real ones are checked below). */
const WORDS: ComposerWords = {
  field: "field-label",
  send: "send-label",
  hint: "hint-line",
  reply: "reply-line\nsecond-line",
  tooLong: "too-long-line\nsecond-line",
  signIn: "sign-in-label",
  pending: "pending-line",
  call999: "call-999-label",
  crop: { retry: "crop-retry", alt: "crop-alt" },
  camera: "camera-label",
};

const QUESTION = "Is this value all right?";

/** One committed ask fixture: what Java answered, status and body. */
function fixture(name: string): { status: number; body: AskJson } {
  return JSON.parse(readFileSync(join(api, "ask", `${name}.json`), "utf8")) as { status: number; body: AskJson };
}
const ASK_FIXTURES = readdirSync(join(api, "ask"))
  .filter((f) => f.endsWith(".json"))
  .map((f) => f.replace(/\.json$/, ""))
  .sort();

type Call = { url: string; init: RequestInit };
let calls: Call[] = [];
const assign = vi.fn();

/** fetch answering every call with `status` and `body` (a rejection for "offline", never for "hold"). */
function answering(status: number | "offline" | "hold", body?: unknown) {
  vi.stubGlobal(
    "fetch",
    vi.fn((url: string, init: RequestInit) => {
      calls.push({ url, init });
      if (status === "offline") return Promise.reject(new TypeError("Failed to fetch"));
      if (status === "hold") return new Promise<Response>(() => undefined);
      return Promise.resolve(new Response(body === undefined ? null : JSON.stringify(body), { status }));
    }),
  );
}

beforeEach(() => {
  calls = [];
  assign.mockClear();
  vi.stubGlobal("location", { pathname: "/app/home", search: "", assign });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const draw = (words: ComposerWords = WORDS, owner = true) =>
  render(<Composer words={words} owner={owner} patientId={PATIENT} preview={false} />).container;

/** Whether send may be pressed, as the button says it: aria-disabled, never the native attribute (WCAG 2.4.3). */
const sendState = (container: HTMLElement) => {
  const send = container.querySelector<HTMLButtonElement>(".composer__send")!;
  return { native: send.disabled, aria: send.getAttribute("aria-disabled") };
};

function type(container: HTMLElement, text: string) {
  const field = container.querySelector<HTMLInputElement>(".composer__field");
  expect(field, "the composer's field").not.toBeNull();
  fireEvent.change(field!, { target: { value: text } });
  return field!;
}

/** Types `text` and presses send (a click on the button, as a finger does). */
async function ask(container: HTMLElement, text: string) {
  type(container, text);
  await act(async () => {
    fireEvent.click(container.querySelector<HTMLButtonElement>(".composer__send")!);
  });
}

/** Lets every settled promise run (the fetch's answer, then the state it sets). */
const settle = () => act(async () => {});

const bubbles = (container: HTMLElement, surface: "sent" | "recv") =>
  [...container.querySelectorAll(`.composer__lines .message-bubble--${surface} .message-bubble__body`)].map((b) => b.textContent);

describe("Composer", () => {
  it("askingPostsTheQuestionOnceAndShowsThePendingBubble", async () => {
    // Review Focus 3: one POST per press, however often the family presses while Java is answering.
    answering("hold");
    const container = draw();
    await ask(container, `  ${QUESTION}  `);
    expect(calls.length, "calls after one press").toBe(1);
    const [{ url, init }] = calls as [Call];
    expect(url).toBe(`/app/api/patients/${PATIENT}/ask`);
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string), "the parsed body").toEqual({ question: QUESTION });
    expect(new Headers(init.headers).get("x-medioo")).toBe("1");
    expect(bubbles(container, "sent")).toEqual([QUESTION]);
    expect(bubbles(container, "recv"), "the pending bubble").toEqual([WORDS.pending]);
    expect(sendState(container), "send is marked disabled while Java answers, never natively").toEqual({ native: false, aria: "true" });
    // A second press, and an Enter (the form's implicit submission), while pending.
    await ask(container, "and another");
    await act(async () => {
      fireEvent.submit(container.querySelector("form")!);
    });
    expect(calls.length, "calls after a second press and an Enter").toBe(1);
  });

  it("theAnswerIsShownAsJavaSentIt", async () => {
    expect(ASK_FIXTURES.length, "the ask fixtures Java wrote").toBeGreaterThanOrEqual(7);
    for (const name of ASK_FIXTURES) {
      const { status, body } = fixture(name);
      expect(status, name).toBe(200);
      answering(status, body);
      const container = draw();
      await ask(container, body.question);
      await settle();
      const [shown] = bubbles(container, "recv");
      expect(shown, `${name}: Medioo's bubble`).toBe(body.answer);
      // Java appended them by code: the disclaimer, then the red-flag line, last (the fixtures are bn).
      expect(shown!.endsWith(`${t("bn", "disclaimer")}\n${t("bn", "safety.red_flag_line")}`), `${name} ends with both lines`).toBe(true);
      // Send is back for the next question.
      expect(sendState(container), name).toEqual({ native: false, aria: null });
      cleanup();
    }
  });

  it("aRefusalOrOfflineFallsBackToTheReply", async () => {
    const cases: Array<[string, number | "offline", unknown]> = [
      ["500", 500, undefined],
      ["404 NOT_FOUND", 404, { reason: "NOT_FOUND" }],
      ["a rejected fetch", "offline", undefined],
      // Never an empty bubble: a 200 that carries no answer is a failure too.
      ["200 with no body", 200, undefined],
      ["200 with an empty answer", 200, { ...fixture("answer").body, answer: "" }],
      ["200 with no answer field", 200, { kind: "answer" }],
    ];
    for (const [name, status, body] of cases) {
      answering(status, body);
      const container = draw();
      await ask(container, QUESTION);
      await settle();
      expect(bubbles(container, "recv"), name).toEqual([WORDS.reply]);
      expect(sendState(container), `${name}: send is back`).toEqual({ native: false, aria: null });
      cleanup();
    }
  });

  it("aSlowAnswerFallsBackAfterTheTimeout", async () => {
    vi.useFakeTimers();
    // A fetch that ignores its abort and answers long after the timeout: the reply stands, the late answer is dropped.
    const late = fixture("answer");
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string, init: RequestInit) => {
        calls.push({ url, init });
        return new Promise<Response>((resolve) =>
          setTimeout(() => resolve(new Response(JSON.stringify(late.body), { status: 200 })), ASK_TIMEOUT_MS + 5_000),
        );
      }),
    );
    const container = draw();
    await ask(container, QUESTION);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ASK_TIMEOUT_MS - 1);
    });
    expect(bubbles(container, "recv"), "just before the timeout").toEqual([WORDS.pending]);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1);
    });
    expect(bubbles(container, "recv"), "at the timeout").toEqual([WORDS.reply]);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    expect(bubbles(container, "recv"), "after the late answer").toEqual([WORDS.reply]);
    expect(container.textContent).not.toContain(late.body.answer);
  });

  it("anEmergencyAnswerOffers999OnlyWhenJavaSays", async () => {
    const home = fixture("emergency").body;
    expect(home.call_999, "emergency: a viewer in Bangladesh").toBe(true);
    answering(200, home);
    let container = draw();
    await ask(container, home.question);
    await settle();
    const links = [...container.querySelectorAll<HTMLAnchorElement>('a[href^="tel:"]')];
    expect(links.map((a) => [a.getAttribute("href"), a.textContent])).toEqual([["tel:999", WORDS.call999]]);
    // The link is beside the bubble, not inside it: the bubble holds Java's text and nothing else.
    expect(container.querySelector(".composer__lines .message-bubble--recv .message-bubble__body")!.children.length).toBe(0);
    cleanup();

    const abroad = fixture("emergency-abroad").body;
    expect(abroad.kind).toBe("emergency");
    expect(abroad.call_999, "emergency-abroad: a viewer outside Bangladesh").toBe(false);
    answering(200, abroad);
    container = draw();
    await ask(container, abroad.question);
    await settle();
    expect(bubbles(container, "recv"), "the emergency reply was shown").toEqual([abroad.answer]);
    expect(container.querySelectorAll('a[href^="tel:"]').length).toBe(0);
  });

  it("theNewestExchangeIsScrolledIntoView", async () => {
    // The composer keeps its place after the newest exchange (home.css): the window goes to the page's end when the
    // pending bubble is drawn and again when the answer replaces it, so neither is left under the docked bar. jsdom lays
    // nothing out (scrollHeight is 0, so `top: 0`, the page's top, would pass): the page is given a height, and the
    // target must be exactly its end (review of PR 166, finding 2).
    const scrollTo = vi.fn();
    vi.stubGlobal("scrollTo", scrollTo);
    const height = Object.getOwnPropertyDescriptor(document.documentElement, "scrollHeight");
    Object.defineProperty(document.documentElement, "scrollHeight", { configurable: true, value: 4321 });
    onTestFinished(() => {
      if (height) Object.defineProperty(document.documentElement, "scrollHeight", height);
      else delete (document.documentElement as { scrollHeight?: number }).scrollHeight;
    });
    let resolve: (r: Response) => void = () => undefined;
    vi.stubGlobal(
      "fetch",
      vi.fn(() => new Promise<Response>((r) => (resolve = r))),
    );
    const container = draw();
    scrollTo.mockClear();
    await ask(container, QUESTION);
    expect(scrollTo, "after the pending bubble").toHaveBeenCalledTimes(1);
    expect(scrollTo.mock.calls[0]![0], "to the page's end").toEqual({ top: 4321, behavior: "instant" });
    await act(async () => resolve(new Response(JSON.stringify(fixture("emergency").body), { status: 200 })));
    await settle();
    expect(bubbles(container, "recv")).toEqual([fixture("emergency").body.answer]);
    expect(scrollTo, "and again after the answer").toHaveBeenCalledTimes(2);
    expect(scrollTo.mock.calls[1]![0], "an answer that fits: the page's end again").toEqual({ top: 4321, behavior: "instant" });
  });

  it("anAnsweredTurnTheThreadDrawsIsNotDrawnTwice", async () => {
    // Review of PR 166, finding 8: the page's server components re-render without a remount (router.refresh after a stale
    // seen tap, the chat tab pressed on this page), and the thread, read with asks=1, now holds the stored turn. The
    // composer leaves its own copy of that exchange out; the others stay.
    const body = fixture("answer").body;
    answering(200, body);
    const view = render(<Composer words={WORDS} owner patientId={PATIENT} preview={false} answered={[]} />);
    await ask(view.container, body.question);
    await settle();
    expect(bubbles(view.container, "recv"), "drawn by the composer").toEqual([body.answer]);
    view.rerender(<Composer words={WORDS} owner patientId={PATIENT} preview={false} answered={["another-turn"]} />);
    expect(bubbles(view.container, "recv"), "another turn in the thread: still drawn").toEqual([body.answer]);
    view.rerender(<Composer words={WORDS} owner patientId={PATIENT} preview={false} answered={["another-turn", body.turn_id]} />);
    expect(bubbles(view.container, "sent"), "the thread draws it now").toEqual([]);
    expect(view.container.querySelectorAll(".composer__lines > li").length).toBe(0);
  });

  it("aFailedExchangeIsNeverHiddenByTheThread", async () => {
    // The other direction: a reply has no turn the thread could hold, so no turn id hides it.
    answering("offline");
    const view = render(<Composer words={WORDS} owner patientId={PATIENT} preview={false} answered={[]} />);
    await ask(view.container, QUESTION);
    await settle();
    view.rerender(<Composer words={WORDS} owner patientId={PATIENT} preview={false} answered={[fixture("answer").body.turn_id]} />);
    expect(bubbles(view.container, "recv")).toEqual([WORDS.reply]);
  });

  it("aQuestionJavaRefusesAsTooLongSaysSo", async () => {
    // Review of PR 166, finding 11: Java's 400 for a question over its length is named as such, with the red-flag line; every
    // other refusal is still the reply.
    answering(400, { reason: "INVALID" });
    const container = draw();
    await ask(container, "x".repeat(1001));
    await settle();
    expect(bubbles(container, "recv")).toEqual([WORDS.tooLong]);
    expect(sendState(container)).toEqual({ native: false, aria: null });
    for (const lang of LANGS) {
      const words = composerWords(lang, POSSESSIVE);
      expect(words.tooLong.split("\n"), lang).toEqual([
        t(lang, "chat.composer.too_long", lang === "bn" ? banglaDigits("1000") : "1000"),
        t(lang, "safety.red_flag_line"),
      ]);
    }
  });

  it("aQuotedLineIsDrawnWithItsCropBeforeTheAnswer", async () => {
    // Review of PR 166, finding 5: the dose answer's quoted line arrives with its crop, drawn before Medioo's bubble.
    const dose = fixture("answer-dose").body;
    expect(dose.medicines.length, "answer-dose quotes a line").toBe(1);
    answering(200, dose);
    const container = draw();
    await ask(container, dose.question);
    await settle();
    const img = container.querySelector<HTMLImageElement>(".composer__lines .ask-answer img");
    expect(img?.getAttribute("src")).toBe(dose.medicines[0]!.crop.src);
    expect(img?.getAttribute("alt")).toBe(WORDS.crop.alt);
    const bubble = container.querySelector(".composer__lines .message-bubble--recv")!;
    expect(img!.compareDocumentPosition(bubble) & Node.DOCUMENT_POSITION_FOLLOWING, "the crop before the bubble").toBeTruthy();
    expect(bubbles(container, "recv")).toEqual([dose.answer]);
  });

  it("theAnswerIsTextNotMarkup", async () => {
    answering(200, { ...fixture("answer").body, answer: "<b>x</b>" });
    const container = draw();
    await ask(container, QUESTION);
    await settle();
    expect(bubbles(container, "recv")).toEqual(["<b>x</b>"]);
    expect(container.querySelectorAll("b").length, "a b element").toBe(0);
  });

  it("aSignedOutAskKeepsTheRedFlagLineReadableAndOffersSignIn", async () => {
    // Review Focus 5, amended by review of PR 166 finding 14: the session ended between typing and sending. Java's guard
    // answers 401 before its emergency pre-check, so an emergency typed now never reaches the emergency line:
    // the reply, which ends with the red-flag line, stays on screen, and the sign-in link is beside it. The page does
    // not leave by itself, and the typed text is kept nowhere.
    document.cookie = "e2e_probe=1";
    localStorage.setItem("kept", "1");
    const before = { local: { ...localStorage }, session: { ...sessionStorage }, cookie: document.cookie };
    answering(401, { reason: "UNAUTHENTICATED" });
    const words = composerWords("bn", POSSESSIVE);
    const container = draw(words);
    const emergency = fixture("emergency").body.question;
    await ask(container, emergency);
    await settle();
    expect(assign, "no navigation by itself").not.toHaveBeenCalled();
    const [reply] = bubbles(container, "recv");
    expect(reply!.split("\n"), "the reply, the red-flag line last").toEqual([t("bn", "chat.ask.reply"), t("bn", "safety.red_flag_line")]);
    const links = [...container.querySelectorAll<HTMLAnchorElement>(".composer__lines a")];
    expect(links.map((a) => [a.getAttribute("href"), a.textContent])).toEqual([[signInPath("/app/home"), t("bn", "login.enter")]]);
    expect(container.querySelector(".message-bubble a"), "the link is beside the bubble").toBeNull();
    expect({ local: { ...localStorage }, session: { ...sessionStorage }, cookie: document.cookie }).toEqual(before);
    expect(JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }) + document.cookie).not.toContain(emergency);
  });

  it("theStandingLineIsTheHintAndTheNoJsLineIsTheParents", () => {
    // Review of PR 166, finding 10: the server render never tells a family with JavaScript on to turn it on. The composer
    // renders the hint from the first byte; the no-JavaScript line is the parent's, inside a <noscript>.
    const nojs = (
      <noscript>
        <p className="composer__nojs">nojs-line</p>
      </noscript>
    );
    const html = renderToString(<Composer words={WORDS} owner patientId={PATIENT} preview={false} nojs={nojs} />);
    expect(html).toContain(`<p class="composer__standing">${WORDS.hint}</p>`);
    expect(html.replace(/<noscript>[\s\S]*?<\/noscript>/g, ""), "nojs outside a noscript").not.toContain("nojs-line");
    expect(html).toContain('<noscript><p class="composer__nojs">nojs-line</p></noscript>');
    expect(html).not.toContain("<form");
    expect(html).not.toContain("<input");
    // Mounted: the field, and the hint under it.
    const container = draw();
    expect(container.querySelector(".composer__field")).not.toBeNull();
    expect(container.querySelector(".composer__standing")?.textContent).toBe(WORDS.hint);
  });

  it("theReplyIsWrittenIntoALiveRegionThatWasThereFirst", async () => {
    // A live region that enters the DOM already holding text is not reliably announced (VoiceOver, NVDA): the region is
    // on the page, empty, before the first send, and the pending bubble and then the answer are written into it.
    answering(200, fixture("answer").body);
    const container = draw();
    const before = container.querySelectorAll("[aria-live]");
    expect(before.length, "one live region before the first send").toBe(1);
    const region = before[0]!;
    expect(region.getAttribute("aria-live")).toBe("polite");
    expect(region.textContent, "and it is empty").toBe("");
    expect(region.children.length).toBe(0);
    await ask(container, QUESTION);
    await settle();
    const after = container.querySelectorAll("[aria-live]");
    expect(after.length).toBe(1);
    expect(after[0], "the same node, not a new one").toBe(region);
    expect(region.textContent).toContain(fixture("answer").body.answer);
  });

  it("anEmptyLineSendsNothingAtAll", async () => {
    answering("hold");
    const container = draw();
    await ask(container, "   ");
    expect(container.querySelectorAll(".message-bubble").length).toBe(0);
    expect(calls.length).toBe(0);
  });

  it("theFallbackCarriesTheRedFlagLine", async () => {
    for (const lang of LANGS) {
      const words = composerWords(lang, POSSESSIVE);
      expect(words.pending, lang).toBe(t(lang, "chat.composer.pending", POSSESSIVE));
      expect(words.call999, lang).toBe(call999Words(lang));
      expect(words.hint, lang).toBe(t(lang, "chat.composer.hint"));
      expect(words.signIn, lang).toBe(t(lang, "login.enter"));
      expect(words.crop, lang).toEqual({ retry: t(lang, "crop.retry"), alt: t(lang, "health.crop.alt") });
      answering("offline");
      const container = draw(words);
      await ask(container, QUESTION);
      await settle();
      const [reply] = bubbles(container, "recv");
      expect(reply, `the reply (${lang})`).toBe(t(lang, "chat.ask.reply") + "\n" + t(lang, "safety.red_flag_line"));
      expect(container.querySelector(".composer__standing")?.textContent).toBe(t(lang, "chat.composer.hint"));
      cleanup();
    }
  });

  it("theTypedTextIsNotStored", async () => {
    answering(200, fixture("answer").body);
    document.cookie = "e2e_probe=1";
    localStorage.setItem("kept", "1");
    const before = { local: { ...localStorage }, session: { ...sessionStorage }, cookie: document.cookie };
    const container = draw();
    await ask(container, QUESTION);
    await settle();
    expect({ local: { ...localStorage }, session: { ...sessionStorage }, cookie: document.cookie }).toEqual(before);
    expect(JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }) + document.cookie).not.toContain(QUESTION);
    // The field is cleared for the next line.
    expect(container.querySelector<HTMLInputElement>(".composer__field")!.value).toBe("");
  });

  it("onlyTheOwnerHasTheCamera", () => {
    const owner = draw();
    const cameras = [...owner.querySelectorAll<HTMLAnchorElement>("a")];
    expect(cameras.map((a) => [a.getAttribute("href"), a.getAttribute("aria-label")])).toEqual([
      [`/app/add?patient=${PATIENT}`, WORDS.camera],
    ]);
    cleanup();
    const member = draw(WORDS, false);
    expect(member.querySelectorAll("a").length).toBe(0);
    expect(member.textContent).not.toContain(WORDS.camera);
  });

  it("theFieldIsNamedAndTheSendButtonSaysSo", () => {
    const container = draw();
    const field = container.querySelector<HTMLInputElement>(".composer__field")!;
    expect(field.getAttribute("aria-label")).toBe(WORDS.field);
    expect(field.getAttribute("autocomplete")).toBe("off");
    expect([...container.querySelectorAll("button")].map((b) => [b.type, b.textContent || b.getAttribute("aria-label")])).toEqual([
      ["submit", WORDS.send],
    ]);
  });
});

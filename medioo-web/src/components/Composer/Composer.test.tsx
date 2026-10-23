import { cleanup, fireEvent, render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { composerWords } from "@/app/app/(signed-in)/home/chat-words";
import { t, type Lang } from "@/copy";
import { Composer, type ComposerWords } from "./Composer";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const LANGS: readonly Lang[] = ["bn", "en"];
const PATIENT = "5e9edf6d-0a2a-4913-8675-9d07a4100002";

/** Words that name their own role, so a test sees which one landed where (the real ones are checked below). */
const WORDS: ComposerWords = {
  field: "field-label",
  send: "send-label",
  standing: "standing-line",
  reply: "reply-line\nsecond-line",
  camera: "camera-label",
};

const QUESTION = "Is this value all right?";

function ask(container: HTMLElement, text: string) {
  const field = container.querySelector<HTMLInputElement>(".composer__field");
  expect(field, "the composer's field").not.toBeNull();
  fireEvent.change(field!, { target: { value: text } });
  fireEvent.submit(field!.form!);
}

const bubbles = (container: HTMLElement, surface: "sent" | "recv") =>
  [...container.querySelectorAll(`.message-bubble--${surface} .message-bubble__body`)].map((b) => b.textContent);

describe("Composer", () => {
  it("askingSendsNothingAndRepliesAsWhatsAppDoes", () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    const { container } = render(<Composer words={WORDS} owner patientId={PATIENT} preview={false} />);
    expect(bubbles(container, "sent"), "nothing before a send").toEqual([]);
    ask(container, QUESTION);
    // The family's own line on the right, then Medioo's reply on the left, in that order.
    const order = [...container.querySelectorAll(".message-bubble")].map((b) =>
      b.classList.contains("message-bubble--sent") ? "sent" : "recv",
    );
    expect(order).toEqual(["sent", "recv"]);
    expect(bubbles(container, "sent")).toEqual([QUESTION]);
    expect(bubbles(container, "recv")).toEqual([WORDS.reply]);
    expect(fetchSpy).toHaveBeenCalledTimes(0);
    // The field is cleared for the next line.
    expect(container.querySelector<HTMLInputElement>(".composer__field")!.value).toBe("");
  });

  it("theReplyIsWrittenIntoALiveRegionThatWasThereFirst", () => {
    // A live region that enters the DOM already holding text is not reliably announced (VoiceOver, NVDA): the region is
    // on the page, empty, before the first send, and the reply (the one carrying the red-flag line) is written into it.
    const { container } = render(<Composer words={WORDS} owner patientId={PATIENT} preview={false} />);
    const before = container.querySelectorAll("[aria-live]");
    expect(before.length, "one live region before the first send").toBe(1);
    const region = before[0]!;
    expect(region.getAttribute("aria-live")).toBe("polite");
    expect(region.textContent, "and it is empty").toBe("");
    expect(region.children.length).toBe(0);
    ask(container, QUESTION);
    const after = container.querySelectorAll("[aria-live]");
    expect(after.length).toBe(1);
    expect(after[0], "the same node, not a new one").toBe(region);
    expect(region.textContent).toContain(WORDS.reply);
  });

  it("anEmptyLineSendsNothingAtAll", () => {
    const { container } = render(<Composer words={WORDS} owner patientId={PATIENT} preview={false} />);
    ask(container, "   ");
    expect(container.querySelectorAll(".message-bubble").length).toBe(0);
  });

  it("theNotYetReplyCarriesTheRedFlagLine", () => {
    for (const lang of LANGS) {
      const words = composerWords(lang);
      const { container } = render(<Composer words={words} owner patientId={PATIENT} preview={false} />);
      ask(container, QUESTION);
      const [reply] = bubbles(container, "recv");
      expect(reply, `the reply (${lang})`).toContain(t(lang, "chat.ask.reply"));
      expect(reply, `the red-flag line (${lang})`).toContain(t(lang, "safety.red_flag_line"));
      // The decline first, the red-flag line after it, as WhatsApp appends it to wa.fallback.
      expect(reply!.indexOf(t(lang, "chat.ask.reply"))).toBeLessThan(reply!.indexOf(t(lang, "safety.red_flag_line")));
      // The standing line stays under the field.
      expect(container.querySelector(".composer__standing")?.textContent).toBe(t(lang, "chat.soon"));
      cleanup();
    }
  });

  it("theTypedTextIsNotStored", () => {
    document.cookie = "e2e_probe=1";
    localStorage.setItem("kept", "1");
    const before = { local: { ...localStorage }, session: { ...sessionStorage }, cookie: document.cookie };
    const { container } = render(<Composer words={WORDS} owner patientId={PATIENT} preview={false} />);
    ask(container, QUESTION);
    expect({ local: { ...localStorage }, session: { ...sessionStorage }, cookie: document.cookie }).toEqual(before);
    expect(JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }) + document.cookie).not.toContain(QUESTION);
  });

  it("onlyTheOwnerHasTheCamera", () => {
    const owner = render(<Composer words={WORDS} owner patientId={PATIENT} preview={false} />).container;
    const cameras = [...owner.querySelectorAll<HTMLAnchorElement>("a")];
    expect(cameras.map((a) => [a.getAttribute("href"), a.getAttribute("aria-label")])).toEqual([
      [`/app/add?patient=${PATIENT}`, WORDS.camera],
    ]);
    cleanup();
    const member = render(<Composer words={WORDS} owner={false} patientId={PATIENT} preview={false} />).container;
    expect(member.querySelectorAll("a").length).toBe(0);
    expect(member.textContent).not.toContain(WORDS.camera);
  });

  it("theFieldIsNamedAndTheSendButtonSaysSo", () => {
    const { container } = render(<Composer words={WORDS} owner patientId={PATIENT} preview={false} />);
    const field = container.querySelector<HTMLInputElement>(".composer__field")!;
    expect(field.getAttribute("aria-label")).toBe(WORDS.field);
    expect(field.getAttribute("autocomplete")).toBe("off");
    expect([...container.querySelectorAll("button")].map((b) => [b.type, b.textContent || b.getAttribute("aria-label")])).toEqual([
      ["submit", WORDS.send],
    ]);
  });
});

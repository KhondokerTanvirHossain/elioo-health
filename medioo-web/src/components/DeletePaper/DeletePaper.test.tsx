import { act, cleanup, fireEvent, render } from "@testing-library/react";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { DeletePaper, type DeleteWords } from "./DeletePaper";

const apiGet = vi.fn();
const apiSend = vi.fn();
vi.mock("@/api/browser", () => ({
  apiGet: (...args: unknown[]) => apiGet(...args),
  apiSend: (...args: unknown[]) => apiSend(...args),
}));

const DOC = "5e9edf6d-0a2a-4913-8675-9d07a4100003";
const words: DeleteWords = {
  open: "Delete this paper",
  question: "Delete this paper?",
  warn: "This cannot be undone.",
  yes: "Yes, delete",
  no: "No, go back",
  gone: "This paper is no longer here",
  refused: "You are not allowed to do this.",
  failed: "Something went wrong.",
};

beforeAll(() => {
  // jsdom has no modal dialog; the platform's showModal and close are all SheetDialog asks of it.
  HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) {
    this.removeAttribute("open");
    this.dispatchEvent(new Event("close"));
  };
});

const replace = vi.fn();
beforeEach(() => {
  apiGet.mockReset();
  apiSend.mockReset();
  replace.mockReset();
  vi.stubGlobal("location", { ...window.location, href: `http://localhost/app/documents/${DOC}`, replace });
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const token = (value: string) => ({ kind: "ok", status: 200, body: { confirm_token: value, expires_at: "2026-10-20T04:10:00Z" } });

async function press(el: HTMLElement, name: string) {
  const button = [...el.querySelectorAll("button")].find((b) => b.textContent === name);
  if (!button) throw new Error(`no button ${name}`);
  await act(async () => {
    fireEvent.click(button);
  });
}

describe("DeletePaper", () => {
  it("deleteAsksThenDeletesWithTheToken", async () => {
    apiGet.mockResolvedValue(token("tok-1"));
    apiSend.mockResolvedValue({ kind: "ok", status: 200, body: { objects_deleted: 3, rows_deleted: 9 } });
    const el = render(<DeletePaper documentId={DOC} words={words} />).container;
    await press(el, words.open);
    const dialog = el.querySelector("dialog")!;
    expect(dialog.hasAttribute("open")).toBe(true);
    expect(dialog.querySelector("h2")?.textContent).toBe(words.question);
    expect(dialog.textContent).toContain(words.warn);
    // Asking sends nothing: only the yes does.
    expect(apiGet).not.toHaveBeenCalled();
    await press(el, words.yes);
    expect(apiGet).toHaveBeenCalledWith(`/app/api/documents/${DOC}/delete-token`);
    expect(apiSend).toHaveBeenCalledWith("DELETE", `/app/api/documents/${DOC}`, { confirm_token: "tok-1" });
    expect(apiGet.mock.invocationCallOrder[0]!).toBeLessThan(apiSend.mock.invocationCallOrder[0]!);
    expect(replace).toHaveBeenCalledWith(`/app/documents/${DOC}?deleted=1`);
  });

  it("confirmRequiredStartsAgain", async () => {
    apiGet.mockResolvedValueOnce(token("tok-expired")).mockResolvedValueOnce(token("tok-fresh"));
    apiSend
      .mockResolvedValueOnce({ kind: "refused", status: 400, reason: "CONFIRM_REQUIRED" })
      .mockResolvedValueOnce({ kind: "ok", status: 200, body: { objects_deleted: 1, rows_deleted: 1 } });
    const el = render(<DeletePaper documentId={DOC} words={words} />).container;
    await press(el, words.open);
    await press(el, words.yes);
    expect(replace).not.toHaveBeenCalled();
    expect(el.querySelector("dialog")!.hasAttribute("open"), "the sheet stays for the next yes").toBe(true);
    await press(el, words.yes);
    expect(apiGet).toHaveBeenCalledTimes(2);
    expect(apiSend.mock.calls.map((c) => (c[2] as { confirm_token: string }).confirm_token)).toEqual(["tok-expired", "tok-fresh"]);
    expect(replace).toHaveBeenCalledTimes(1);
  });

  it("aDeleteRacedByAnotherMemberSaysGone", async () => {
    apiGet.mockResolvedValue(token("tok-1"));
    apiSend.mockResolvedValue({ kind: "refused", status: 404, reason: "NOT_FOUND" });
    const el = render(<DeletePaper documentId={DOC} words={words} />).container;
    await press(el, words.open);
    await press(el, words.yes);
    expect(el.querySelector(".toast__text")?.textContent).toBe(words.gone);
    expect(replace).not.toHaveBeenCalled();
  });

  it("aShareMembersForcedDeleteIsCopyNeverACrash", async () => {
    apiGet.mockResolvedValue({ kind: "refused", status: 403, reason: "VIEW_ONLY" });
    const el = render(<DeletePaper documentId={DOC} words={words} />).container;
    await press(el, words.open);
    await press(el, words.yes);
    expect(apiSend).not.toHaveBeenCalled();
    expect(el.querySelector(".toast__text")?.textContent).toBe(words.refused);
  });
});

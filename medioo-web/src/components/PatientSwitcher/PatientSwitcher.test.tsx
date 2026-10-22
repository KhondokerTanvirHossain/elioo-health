import { cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { PatientSwitcher, type SwitcherWords } from "./PatientSwitcher";

/*
 * The switcher (plan W4): the current patient's name in a button that opens a sheet listing whose papers the family
 * may see. A choice is remembered by Java (PUT /app/api/active-patient, the cookie the HTML pages use) and the same tab
 * opens again with `?patient=`; a choice Java refuses (a patient this family no longer sees: 404) is said in words and
 * goes nowhere. The words are resolved by the server (shell-words.ts): this island ships no copy.
 */

const WORDS: SwitcherWords = {
  label: "label-for-Ma",
  title: "whose-title",
  closeLabel: "close-label",
  refused: "refused-line",
  failed: "failed-line",
  patients: [
    { id: "5e9edf6d-0a2a-4913-8675-9d07a4100002", name: "Ma", urgentWord: "urgent-word" },
    { id: "5e9edf6d-0a2a-4913-8675-9d07a4100012", name: "Baba" },
  ],
};

const assign = vi.fn();
let calls: { url: string; init: RequestInit }[] = [];

function answer(status: number, body?: unknown) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, init });
      return new Response(body === undefined ? null : JSON.stringify(body), { status });
    }),
  );
}

beforeAll(() => {
  // jsdom's <dialog> has no showModal() or close(): the platform's documented effect on the element, nothing more.
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

beforeEach(() => {
  calls = [];
  assign.mockClear();
  vi.stubGlobal("location", { pathname: "/app/preview/timeline", search: "?month=2026-10", assign });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function open() {
  const view = render(<PatientSwitcher words={WORDS} current={WORDS.patients[0]!.id} preview />);
  fireEvent.click(view.getByRole("button", { name: "label-for-Ma" }));
  const dialog = view.container.querySelector("dialog")!;
  expect(dialog.open, "the sheet opened").toBe(true);
  return { ...view, dialog };
}

const choice = (dialog: HTMLDialogElement, name: string) =>
  [...dialog.querySelectorAll("button")].find((b) => b.textContent?.startsWith(name))!;

describe("PatientSwitcher", () => {
  it("theSheetListsEveryPatientAndMarksTheCurrentOne", () => {
    const { dialog } = open();
    expect(dialog.getAttribute("aria-labelledby"), "the sheet is named by its title").toBeTruthy();
    expect(dialog.textContent).toContain("whose-title");
    const options = [...dialog.querySelectorAll("button[data-patient]")];
    expect(options.map((b) => b.getAttribute("data-patient"))).toEqual(WORDS.patients.map((p) => p.id));
    expect(options.map((b) => b.getAttribute("aria-current"))).toEqual(["true", null]);
    // The urgent patient's mark is the shape and the word.
    expect(options[0]!.querySelector(".urgent-mark svg[data-shape='octagon']")).not.toBeNull();
    expect(options[0]!.textContent).toContain("urgent-word");
    expect(options[1]!.querySelector(".urgent-mark")).toBeNull();
  });

  it("choosingPutsTheChoiceAndNavigates", async () => {
    answer(204);
    const { dialog } = open();
    fireEvent.click(choice(dialog, "Baba"));
    await waitFor(() => expect(assign).toHaveBeenCalledTimes(1));
    expect(calls).toHaveLength(1);
    expect(calls[0]!.url).toBe("/app/api/active-patient");
    expect(calls[0]!.init.method).toBe("PUT");
    expect(JSON.parse(String(calls[0]!.init.body))).toEqual({ patient_id: WORDS.patients[1]!.id });
    expect(new Headers(calls[0]!.init.headers).get("x-medioo")).toBe("1");
    // The same tab, inside the preview, with the choice in the address: nothing else of the old query is kept.
    expect(assign).toHaveBeenCalledWith(`/app/preview/timeline?patient=${WORDS.patients[1]!.id}`);
  });

  it("aRefusedChoiceSaysSo", async () => {
    answer(404, { reason: "NOT_FOUND" });
    const { dialog, findByRole } = open();
    fireEvent.click(choice(dialog, "Baba"));
    const status = await findByRole("status");
    expect(status.textContent).toBe("refused-line");
    expect(assign).not.toHaveBeenCalled();
    expect(dialog.open, "the sheet stays open on the refusal").toBe(true);
  });

  it("aFailedSendSaysSoAndGoesNowhere", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("offline");
      }),
    );
    const { dialog, findByRole } = open();
    fireEvent.click(choice(dialog, "Baba"));
    expect((await findByRole("status")).textContent).toBe("failed-line");
    expect(assign).not.toHaveBeenCalled();
  });

  it("theCurrentPatientIsNotSentAgain", async () => {
    answer(204);
    const { dialog } = open();
    fireEvent.click(choice(dialog, "Ma"));
    await waitFor(() => expect(dialog.open).toBe(false));
    expect(calls, "choosing the patient already shown sends nothing").toEqual([]);
    expect(assign).not.toHaveBeenCalled();
  });
});

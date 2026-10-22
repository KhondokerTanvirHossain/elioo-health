import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { UploadOptionsJson } from "@/api/types";
import { banglaDigits, formatDate, t } from "@/copy";
import capReached from "../../../fixtures/api/upload-options/cap-reached.json";
import ownerOne from "../../../fixtures/api/upload-options/owner-one.json";
import ownerTwo from "../../../fixtures/api/upload-options/owner-two.json";
import viewOnly from "../../../fixtures/api/upload-options/view-only.json";
import { uploadWords } from "../../app/app/(signed-in)/add/upload-words";
import { UploadSheet } from "./UploadSheet";

/*
 * The add sheet (plan W7) over the options Java's UploadApiContractTest wrote. The sheet never decides whose paper it
 * is, never sends for a patient the viewer may only see, holds the page tray to Java's own cap, and asks whether
 * several pages are one report without answering for the family. Its words are resolved by the server
 * (upload-words.ts); the island imports no copy.
 */

const options = (fixture: { body: unknown }) => fixture.body as UploadOptionsJson;

function sheet(fixture: { body: unknown }, opts: { asked?: string | null; replaces?: string | null } = {}) {
  const o = options(fixture);
  return render(
    <UploadSheet options={o} words={uploadWords("bn", o)} preview={false} asked={opts.asked ?? null} replaces={opts.replaces ?? null} />,
  );
}

let made = 0;
beforeEach(() => {
  made = 0;
  // jsdom has no object URLs: each file gets one that names it, so a thumbnail says which file it shows.
  vi.stubGlobal("URL", Object.assign(URL, { createObjectURL: (f: File) => `blob:${f.name}#${++made}`, revokeObjectURL: () => {} }));
});
afterEach(() => cleanup());

const jpeg = (name: string) => new File([new Uint8Array([0xff, 0xd8, 0xff])], name, { type: "image/jpeg" });

/** Chooses files on one of the sheet's inputs, as the picker would. */
function choose(container: HTMLElement, src: "camera" | "gallery" | "pdf", files: File[]) {
  const input = container.querySelector<HTMLInputElement>(`input[type=file][data-src=${src}]`)!;
  expect(input, `the ${src} input`).not.toBeNull();
  fireEvent.change(input, { target: { files } });
}

const send = () => screen.getByRole("button", { name: t("bn", "add.send") });
const thumbs = (container: HTMLElement) => [...container.querySelectorAll<HTMLImageElement>(".page-tray__thumb")].map((i) => i.getAttribute("src"));

describe("the add sheet", () => {
  it("theSheetNeverGuessesThePatient", () => {
    const two = options(ownerTwo);
    expect(two.can_send.length, "the fixture has two to send for").toBe(2);
    expect(two.preselected_patient_id).toBeNull();
    const { container } = sheet(ownerTwo);
    expect(screen.getByText(t("bn", "add.choose"))).toBeTruthy();
    const radios = [...container.querySelectorAll<HTMLInputElement>("input[type=radio][name=patient]")];
    expect(radios.map((r) => r.value)).toEqual(two.can_send.map((p) => p.id));
    expect(radios.filter((r) => r.checked), "no patient chosen for the family").toEqual([]);
    choose(container, "gallery", [jpeg("a.jpg")]);
    expect(send(), "a page and no patient: nothing to send").toHaveProperty("disabled", true);
    fireEvent.click(radios[1]!);
    expect(send()).toHaveProperty("disabled", false);
    cleanup();

    // The other direction: the only one the family may send for is chosen, and the sheet says why.
    const one = options(ownerOne);
    const { container: c1 } = sheet(ownerOne);
    const only = one.can_send[0]!;
    expect(screen.getByText(t("bn", "add.onlyone", only.name_possessive, only.name_objective))).toBeTruthy();
    expect(screen.queryByText(t("bn", "add.choose"))).toBeNull();
    const checked = [...c1.querySelectorAll<HTMLInputElement>("input[type=radio][name=patient]")].filter((r) => r.checked);
    expect(checked.map((r) => r.value)).toEqual([only.id]);
  });

  it("aViewOnlyPatientCannotBeSentFor", () => {
    const o = options(viewOnly);
    const shared = o.view_only[0]!;
    // Opened from the shared patient's paper: the sheet says it cannot send for them, and chooses nobody else instead.
    const { container } = sheet(viewOnly, { asked: shared.id });
    expect(screen.getByText(t("bn", "add.viewonly"))).toBeTruthy();
    const radios = [...container.querySelectorAll<HTMLInputElement>("input[type=radio][name=patient]")];
    expect(radios.map((r) => r.value), "the shared patient is not offered").not.toContain(shared.id);
    expect(radios.filter((r) => r.checked)).toEqual([]);
    cleanup();

    // A viewer who may send for nobody: the line, and no control at all.
    const none = { body: { ...o, can_send: [], preselected_patient_id: null } };
    const { container: c2 } = sheet(none);
    expect(screen.getByText(t("bn", "add.viewonly"))).toBeTruthy();
    expect(c2.querySelectorAll("input, button[data-primary]")).toHaveLength(0);
  });

  it("aCapReachedSheetSaysUntilWhenAndOffersNoPress", () => {
    const o = options(capReached);
    const { container } = sheet(capReached);
    expect(o.allowance.remaining).toBe(0);
    // UiCopy.dateText on bn, through the app's own formatter: the reset is the options' resets_on.
    expect(screen.getByText(t("bn", "add.cap", o.allowance.max!, formatDate("bn", o.allowance.resets_on!)))).toBeTruthy();
    expect(container.querySelectorAll("input[type=file], button[data-primary]")).toHaveLength(0);
  });

  it("theTrayKeepsOrderAndCap", () => {
    const o = options(ownerOne);
    const { container } = sheet(ownerOne);
    choose(container, "gallery", [jpeg("p1.jpg"), jpeg("p2.jpg"), jpeg("p3.jpg")]);
    expect(thumbs(container)).toEqual(["blob:p1.jpg#1", "blob:p2.jpg#2", "blob:p3.jpg#3"]);
    const tray = within(container.querySelector<HTMLElement>(".page-tray")!);
    fireEvent.click(tray.getByRole("button", { name: t("bn", "tray.up").replace("{n}", digits(2)) }));
    expect(thumbs(container), "page 2 moved up").toEqual(["blob:p2.jpg#2", "blob:p1.jpg#1", "blob:p3.jpg#3"]);
    fireEvent.click(tray.getByRole("button", { name: t("bn", "tray.down").replace("{n}", digits(2)) }));
    expect(thumbs(container), "the page now second moved down").toEqual(["blob:p2.jpg#2", "blob:p3.jpg#3", "blob:p1.jpg#1"]);
    fireEvent.click(tray.getByRole("button", { name: t("bn", "tray.remove").replace("{n}", digits(1)) }));
    expect(thumbs(container)).toEqual(["blob:p3.jpg#3", "blob:p1.jpg#1"]);

    // Up to Java's own cap (limits.max_pages), never restated here; at the cap "more pages" is off and says why.
    const more = () => tray.getByRole("button", { name: t("bn", "tray.more") });
    expect(more()).toHaveProperty("disabled", false);
    const left = o.limits.max_pages - 2;
    choose(container, "gallery", Array.from({ length: left }, (_, i) => jpeg(`x${i}.jpg`)));
    expect(thumbs(container)).toHaveLength(o.limits.max_pages);
    expect(more()).toHaveProperty("disabled", true);
    const limit = t("bn", "tray.limit").replace("{max}", digits(o.limits.max_pages)).replace("{n}", digits(o.limits.max_pages));
    expect(screen.getByText(limit)).toBeTruthy();
    // A page past the cap (a drop, or a picker that ignores the disabled button) is not taken in: the tray stays at the
    // cap, and the line saying the cap is still there.
    choose(container, "gallery", [jpeg("over.jpg")]);
    expect(thumbs(container)).toHaveLength(o.limits.max_pages);
    expect(thumbs(container).filter((src) => src!.startsWith("blob:over.jpg"))).toEqual([]);
    expect(screen.getByText(limit)).toBeTruthy();
  });

  it("groupIsAskedNotGuessed", () => {
    const { container } = sheet(ownerOne);
    choose(container, "camera", [jpeg("one.jpg")]);
    // One page is one paper: nothing to ask.
    expect(screen.queryByText(t("bn", "add.group.q"))).toBeNull();
    expect(send()).toHaveProperty("disabled", false);
    choose(container, "camera", [jpeg("two.jpg")]);
    expect(screen.getByText(t("bn", "add.group.q"))).toBeTruthy();
    const groups = [...container.querySelectorAll<HTMLInputElement>("input[type=radio][name=group]")];
    expect(groups.map((g) => g.value)).toEqual(["one", "separate"]);
    expect(groups.filter((g) => g.checked), "neither answer chosen for the family").toEqual([]);
    expect(send(), "two pages and no answer: nothing to send").toHaveProperty("disabled", true);
    fireEvent.click(groups[1]!);
    expect(send()).toHaveProperty("disabled", false);
  });
});

const digits = (n: number) => banglaDigits(String(n));

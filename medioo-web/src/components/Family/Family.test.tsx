import { cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { FamilyJson } from "@/api/types";
import { familyWords } from "@/app/app/(signed-in)/family/family-words";
import { t } from "@/copy";
import mixed from "../../../fixtures/api/family/mixed.json";
import owner from "../../../fixtures/api/family/owner.json";
import remindersOff from "../../../fixtures/api/family/reminders-off.json";
import shareMember from "../../../fixtures/api/family/share-member.json";
import { ChronicFlags } from "./ChronicFlags";
import { FamilyScreen } from "./FamilyScreen";
import { LanguageSwitch } from "./LanguageSwitch";
import { PatientCard } from "./PatientCard";
import { ReminderSwitch } from "./ReminderSwitch";
import { ThemeSwitch } from "./ThemeSwitch";

/*
 * family and settings (plan W10) over the bodies Java's FamilyApiContractTest wrote (fixtures/api/family/*.json): the
 * owner and the share member, the flags (offered and typed), the reminder stop that cannot be undone from here, the
 * family switch only for someone it changes anything for (S5), the theme and the language, sign out. The words are
 * family-words.ts's, resolved on the server: every client island here takes them as props.
 */

const OWNER = owner.body as FamilyJson;
const SHARE = shareMember.body as FamilyJson;
const OFF = remindersOff.body as FamilyJson;
const MIXED = mixed.body as FamilyJson;
const words = (family: FamilyJson) => familyWords("bn", family);

/*
 * jsdom has an HTMLDialogElement with no showModal() or close(): given here with the platform's documented effect on
 * the element (open set; open cleared and a `close` event), as Sheet.test.tsx does. The stop's confirm is a SheetDialog.
 */
beforeAll(() => {
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

/** The open confirm sheet under `root`, or null. */
const openDialog = (root: HTMLElement) => root.querySelector<HTMLDialogElement>("dialog[open]");

const assign = vi.fn();
const reload = vi.fn();
let calls: { url: string; method: string; body: unknown }[] = [];

/** fetch answers `status` (and `body`) to every call, and every call is recorded with its parsed JSON body. */
function answer(status: number, body?: unknown) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, method: init.method ?? "GET", body: typeof init.body === "string" ? JSON.parse(init.body) : null });
      return new Response(body === undefined || status === 204 ? null : JSON.stringify(body), { status });
    }),
  );
}

beforeEach(() => {
  calls = [];
  assign.mockClear();
  reload.mockClear();
  vi.stubGlobal("location", { pathname: "/app/family", search: "", assign, reload });
  document.documentElement.removeAttribute("data-theme");
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute("data-theme");
});

const screen = (family: FamilyJson) =>
  render(<FamilyScreen family={family} words={words(family)} theme="auto" lang="bn" signOutTo="/app/" />);

function card(family: FamilyJson, index: number) {
  const patient = family.patients[index]!;
  const w = words(family);
  return { patient, view: render(<PatientCard patient={patient} words={w.patients[patient.id]!} flags={w.flags} headingLevel={2} />) };
}

/** Every heading under `root` a reader meets, as `h<level> text`, in document order: a closed sheet's title is not one. */
const outline = (root: HTMLElement) =>
  [...root.querySelectorAll("h1, h2, h3, h4, h5, h6")]
    .filter((h) => h.closest("dialog:not([open])") === null)
    .map((h) => `${h.tagName.toLowerCase()} ${h.textContent?.trim()}`);

const buttonNamed = (root: HTMLElement, name: string) =>
  [...root.querySelectorAll("button")].find((b) => b.textContent?.trim() === name);

describe("family", () => {
  it("theNumberIsShownOnlyHere", () => {
    const { container } = screen(OWNER);
    const number = OWNER.account.number;
    // Every place the number's text occurs, by element: exactly one, and that one is inside the account section.
    const holders = [...container.querySelectorAll("*")].filter(
      (el) => [...el.childNodes].some((n) => n.nodeType === Node.TEXT_NODE && n.textContent?.includes(number)),
    );
    expect(holders, "elements whose own text holds the number").toHaveLength(1);
    expect(holders[0]!.closest(".family-account"), "the number is in FamilyAccount").not.toBeNull();
    expect(container.textContent!.split(number).length - 1, "occurrences of the number on the page").toBe(1);
    // The guard: the patients were rendered too, so "once" was judged over the whole tab.
    expect(container.querySelectorAll("[data-patient-card]")).toHaveLength(OWNER.patients.length);
  });

  it("aShareMemberSeesFlagsReadOnly", () => {
    const { patient, view } = card(SHARE, 0);
    expect(patient.owner, "the fixture's patient is shared with the viewer").toBe(false);
    const root = view.container;
    expect([...root.querySelectorAll("input, button, textarea, select")].map((e) => e.outerHTML), "controls for a share member").toEqual(
      [],
    );
    // The flags as text, in their copy.
    const flags = [...root.querySelectorAll("[data-flag]")].map((e) => e.textContent);
    expect(flags).toEqual([t("bn", "flag.kidney")]);
    expect(root.textContent).toContain(t("bn", "patients.shared"));
    // A statement over them, not the owner's instruction to tick (D30: there is nothing to tick).
    const title = root.querySelector(".family-flags__title")?.textContent;
    expect(title, "the read-only flags' title").toBe(t("bn", "flags.readonly.title"));
    expect(root.textContent).not.toContain(t("bn", "profile.flags"));
    expect(root.textContent).not.toContain(t("bn", "family.flags.hint"));
  });

  it("aShareMemberWithNoFlagsRecordedIsToldSo", () => {
    const empty: FamilyJson = { ...SHARE, patients: [{ ...SHARE.patients[0]!, chronic_flags: [] }] };
    const { view } = card(empty, 0);
    const block = view.container.querySelector<HTMLElement>(".family-flags")!;
    expect(block.querySelector(".family-flags__title")?.textContent).toBe(t("bn", "flags.readonly.title"));
    expect(block.querySelector(".family-flags__none")?.textContent, "an empty list says so").toBe(t("bn", "flags.none"));
    expect(block.querySelectorAll("[data-flag]")).toHaveLength(0);
  });

  it("aTypedFlagIsKeptAndShownAsTyped", async () => {
    const { patient, view } = card(OWNER, 0);
    const typed = patient.chronic_flags.find((f) => f.label_key === null)!;
    expect(typed, "the fixture holds a typed flag").toBeDefined();
    const box = view.getByRole("checkbox", { name: typed.value });
    expect((box as HTMLInputElement).checked, "the typed flag is shown, ticked, under its own text").toBe(true);
    answer(200, { ...patient });
    fireEvent.click(view.getByRole("button", { name: t("bn", "profile.save") }));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]).toEqual({
      url: `/app/api/patients/${patient.id}/flags`,
      method: "PUT",
      body: { chronic_flags: patient.chronic_flags.map((f) => f.value) },
    });
    await waitFor(() => expect(view.container.textContent).toContain(t("bn", "profile.saved")));
  });

  it("aNewTypedFlagIsAppendedTrimmedAndShownAfterSave", async () => {
    const { patient, view } = card(OWNER, 0);
    const stored = patient.chronic_flags.map((f) => f.value);
    expect(stored, "the fixture has no migraine yet").not.toContain("migraine");
    const field = view.getByRole("textbox", { name: t("bn", "flag.other") }) as HTMLInputElement;
    fireEvent.change(field, { target: { value: "  migraine " } });
    // Java's answer is the saved patient (FamilyApiHandler): the typed flag stored, named by no key.
    answer(200, { ...patient, chronic_flags: [...patient.chronic_flags, { value: "migraine", label_key: null }] });
    fireEvent.click(view.getByRole("button", { name: t("bn", "profile.save") }));
    await waitFor(() => expect(calls).toHaveLength(1));
    const sent = (calls[0]!.body as { chronic_flags: string[] }).chronic_flags;
    expect(sent.at(-1), "the typed flag, trimmed, last").toBe("migraine");
    expect(calls[0]!.body, "the whole list, the typed one trimmed and last").toEqual({ chronic_flags: [...stored, "migraine"] });
    const box = await view.findByRole("checkbox", { name: "migraine" });
    expect((box as HTMLInputElement).checked, "the saved flag is shown, ticked, from Java's answer").toBe(true);
    expect(field.value, "the field is empty again").toBe("");
  });

  it("aTypedFlagAlreadyListedIsSentOnce", async () => {
    const { patient, view } = card(OWNER, 0);
    const typed = patient.chronic_flags.find((f) => f.label_key === null)!.value;
    fireEvent.change(view.getByRole("textbox", { name: t("bn", "flag.other") }), { target: { value: ` ${typed}  ` } });
    answer(200, { ...patient });
    fireEvent.click(view.getByRole("button", { name: t("bn", "profile.save") }));
    await waitFor(() => expect(calls).toHaveLength(1));
    const sent = (calls[0]!.body as { chronic_flags: string[] }).chronic_flags;
    expect(sent.filter((v) => v === typed), `${typed} in the list sent`).toHaveLength(1);
    expect(sent).toEqual(patient.chronic_flags.map((f) => f.value));
  });

  it("theCardShowsTheListJavaSavedNotTheOneSent", async () => {
    // Java normalises what it stores (FamilyAccountService.normalizeFlags: trimmed, lower-cased): the card shows that.
    const { patient, view } = card(OWNER, 0);
    fireEvent.change(view.getByRole("textbox", { name: t("bn", "flag.other") }), { target: { value: "Migraine" } });
    answer(200, { ...patient, chronic_flags: [{ value: "kidney", label_key: "flag.kidney" }, { value: "migraine", label_key: null }] });
    fireEvent.click(view.getByRole("button", { name: t("bn", "profile.save") }));
    await waitFor(() => expect(view.container.textContent).toContain(t("bn", "profile.saved")));
    const ticked = [...view.container.querySelectorAll<HTMLInputElement>("input[type=checkbox]")].filter((c) => c.checked).map((c) => c.value);
    expect(ticked, "exactly Java's saved list is ticked").toEqual(["kidney", "migraine"]);
    expect(view.queryByRole("checkbox", { name: "Migraine" }), "what was typed, before Java normalised it").toBeNull();
  });

  it("theSaveKeepsFocusAndItsStatusRegionIsAlreadyThere", async () => {
    const patient = OWNER.patients[0]!;
    const w = words(OWNER);
    const view = render(<ChronicFlags patientId={patient.id} owner stored={patient.chronic_flags} words={w.flags} />);
    const status = view.container.querySelector<HTMLElement>(".family-flags__status");
    expect(status, "the status region is mounted before any send").not.toBeNull();
    expect(status!.getAttribute("role")).toBe("status");
    expect(status!.textContent).toBe("");
    const save = view.getByRole("button", { name: t("bn", "profile.save") }) as HTMLButtonElement;
    save.focus();
    let release: (r: Response) => void = () => undefined;
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>((r) => (release = r))));
    fireEvent.click(save);
    await waitFor(() => expect(save.getAttribute("aria-disabled")).toBe("true"));
    expect(save.disabled, "a disabled button would drop the focus").toBe(false);
    expect(view.container.querySelector("fieldset:disabled"), "the whole form disabled mid-save").toBeNull();
    expect(document.activeElement, "focus while saving").toBe(save);
    release(new Response(JSON.stringify({ reason: "TOO_MANY" }), { status: 400 }));
    await waitFor(() => expect(view.container.querySelector(".family-flags__status")!.textContent).toBe(t("bn", "app.err.generic")));
    expect(view.container.querySelector(".family-flags__status"), "the same region, written into").toBe(status);
    expect(document.activeElement, "focus after the refusal").toBe(save);
  });

  it("aPatientStopAsksFirst", async () => {
    const { patient, view } = card(OWNER, 0);
    const poss = patient.name_possessive;
    answer(200, { ...patient, reminders: "stopped_for_patient" });
    fireEvent.click(view.getByRole("button", { name: t("bn", "profile.nudges.stop", poss) }));
    const dialog = openDialog(view.container);
    expect(dialog, "the stop opens a confirm").not.toBeNull();
    expect(dialog!.querySelector(".sheet__title")?.textContent).toBe(t("bn", "optout.q"));
    expect(calls, "nothing sent on the first press").toEqual([]);
    // Keep: nothing sent, the stop still offered.
    fireEvent.click(buttonNamed(dialog!, t("bn", "optout.keep"))!);
    expect(openDialog(view.container)).toBeNull();
    expect(calls, "nothing sent after keep").toEqual([]);
    expect(view.getByRole("button", { name: t("bn", "profile.nudges.stop", poss) })).toBeDefined();
  });

  it("remindersCanOnlyBeStopped", async () => {
    const { patient, view } = card(OWNER, 0);
    expect(patient.reminders).toBe("on");
    const region = () => view.container.querySelector<HTMLElement>("[data-reminders]")!;
    const poss = patient.name_possessive;
    expect(region().textContent).toContain(t("bn", "profile.nudges.on", poss));
    answer(200, { ...patient, reminders: "stopped_for_patient" });
    fireEvent.click(view.getByRole("button", { name: t("bn", "profile.nudges.stop", poss) }));
    fireEvent.click(buttonNamed(openDialog(view.container)!, t("bn", "optout.patient"))!);
    await waitFor(() => expect(region().textContent).toContain(t("bn", "profile.nudges.off", poss)));
    // The stop button is gone, so the focus moves to the line that says what is true now.
    const line = region().querySelector<HTMLElement>(".family-reminders__line")!;
    expect(document.activeElement, "focus after the stop").toBe(line);
    expect(calls).toEqual([{ url: `/app/api/patients/${patient.id}/reminders`, method: "PUT", body: { on: false } }]);
    expect(
      [...region().querySelectorAll("button, input, [role=switch]")].map((e) => e.outerHTML),
      "a stopped reminder offers no control to turn it on",
    ).toEqual([]);

    // A patient whose reminders were already stopped offers none either, from the first render.
    cleanup();
    const stopped = card(OWNER, 1);
    expect(stopped.patient.reminders).toBe("stopped_for_patient");
    const r = stopped.view.container.querySelector<HTMLElement>("[data-reminders]")!;
    expect(r.textContent).toContain(t("bn", "profile.nudges.off", stopped.patient.name_possessive));
    expect([...r.querySelectorAll("button, input, [role=switch]")].map((e) => e.outerHTML)).toEqual([]);
  });

  it("theFamilySwitchIsHiddenForAMemberWhoOwnsNoone", () => {
    expect(SHARE.patients.every((p) => !p.owner), "the viewer owns no patient").toBe(true);
    const { container } = screen(SHARE);
    expect(container.querySelector("[data-family-reminders]"), "a family reminder switch for a viewer who owns no patient").toBeNull();
    expect(container.textContent).not.toContain(t("bn", "optout.family"));
    expect(container.textContent).not.toContain(t("bn", "optout.done.family"));
  });

  it("theFamilySwitchShowsForAnOwnerWhoAlsoSeesASharedPatient", () => {
    // Java's mixed case: one patient owned, one shared with this number. Owning anyone keeps the family's stop (S5).
    expect(MIXED.patients.some((p) => p.owner), "the viewer owns a patient").toBe(true);
    expect(MIXED.patients.some((p) => !p.owner), "and sees a shared one").toBe(true);
    const { container } = screen(MIXED);
    expect(container.querySelector("[data-family-reminders]"), "the family switch for an owner who also holds a share").not.toBeNull();
    expect(container.querySelectorAll("[data-patient-card]")).toHaveLength(MIXED.patients.length);
  });

  it("theFamilyStopAsksFirst", async () => {
    const view = screen(OWNER);
    const region = view.container.querySelector<HTMLElement>("[data-family-reminders]")!;
    answer(200, OFF);
    fireEvent.click(buttonNamed(region, t("bn", "optout.family"))!);
    const dialog = openDialog(region);
    expect(dialog, "the family stop opens a confirm").not.toBeNull();
    expect(dialog!.querySelector(".sheet__title")?.textContent).toBe(t("bn", "optout.q"));
    expect(calls, "nothing sent on the first press").toEqual([]);
    fireEvent.click(buttonNamed(dialog!, t("bn", "optout.family"))!);
    await waitFor(() => expect(calls).toEqual([{ url: "/app/api/family/reminders", method: "PUT", body: { on: false } }]));
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
    expect(calls, "exactly one PUT").toHaveLength(1);
  });

  it("theFamilyStopsKeepSendsNothing", () => {
    const view = screen(OWNER);
    const region = view.container.querySelector<HTMLElement>("[data-family-reminders]")!;
    answer(200, OFF);
    fireEvent.click(buttonNamed(region, t("bn", "optout.family"))!);
    fireEvent.click(buttonNamed(openDialog(region)!, t("bn", "optout.keep"))!);
    expect(openDialog(region), "keep closes the confirm").toBeNull();
    expect(calls).toEqual([]);
    expect(reload).not.toHaveBeenCalled();
    expect(buttonNamed(region, t("bn", "optout.family")), "the stop is still offered").toBeDefined();
  });

  it("theFamilySwitchShowsForAnOwner", async () => {
    const view = screen(OWNER);
    const region = view.container.querySelector<HTMLElement>("[data-family-reminders]");
    expect(region, "an owner's family switch").not.toBeNull();
    answer(200, OFF);
    fireEvent.click(buttonNamed(region!, t("bn", "optout.family"))!);
    fireEvent.click(buttonNamed(openDialog(region!)!, t("bn", "optout.family"))!);
    await waitFor(() => expect(calls).toEqual([{ url: "/app/api/family/reminders", method: "PUT", body: { on: false } }]));
    // The family's stop changes every patient's line: the page is read again from Java.
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
    // A family already stopped says so and offers nothing.
    cleanup();
    const off = screen(OFF).container.querySelector<HTMLElement>("[data-family-reminders]")!;
    expect(off.textContent).toContain(t("bn", "optout.done.family"));
    expect(off.querySelectorAll("button")).toHaveLength(0);
  });

  it("aViewOnlyRefusalIsCopy", async () => {
    // An owner's form whose save Java refuses as VIEW_ONLY (the share was withdrawn while the page was open).
    const patient = OWNER.patients[0]!;
    const w = words(OWNER);
    const view = render(<ChronicFlags patientId={patient.id} owner stored={patient.chronic_flags} words={w.flags} />);
    answer(403, { reason: "VIEW_ONLY" });
    fireEvent.click(view.getByRole("button", { name: t("bn", "profile.save") }));
    await waitFor(() => expect(view.container.textContent).toContain(t("bn", "app.err.forbidden")));
    expect(view.container.textContent).not.toContain(t("bn", "profile.saved"));
  });

  it("theThemePutsAndRepaints", async () => {
    const w = words(OWNER);
    const view = render(<ThemeSwitch current="auto" words={w.account.theme} />);
    expect(view.getByRole("button", { name: t("bn", "theme.auto") }).getAttribute("aria-pressed")).toBe("true");
    answer(204);
    fireEvent.click(view.getByRole("button", { name: t("bn", "theme.dark") }));
    await waitFor(() => expect(document.documentElement.getAttribute("data-theme")).toBe("dark"));
    expect(calls).toEqual([{ url: "/app/api/preferences", method: "PUT", body: { theme: "dark" } }]);
    expect(view.getByRole("button", { name: t("bn", "theme.dark") }).getAttribute("aria-pressed")).toBe("true");
    // "Like the phone" takes the attribute away, so the phone's own scheme applies again.
    fireEvent.click(view.getByRole("button", { name: t("bn", "theme.auto") }));
    await waitFor(() => expect(document.documentElement.hasAttribute("data-theme")).toBe(false));
    expect(calls.map((c) => c.body)).toEqual([{ theme: "dark" }, { theme: "auto" }]);
    expect(reload, "repainted without a reload").not.toHaveBeenCalled();
  });

  it("aThemeJavaRefusedLeavesThePageAsItWas", async () => {
    const w = words(OWNER);
    const view = render(<ThemeSwitch current="auto" words={w.account.theme} />);
    answer(400, { reason: "INVALID" });
    fireEvent.click(view.getByRole("button", { name: t("bn", "theme.light") }));
    await waitFor(() => expect(view.container.textContent).toContain(t("bn", "app.err.generic")));
    expect(document.documentElement.hasAttribute("data-theme")).toBe(false);
    expect(view.getByRole("button", { name: t("bn", "theme.auto") }).getAttribute("aria-pressed")).toBe("true");
  });

  it("languageReloadsInTheNewLanguage", async () => {
    const w = words(OWNER);
    const view = render(<LanguageSwitch current="bn" words={w.account.language} />);
    answer(204);
    fireEvent.click(view.getByRole("button", { name: t("bn", "lang.en") }));
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
    expect(calls).toEqual([{ url: "/app/api/language", method: "PUT", body: { lang: "en" } }]);
  });

  it("signOutIsHere", async () => {
    const view = screen(OWNER);
    const account = view.container.querySelector<HTMLElement>(".family-account")!;
    answer(204);
    fireEvent.click(buttonNamed(account, t("bn", "app.logout"))!);
    await waitFor(() => expect(assign).toHaveBeenCalledWith("/app/"));
    expect(calls).toEqual([{ url: "/app/api/sign-out", method: "POST", body: null }]);
  });

  it("aFamilyWithNoPatientSaysSo", () => {
    const empty: FamilyJson = { ...OWNER, patients: [] };
    const { container } = screen(empty);
    expect(container.textContent).toContain(t("bn", "patients.none"));
    expect(container.querySelectorAll("[data-patient-card]")).toHaveLength(0);
  });
});

describe("headings", () => {
  it("theFamilyTabNestsEachPatientUnderItsGroup", () => {
    const { container } = screen(OWNER);
    const w = words(OWNER);
    const heads = outline(container);
    expect(heads[0], "the page's heading").toBe(`h1 ${t("bn", "tab.family")}`);
    expect(heads[1], "the patients' group").toBe(`h2 ${t("bn", "family.patients.title")}`);
    for (const p of OWNER.patients) {
      expect(heads, `${p.id}'s name, under the group`).toContain(`h3 ${w.patients[p.id]!.title}`);
    }
    expect(heads, "a patient's subtitles, under the patient").toContain(`h4 ${t("bn", "profile.nudges")}`);
    expect(heads.filter((h) => h.startsWith("h2 ")), "the groups at h2").toEqual([
      `h2 ${t("bn", "family.patients.title")}`,
      `h2 ${t("bn", "family.account.title")}`,
    ]);
    // No level is skipped anywhere on the tab.
    const levels = heads.map((h) => Number(h[1]));
    levels.forEach((level, i) => expect(level - (levels[i - 1] ?? 0), `${heads[i]} after ${heads[i - 1]}`).toBeLessThanOrEqual(1));
  });

  it("theProfilePageHasThePatientAtTheTop", () => {
    const { patient, view } = card(OWNER, 0);
    const heads = outline(view.container);
    expect(heads[0]).toBe(`h2 ${words(OWNER).patients[patient.id]!.title}`);
    expect(heads).toContain(`h3 ${t("bn", "profile.nudges")}`);
    expect(heads.some((h) => h.startsWith("h4 ")), "no h4 on the profile page").toBe(false);
  });
});

describe("ReminderSwitch", () => {
  const W = {
    on: "on-line",
    off: "off-line",
    stop: "stop-button",
    refused: "refused-line",
    failed: "failed-line",
    confirm: { title: "confirm-title", yes: "yes-button", keep: "keep-button", close: "close-button" },
  };

  it("aRefusedStopKeepsTheSwitchOnAndSaysWhy", async () => {
    const view = render(<ReminderSwitch path="/app/api/patients/p1/reminders" stopped={false} canStop words={W} />);
    const status = view.container.querySelector<HTMLElement>(".family-reminders__status");
    expect(status, "the status region is mounted before the send").not.toBeNull();
    expect(status!.getAttribute("role")).toBe("status");
    expect(status!.textContent).toBe("");
    answer(403, { reason: "VIEW_ONLY" });
    fireEvent.click(view.getByRole("button", { name: "stop-button" }));
    fireEvent.click(buttonNamed(openDialog(view.container)!, "yes-button")!);
    await waitFor(() => expect(view.container.textContent).toContain("refused-line"));
    expect(view.container.querySelector(".family-reminders__status"), "the same region, written into").toBe(status);
    expect(status!.textContent).toBe("refused-line");
    expect(view.container.textContent).toContain("on-line");
    expect(view.container.textContent).not.toContain("off-line");
  });
});

describe("the segmented choice", () => {
  it("thePressedOptionCarriesATickAndTheOthersNone", () => {
    const w = words(OWNER);
    const view = render(<ThemeSwitch current="dark" words={w.account.theme} />);
    const ticks = (name: string) => view.getByRole("button", { name: t("bn", name as "theme.dark") }).querySelectorAll(".family-choice__tick");
    expect(ticks("theme.dark"), "the pressed option's shape").toHaveLength(1);
    expect(ticks("theme.light")).toHaveLength(0);
    expect(ticks("theme.auto")).toHaveLength(0);
    cleanup();
    const lang = render(<LanguageSwitch current="en" words={w.account.language} />);
    expect(lang.getByRole("button", { name: t("bn", "lang.en") }).querySelectorAll(".family-choice__tick")).toHaveLength(1);
    expect(lang.getByRole("button", { name: t("bn", "lang.bn") }).querySelectorAll(".family-choice__tick")).toHaveLength(0);
  });

  it("aChoiceKeepsTheFocusWhileItSends", async () => {
    const w = words(OWNER);
    const view = render(<ThemeSwitch current="auto" words={w.account.theme} />);
    const dark = view.getByRole("button", { name: t("bn", "theme.dark") }) as HTMLButtonElement;
    dark.focus();
    let release: (r: Response) => void = () => undefined;
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>((r) => (release = r))));
    fireEvent.click(dark);
    await waitFor(() => expect(dark.getAttribute("aria-disabled")).toBe("true"));
    expect(dark.disabled, "a disabled button would drop the focus").toBe(false);
    expect(document.activeElement).toBe(dark);
    release(new Response(null, { status: 204 }));
    await waitFor(() => expect(dark.getAttribute("aria-pressed")).toBe("true"));
    expect(document.activeElement).toBe(dark);
  });
});

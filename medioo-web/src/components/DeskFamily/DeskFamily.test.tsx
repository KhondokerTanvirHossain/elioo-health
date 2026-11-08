import { cleanup, render, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { FamilyJson } from "@/api/types";
import { familyWords } from "@/app/app/(signed-in)/family/family-words";
import { t, type Lang } from "@/copy";
import mixed from "../../../fixtures/api/family/mixed.json";
import owner from "../../../fixtures/api/family/owner.json";
import shareMember from "../../../fixtures/api/family/share-member.json";
import { DeskFamily, type DeskFamilyProps } from "./DeskFamily";

/*
 * The family and account page on the desktop layout (plan desktop-parity Phase 2, row F1; desktop.html
 * `settingsView()`): one column of grouped rows. The people whose papers are kept, each a way to them, the one in view
 * ticked; that person's long-term conditions and reminders; then the account. The working parts are the family tab's
 * own islands (ChronicFlags, ReminderSwitch, FamilyAccount), so every rule they keep is kept here. Java's fixtures.
 */

afterEach(cleanup);

const OWNER = owner.body as FamilyJson;
const MIXED = mixed.body as FamilyJson;
const SHARE = shareMember.body as FamilyJson;

function page(family: FamilyJson, extra: Partial<DeskFamilyProps> = {}) {
  const lang = extra.lang ?? "bn";
  return render(
    <DeskFamily
      lang={lang}
      family={family}
      words={familyWords(lang, family)}
      activeId={family.patients[0]?.id ?? null}
      documents={Object.fromEntries(family.patients.map((p, i) => [p.id, i + 2]))}
      theme="auto"
      signOutTo="/app/"
      {...extra}
    />,
  );
}

const people = (c: HTMLElement) => [...c.querySelectorAll<HTMLAnchorElement>("[data-patient]")];
const section = (c: HTMLElement, name: string) => c.querySelector<HTMLElement>(`[data-section="${name}"]`);

describe("DeskFamily", () => {
  it("theScreenIsTheAccountItemsOwnName", () => {
    const { container } = page(OWNER);
    expect(within(container).getByRole("heading", { level: 1 }).textContent).toBe(t("bn", "desk.account"));
  });

  it("everyPersonIsARowInJavasOrderWithAgeAndPapersAndTheOneInViewTicked", () => {
    const active = OWNER.patients[1]!;
    const { container } = page(OWNER, { activeId: active.id });
    const list = section(container, "people")!;
    expect(within(list).getByRole("heading", { level: 2 }).textContent).toBe(t("bn", "family.patients.title"));
    expect(people(container).map((a) => a.dataset.patient)).toEqual(OWNER.patients.map((p) => p.id));
    people(container).forEach((row, i) => {
      const p = OWNER.patients[i]!;
      expect(row.textContent).toContain(p.name);
      expect(row.textContent).toContain(t("bn", "desk.fam.age", p.age!));
      expect(row.textContent).toContain(t("bn", "desk.fam.papers", i + 2));
      const ticked = row.querySelector("[data-tick]") !== null;
      expect(ticked, `${p.name} ticked`).toBe(p.id === active.id);
      expect(row.getAttribute("aria-current")).toBe(p.id === active.id ? "page" : null);
    });
  });

  it("aPersonSharedWithTheViewerSaysSo", () => {
    const shared = MIXED.patients.find((p) => !p.owner)!;
    const { container } = page(MIXED);
    const row = people(container).find((a) => a.dataset.patient === shared.id)!;
    expect(row.textContent).toContain(t("bn", "patients.shared"));
    const own = people(container).find((a) => a.dataset.patient !== shared.id)!;
    expect(own.textContent).not.toContain(t("bn", "patients.shared"));
  });

  it("thePersonInViewHasTheirConditionsAndRemindersUnderTheirName", () => {
    const p = OWNER.patients[0]!;
    const { container } = page(OWNER, { activeId: p.id });
    const flags = section(container, "flags")!;
    expect(within(flags).getByRole("heading", { level: 2 }).textContent).toBe(t("bn", "desk.fam.flags", p.name_possessive));
    // The family tab's own tick list and save, for this person (its check boxes name the flags Java stored).
    const ticked = [...flags.querySelectorAll<HTMLInputElement>("input[type=checkbox]")].filter((b) => b.checked).map((b) => b.value);
    expect(ticked.sort()).toEqual(p.chronic_flags.map((f) => f.value).sort());
    const reminders = section(container, "reminders")!;
    expect(within(reminders).getByRole("heading", { level: 2 }).textContent).toBe(t("bn", "desk.fam.reminders"));
    expect(reminders.textContent).toContain(t("bn", "desk.fam.reminders.wa"));
    expect(reminders.querySelector("[data-reminders]")!.textContent).toContain(t("bn", "profile.nudges.on", p.name_possessive));
  });

  it("aStoppedReminderOffersNoWayBackHere", () => {
    const stopped = OWNER.patients.find((p) => p.reminders !== "on")!;
    const { container } = page(OWNER, { activeId: stopped.id });
    const reminders = section(container, "reminders")!;
    expect(reminders.textContent).toContain(t("bn", "profile.nudges.off", stopped.name_possessive));
    expect(reminders.querySelectorAll("button, input, [role=switch]")).toHaveLength(0);
  });

  it("aShareMemberSeesTheConditionsAndChangesNothing", () => {
    const p = SHARE.patients[0]!;
    const { container } = page(SHARE, { activeId: p.id });
    const flags = section(container, "flags")!;
    expect(flags.querySelectorAll("input, button")).toHaveLength(0);
    expect(section(container, "reminders")!.querySelectorAll("button")).toHaveLength(0);
  });

  it("nobodyInViewShowsNoPersonsSections", () => {
    const { container } = page(OWNER, { activeId: null });
    expect(section(container, "flags")).toBeNull();
    expect(section(container, "reminders")).toBeNull();
    expect(people(container).filter((a) => a.getAttribute("aria-current") === "page")).toHaveLength(0);
  });

  it("theAccountIsTheFamilyTabsOwn", () => {
    const { container } = page(OWNER);
    const account = section(container, "account")!;
    expect(account.textContent).toContain(OWNER.account.number);
    expect(account.textContent).toContain(t("bn", "app.logout"));
  });

  it("nothingTheDesignShowsThatTheAppCannotDoIsOffered", () => {
    const { container } = page(OWNER);
    // desktop.html's add-a-person, invite and wipe-everything rows wait for their own work (plan Phase 3; S7).
    for (const words of ["\u0995\u09BE\u0989\u0995\u09C7 \u09AF\u09CB\u0997", "\u0986\u09AE\u09A8\u09CD\u09A4\u09CD\u09B0\u09A3", "\u09AE\u09C1\u099B\u09C7 \u09A6\u09BF\u09A8"]) {
      expect(container.textContent).not.toContain(words);
    }
  });

  it.each<Lang>(["bn", "en"])("everyWordIsTheBundles (%s)", (lang) => {
    const { container } = page(OWNER, { lang });
    expect(container.textContent).toContain(t(lang, "desk.account"));
    expect(container.textContent).toContain(t(lang, "desk.fam.reminders.sub"));
  });
});

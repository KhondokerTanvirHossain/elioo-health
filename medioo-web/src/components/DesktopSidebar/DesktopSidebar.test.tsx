import { cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { FollowUpItemJson, FollowUpsJson, MeJson, MePatient } from "@/api/types";
import { banglaDigits, t } from "@/copy";
import longName from "../../../fixtures/api/me/long-name.json";
import proto from "../../../fixtures/api/me/proto.json";
import protoAmmaFollowUps from "../../../fixtures/api/follow-ups/proto-amma.json";
import { DesktopSidebar, daysUntil, nextVisitOf } from "./DesktopSidebar";

/*
 * The desktop sidebar (plan desktop-parity Task 5; desktop.html `sidebar()`): the family with each patient's urgency
 * word, the active patient's ledger (desk.nav.talk, calendar, labs, meds, soon), the next visit, and the account row.
 * Over the prototype's own family (fixtures/api/me/proto.json, Java's output: Amma, 68, THIS_WEEK; Abbu, 74, NOW).
 * No Bangla is typed here (the bangla-literal lint scans tests too): every expected word is the bundle's.
 */

const PROTO = proto.body as MeJson;
const AMMA = PROTO.patients.find((p) => p.age === 68)!;
const ABBU = PROTO.patients.find((p) => p.age === 74)!;
const AMMA_VISIT = (protoAmmaFollowUps.body as FollowUpsJson).items[0]!;
const assign = vi.fn();
let calls: { url: string; init: RequestInit }[] = [];

beforeEach(() => {
  calls = [];
  assign.mockClear();
  vi.stubGlobal("location", { pathname: "/app/preview/home", search: "", assign });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function sidebar(extra: Partial<Parameters<typeof DesktopSidebar>[0]> = {}) {
  return render(
    <DesktopSidebar
      lang="bn"
      preview={false}
      me={PROTO}
      active={AMMA}
      current="talk"
      nextVisit={null}
      today="2026-10-20"
      {...extra}
    />,
  );
}

/** The row of one patient in the family list. */
const rowOf = (container: HTMLElement, p: MePatient) => container.querySelector<HTMLElement>(`[data-patient="${p.id}"]`)!;

describe("DesktopSidebar", () => {
  it("listsEveryPatientWithTheirUrgencyWord", () => {
    expect(AMMA.urgency, "the fixture: Amma is this week").toBe("this_week");
    expect(ABBU.urgency, "the fixture: Abbu is now").toBe("now");
    for (const lang of ["bn", "en"] as const) {
      const { container } = sidebar({ lang });
      const family = container.querySelector(".desktop-sidebar__family")!;
      expect([...family.querySelectorAll("[data-patient]")].map((r) => r.getAttribute("data-patient"))).toEqual(
        PROTO.patients.map((p) => p.id),
      );
      // The word itself, read off its own element: never the colour alone, never a substring of a longer line.
      const word = (p: MePatient) => rowOf(container, p).querySelector("[data-urgency]");
      expect(word(ABBU)!.getAttribute("data-urgency")).toBe("now");
      expect(word(ABBU)!.textContent).toBe(t(lang, "calendar.day.now"));
      expect(word(AMMA)!.getAttribute("data-urgency")).toBe("this_week");
      expect(word(AMMA)!.textContent).toBe(t(lang, "calendar.day.week"));
      // Each word carries a shape too.
      expect(word(ABBU)!.querySelector("svg[data-shape]"), "now: a shape").not.toBeNull();
      expect(word(AMMA)!.querySelector("svg[data-shape]"), "this week: a shape").not.toBeNull();
      cleanup();
    }
  });

  it("aPatientWithNothingUrgentSaysNothing", () => {
    const calm: MeJson = { ...PROTO, patients: PROTO.patients.map((p) => ({ ...p, urgency: null, urgent: false })) };
    const { container } = sidebar({ me: calm });
    expect(container.querySelectorAll("[data-urgency]").length).toBe(0);
  });

  it("anUrgentPatientWithoutALevelIsStillNow", () => {
    // `urgent` and `urgency` come from one Java read; should they ever disagree, the sidebar errs toward the warning.
    const odd: MeJson = { ...PROTO, patients: PROTO.patients.map((p) => (p.id === ABBU.id ? { ...p, urgency: null } : p)) };
    const { container } = sidebar({ me: odd });
    expect(rowOf(container, ABBU).querySelector("[data-urgency]")?.getAttribute("data-urgency"), "Abbu's word").toBe("now");
  });

  it("theActivePatientIsMarkedCurrent", () => {
    const { container } = sidebar({ active: ABBU });
    const current = container.querySelectorAll(".desktop-sidebar__family [aria-current]");
    expect(current.length).toBe(1);
    expect(current[0]!.getAttribute("aria-current")).toBe("page");
    expect(current[0]!.getAttribute("data-patient")).toBe(ABBU.id);
    cleanup();
    expect(sidebar({ active: null }).container.querySelectorAll(".desktop-sidebar__family [aria-current]").length).toBe(0);
  });

  it("aPatientLinkRemembersTheChoiceThenOpensIt", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string, init: RequestInit) => {
        calls.push({ url, init });
        return new Response(null, { status: 204 });
      }),
    );
    const { container } = sidebar();
    const link = rowOf(container, ABBU);
    expect(link.tagName).toBe("A");
    expect(link.getAttribute("href")).toBe(`?patient=${ABBU.id}`);
    fireEvent.click(link, { button: 0 });
    await waitFor(() => expect(assign).toHaveBeenCalledTimes(1));
    expect(calls.map((c) => [c.init.method, c.url, c.init.body])).toEqual([
      ["PUT", "/app/api/active-patient", JSON.stringify({ patient_id: ABBU.id })],
    ]);
    expect(assign).toHaveBeenCalledWith(`/app/preview/home?patient=${ABBU.id}`);
  });

  it("theLedgerNamesTheActivePatient", () => {
    const { container } = sidebar();
    const heading = container.querySelector(".desktop-sidebar__ledger-title")!;
    expect(heading.textContent).toBe(t("bn", "desk.ledger", AMMA.name_possessive));
    cleanup();
    // No patient in view: no ledger, and nothing named.
    const none = sidebar({ active: null }).container;
    expect(none.querySelector(".desktop-sidebar__ledger-title")).toBeNull();
    expect(none.querySelector(".desktop-sidebar__ledger")).toBeNull();
  });

  it("menuLinksGoWhereTheyShouldInPhase1", () => {
    const want = (prefix: string) => [
      [t("bn", "desk.nav.talk"), `${prefix}/home`],
      [t("bn", "desk.nav.calendar"), `${prefix}/timeline`],
      [t("bn", "desk.nav.labs"), `${prefix}/health`],
      [t("bn", "desk.nav.meds"), `${prefix}/health#health-medicines`],
    ];
    const links = (preview: boolean) =>
      [...sidebar({ preview }).container.querySelectorAll<HTMLAnchorElement>(".desktop-sidebar__ledger a")].map((a) => [
        a.querySelector(".desktop-sidebar__label")!.textContent,
        a.getAttribute("href"),
      ]);
    expect(links(true)).toEqual(want("/app/preview"));
    cleanup();
    expect(links(false)).toEqual(want("/app"));
    // The fragment names a heading the health page really has.
    expect(want("/app")[3]![1]).toContain("#health-medicines");
  });

  it("theCurrentLedgerItemIsMarked", () => {
    const { container } = sidebar({ current: "labs" });
    const marked = [...container.querySelectorAll(".desktop-sidebar__ledger [aria-current='page']")];
    expect(marked.map((a) => a.querySelector(".desktop-sidebar__label")!.textContent)).toEqual([t("bn", "desk.nav.labs")]);
  });

  it("soonGroupIsCollapsedAndItsItemsAreNotLinks", () => {
    const { container } = sidebar();
    const group = container.querySelector<HTMLDetailsElement>("details.desktop-sidebar__soon")!;
    expect(group, "the soon group is a disclosure").not.toBeNull();
    expect(group.open, "collapsed").toBe(false);
    expect(group.querySelector("summary")!.textContent).toContain(t("bn", "desk.nav.soon"));
    const items = [...group.querySelectorAll(".desktop-sidebar__soon-item")];
    expect(items.map((i) => i.querySelector(".desktop-sidebar__label")!.textContent)).toEqual(
      (["summary", "card", "conditions", "vaccines", "scans", "devices"] as const).map((k) => t("bn", `desk.soon.${k}`)),
    );
    for (const item of items) {
      expect(item.querySelector(".desktop-sidebar__badge")!.textContent).toBe(t("bn", "desk.soon.badge"));
      expect(item.closest("a"), "not a link").toBeNull();
      expect(item.querySelector("a, button, [tabindex], [href]"), "nothing focusable").toBeNull();
      expect(item.hasAttribute("tabindex")).toBe(false);
    }
  });

  it("nextVisitShowsDaysLeftAndHidesWhenNone", () => {
    expect(AMMA_VISIT.due_date, "the fixture: the prescription's date").toBe("2026-10-22");
    const { container } = sidebar({ nextVisit: AMMA_VISIT, today: "2026-10-20" });
    const card = container.querySelector(".desktop-sidebar__visit")!;
    expect(card.querySelector(".desktop-sidebar__visit-title")!.textContent).toBe(t("bn", "desk.visit.title"));
    expect(card.querySelector(".desktop-sidebar__visit-in")!.textContent).toBe(t("bn", "desk.visit.in", 2));
    expect(card.querySelector(".desktop-sidebar__visit-in")!.textContent, "in Bangla digits").toContain(banglaDigits("2"));
    expect(card.querySelector(".desktop-sidebar__visit-day")!.textContent).toBe(banglaDigits("22"));
    cleanup();
    expect(sidebar({ nextVisit: null }).container.querySelector(".desktop-sidebar__visit")).toBeNull();
    cleanup();
    // On the day itself the card stays, without a count of days.
    const today = sidebar({ nextVisit: AMMA_VISIT, today: "2026-10-22" }).container;
    expect(today.querySelector(".desktop-sidebar__visit")).not.toBeNull();
    expect(today.querySelector(".desktop-sidebar__visit-in")).toBeNull();
  });

  it("theNextVisitIsTheSoonestNotOverdue", () => {
    const item = (due: string, overdue: boolean): FollowUpItemJson => ({ ...AMMA_VISIT, due_date: due, overdue });
    expect(nextVisitOf([])).toBeNull();
    expect(nextVisitOf([item("2026-10-01", true)])).toBeNull();
    expect(nextVisitOf([item("2026-10-01", true), item("2026-10-22", false), item("2026-11-02", false)])!.due_date).toBe("2026-10-22");
    expect(daysUntil("2026-10-20", "2026-10-22")).toBe(2);
    expect(daysUntil("2026-10-31", "2026-11-01")).toBe(1);
    expect(daysUntil("2026-10-22", "2026-10-22")).toBe(0);
  });

  it("accountRowOpensFamily", () => {
    for (const preview of [false, true]) {
      const { container } = sidebar({ preview });
      const row = container.querySelector<HTMLAnchorElement>("a.desktop-sidebar__account")!;
      expect(row.getAttribute("href")).toBe(preview ? "/app/preview/family" : "/app/family");
      expect(row.textContent).toContain(t("bn", "desk.account"));
      expect(row.textContent).toContain(PROTO.family.owner_name);
      expect(row.getAttribute("aria-current")).toBeNull();
      cleanup();
    }
    const settings = sidebar({ current: "settings" }).container.querySelector("a.desktop-sidebar__account")!;
    expect(settings.getAttribute("aria-current")).toBe("page");
  });

  it("aLongNameWraps", () => {
    const LONG = longName.body as MeJson;
    const { container } = sidebar({ me: LONG, active: LONG.patients[0]! });
    const name = container.querySelector(".desktop-sidebar__name")!;
    expect(name.textContent).toBe(LONG.patients[0]!.name);
  });
});

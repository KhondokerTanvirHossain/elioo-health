import { cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { MeJson } from "@/api/types";
import { t } from "@/copy";
import twoPatients from "../../../fixtures/api/me/two-patients.json";
import { PatientChooser } from "./PatientChooser";

/*
 * The chooser (plan W4): with two or more patients and no remembered choice, the page asks whose papers to show and
 * picks none. Each name is a plain link to `?patient=<id>` (a read, so it works without JavaScript); with JavaScript the
 * press also remembers the choice (PUT /app/api/active-patient) before it goes, so the next tab does not ask again.
 */

const TWO: MeJson = twoPatients.body;
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

beforeEach(() => {
  calls = [];
  assign.mockClear();
  vi.stubGlobal("location", { pathname: "/app/preview/shell-test", search: "", assign });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("PatientChooser", () => {
  it("theChooserListsEveryPatientAndPicksNone", () => {
    const { container, getByRole } = render(<PatientChooser lang="bn" patients={TWO.patients} />);
    expect(getByRole("heading", { name: t("bn", "switch.title") })).toBeTruthy();
    expect(container.textContent).toContain(t("bn", "switch.hint"));
    const links = [...container.querySelectorAll("a")];
    expect(links.map((a) => a.getAttribute("href"))).toEqual(TWO.patients.map((p) => `?patient=${p.id}`));
    expect(links.map((a) => a.textContent)).toEqual(
      TWO.patients.map((p) => (p.urgent ? `${p.name}${t("bn", "attn.strip")}` : p.name)),
    );
    // None is chosen for the family: no current mark, no pressed state, nothing selected.
    expect(container.querySelectorAll("[aria-current], [aria-pressed='true'], [aria-selected='true']").length).toBe(0);
  });

  it("anUrgentPatientIsMarkedByShapeAndWord", () => {
    const urgent = TWO.patients.filter((p) => p.urgent);
    expect(urgent.length, "the fixture has an urgent patient and a calm one").toBe(1);
    const { container } = render(<PatientChooser lang="en" patients={TWO.patients} />);
    const rows = [...container.querySelectorAll("a")];
    const marked = rows.filter((a) => a.querySelector(".urgent-mark"));
    expect(marked.map((a) => a.getAttribute("href"))).toEqual([`?patient=${urgent[0]!.id}`]);
    const mark = marked[0]!.querySelector(".urgent-mark")!;
    expect(mark.querySelector("svg[data-shape='octagon']"), "the NOW dot has a shape").not.toBeNull();
    expect(mark.textContent, "and a word, never colour alone").toBe(t("en", "attn.strip"));
  });

  it("aPressRemembersTheChoiceThenGoes", async () => {
    answer(204);
    const { container } = render(<PatientChooser lang="bn" patients={TWO.patients} />);
    const second = TWO.patients[1]!;
    fireEvent.click(container.querySelectorAll("a")[1]!);
    await waitFor(() => expect(assign).toHaveBeenCalledTimes(1));
    expect(calls.map((c) => [c.init.method, c.url, c.init.body])).toEqual([
      ["PUT", "/app/api/active-patient", JSON.stringify({ patient_id: second.id })],
    ]);
    expect(assign).toHaveBeenCalledWith(`/app/preview/shell-test?patient=${second.id}`);
  });
});

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import labsSome from "../../../fixtures/api/health/labs-some.json";
import medicinesLatest from "../../../fixtures/api/health/medicines-latest.json";
import firstPage from "../../../fixtures/api/timeline/first-page.json";
import type { LabsJson } from "@/api/types";
import { DeskSearch, type SearchLabels } from "./DeskSearch";

/*
 * The desktop search's field (plan desktop-parity S4): its three reads are made the first time it is used, once; typing
 * lists the matches as links to their screens; nothing matched says so; Escape clears. apiGet is the browser's call to
 * Java, answered here with Java's fixtures.
 */

const reads: string[] = [];
vi.mock("@/api/browser", () => ({
  apiGet: vi.fn(async (path: string) => {
    reads.push(path);
    if (path.endsWith("/timeline")) return { kind: "ok", status: 200, body: firstPage.body };
    if (path.endsWith("/labs")) return { kind: "ok", status: 200, body: labsSome.body };
    if (path.endsWith("/medicines")) return { kind: "ok", status: 200, body: medicinesLatest.body };
    return { kind: "offline" };
  }),
}));

const LABS = labsSome.body as LabsJson;
const LABELS: SearchLabels = { label: "Search label", placeholder: "Search", results: "Results", none: "None for {0}" };
const WORDS = { lang: "en" as const, months: [], types: { lab_report: "Lab report", prescription: "Prescription", other: "Paper" } };

beforeEach(() => {
  reads.length = 0;
});
afterEach(cleanup);

function search() {
  const view = render(<DeskSearch patientId="p-1" preview={false} words={WORDS} labels={LABELS} />);
  return { view, field: screen.getByRole("searchbox", { name: LABELS.label }) };
}

describe("DeskSearch", () => {
  it("readsNothingUntilUsedThenTheThreeReadsOnce", async () => {
    const { field } = search();
    expect(reads, "a page pays for no search nobody runs").toEqual([]);
    fireEvent.focus(field);
    fireEvent.focus(field);
    await waitFor(() => expect(reads).toHaveLength(3));
    expect(reads.sort()).toEqual(["/app/api/patients/p-1/labs", "/app/api/patients/p-1/medicines", "/app/api/patients/p-1/timeline"]);
  });

  it("aMatchIsALinkToItsScreen", async () => {
    const { view, field } = search();
    fireEvent.focus(field);
    await waitFor(() => expect(reads).toHaveLength(3));
    const s = LABS.series[0]!;
    fireEvent.change(field, { target: { value: s.name } });
    const link = await screen.findByRole("link", { name: new RegExp(s.name) });
    expect(link.getAttribute("href")).toBe(`/app/health?patient=p-1&lab=${s.points[0]!.observation_id}`);
    expect(view.container.textContent).toContain(LABELS.results);
  });

  it("nothingMatchedSaysSoAndEscapeClears", async () => {
    const { view, field } = search();
    fireEvent.focus(field);
    await waitFor(() => expect(reads).toHaveLength(3));
    fireEvent.change(field, { target: { value: "zzz-nothing" } });
    expect(await screen.findByText("None for zzz-nothing")).toBeTruthy();
    fireEvent.keyDown(field, { key: "Escape" });
    expect((field as HTMLInputElement).value).toBe("");
    expect(view.container.querySelector(".desk-search__pop")).toBeNull();
  });
});

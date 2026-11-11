import { describe, expect, it } from "vitest";
import type { LabsJson, MedicinesJson, TimelineJson } from "@/api/types";
import labsSome from "../../../fixtures/api/health/labs-some.json";
import medicinesLatest from "../../../fixtures/api/health/medicines-latest.json";
import firstPage from "../../../fixtures/api/timeline/first-page.json";
import { buildIndex, dayMonth, find, type SearchWords } from "./search-index";

/*
 * The desktop search's index (plan desktop-parity S4; desktop.html's search): the patient's papers (the timeline's first
 * page), every lab value (the labs read) and the latest prescription's medicines, each with the screen it opens. Java's
 * fixtures; nothing judged, only matched.
 */

const TIMELINE = firstPage.body as TimelineJson;
const LABS = labsSome.body as LabsJson;
const MEDS = medicinesLatest.body as MedicinesJson;
const PATIENT = "p-1";
const MONTHS = ["J1", "F2", "M3", "A4", "M5", "J6", "J7", "A8", "S9", "O10", "N11", "D12"];
const WORDS: SearchWords = { lang: "bn", months: MONTHS, types: { lab_report: "LAB", prescription: "RX", other: "OTHER" } };
const hrefOf = (path: string) => `/x${path}`;
const index = buildIndex({ timeline: TIMELINE, labs: LABS, medicines: MEDS }, PATIENT, WORDS, hrefOf);

describe("the desktop search index", () => {
  it("everyPaperValueAndMedicineIsAnEntryWithTheScreenItOpens", () => {
    const papers = index.filter((e) => e.kind === "paper");
    expect(papers.map((e) => e.href)).toEqual(
      TIMELINE.entries.map((x) => `/x/app/home?patient=${PATIENT}&paper=${x.document_id}`),
    );
    const values = index.filter((e) => e.kind === "value");
    expect(values.map((e) => e.href)).toEqual(LABS.series.map((s) => `/x/app/health?patient=${PATIENT}&lab=${s.points[0]!.observation_id}`));
    const meds = index.filter((e) => e.kind === "medicine");
    expect(meds.map((e) => e.title)).toEqual(MEDS.medicines.map((m) => m.name));
    for (const m of meds) expect(m.href).toBe(`/x/app/health?patient=${PATIENT}&show=medicines`);
  });

  it("aValueSaysItsNewestReadingAsWritten", () => {
    const s = LABS.series[0]!;
    const entry = index.find((e) => e.kind === "value" && e.title.startsWith(s.name))!;
    expect(entry.title).toBe(`${s.name} ${s.points[0]!.value} ${s.unit}`);
    expect(entry.sub).toBe(dayMonth(WORDS, s.points[0]!.date));
  });

  it("aPaperIsNamedByItsTypeAndWhereItWasDone", () => {
    const x = TIMELINE.entries.find((e) => e.facility !== null)!;
    const entry = index.find((e) => e.href.endsWith(x.document_id))!;
    expect(entry.title).toBe(WORDS.types[x.document_type ?? "other"] ?? WORDS.types.other);
    expect(entry.sub).toBe(`${dayMonth(WORDS, x.date)} · ${x.facility}`);
  });

  it("findMatchesNameTypeOrPlaceWhateverTheCaseAndKeepsEight", () => {
    const s = LABS.series[0]!;
    expect(find(index, s.name.toUpperCase()).map((e) => e.href)).toContain(`/x/app/health?patient=${PATIENT}&lab=${s.points[0]!.observation_id}`);
    expect(find(index, "rx").every((e) => e.kind === "paper" || e.kind === "medicine")).toBe(true);
    expect(find(index, "   ")).toEqual([]);
    expect(find(index, "no such thing anywhere")).toEqual([]);
    expect(find(index, "").length).toBe(0);
    // At most eight, in the index's order: a patient with many lab papers sees the first eight.
    const many = buildIndex({ timeline: { entries: Array(5).fill(TIMELINE.entries).flat(), next_cursor: null }, labs: null, medicines: null }, PATIENT, WORDS, hrefOf);
    const labs = many.filter((e) => e.title === "LAB");
    expect(labs.length, "the fixture has more than eight lab papers").toBeGreaterThan(8);
    expect(find(many, "lab")).toEqual(labs.slice(0, 8));
  });

  it("aBanglaDateIsTheAppsOwnForm", () => {
    expect(dayMonth(WORDS, "2026-10-14")).toBe("\u09E7\u09EA O10");
    expect(dayMonth({ ...WORDS, lang: "en" }, "2026-10-14")).toBe("2026-10-14");
  });
});

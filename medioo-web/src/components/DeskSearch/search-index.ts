import type { LabsJson, MedicinesJson, TimelineJson } from "@/api/types";

/** The words the index is written with, resolved by the server: no copy ships with the search (it runs in the browser). */
export type SearchWords = {
  lang: "bn" | "en";
  /** The twelve month names as the app writes them (formatMonth), January first. */
  months: string[];
  /** Each document type's name (`document_type` → label); `other` for a paper Java gave none. */
  types: Record<string, string>;
};

/** One thing the search can find: a paper, a lab value or a medicine, and the screen it opens. */
export type SearchEntry = { kind: "paper" | "value" | "medicine"; title: string; sub: string; match: string; href: string };

/** The Bangla digits zero to nine (escaped: no Bangla in source). */
const BN_DIGITS = "\u09E6\u09E7\u09E8\u09E9\u09EA\u09EB\u09EC\u09ED\u09EE\u09EF";

/** A day as the app writes it on bn (the day in Bangla digits, then the month: bangla.ts formatDayMonth); the ISO day on en, as formatDayMonth does. */
export function dayMonth(words: SearchWords, iso: string): string {
  if (words.lang !== "bn") return iso;
  const [, month, day] = iso.split("-").map(Number);
  const digits = String(day).replace(/\d/g, (d) => BN_DIGITS[Number(d)]!);
  return `${digits} ${words.months[(month ?? 1) - 1] ?? ""}`.trim();
}

/**
 * The patient's index (plan desktop-parity S4; desktop.html's search), from three reads the app already makes (no Java
 * search): the timeline's first page (each paper: its type's name, its day and where it was done; opens the
 * talk-and-papers screen on it), the labs (each value: its name and newest reading as written; opens the lab values on
 * it) and the latest prescription (each medicine by its written name; opens the medicines). A read that failed is left
 * out (null), never guessed. `hrefOf` keeps a preview page's links inside the preview.
 */
export function buildIndex(
  data: { timeline: TimelineJson | null; labs: LabsJson | null; medicines: MedicinesJson | null },
  patientId: string,
  words: SearchWords,
  hrefOf: (path: string) => string,
): SearchEntry[] {
  const who = encodeURIComponent(patientId);
  const papers: SearchEntry[] = (data.timeline?.entries ?? []).map((e) => {
    const title = words.types[e.document_type ?? "other"] ?? words.types.other ?? "";
    const day = dayMonth(words, e.date);
    return {
      kind: "paper",
      title,
      sub: e.facility ? `${day} · ${e.facility}` : day,
      match: `${title} ${e.document_type ?? ""} ${e.facility ?? ""}`,
      href: hrefOf(`/app/home?patient=${who}&paper=${encodeURIComponent(e.document_id)}`),
    };
  });
  const values: SearchEntry[] = (data.labs?.series ?? []).flatMap((s) => {
    const p = s.points[0];
    if (p === undefined) return [];
    return [
      {
        kind: "value" as const,
        title: s.unit === null ? `${s.name} ${p.value}` : `${s.name} ${p.value} ${s.unit}`,
        sub: dayMonth(words, p.date),
        match: `${s.name} ${s.canonical_name}`,
        href: hrefOf(`/app/health?patient=${who}&lab=${encodeURIComponent(p.observation_id)}`),
      },
    ];
  });
  const prescription = words.types.prescription ?? "";
  const medicines: SearchEntry[] = (data.medicines?.medicines ?? []).flatMap((m) =>
    m.name === undefined
      ? []
      : [{ kind: "medicine" as const, title: m.name, sub: prescription, match: `${m.name} ${prescription}`, href: hrefOf(`/app/health?patient=${who}&show=medicines`) }],
  );
  return [...papers, ...values, ...medicines];
}

/** The entries whose words hold `query` (case ignored), at most eight, in the index's order; nothing for a blank query. */
export function find(index: readonly SearchEntry[], query: string): SearchEntry[] {
  const q = query.trim().toLocaleLowerCase();
  if (q === "") return [];
  return index.filter((e) => e.match.toLocaleLowerCase().includes(q)).slice(0, 8);
}

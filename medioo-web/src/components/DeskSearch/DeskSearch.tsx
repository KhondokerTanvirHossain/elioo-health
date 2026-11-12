"use client";

import { useEffect, useId, useRef, useState, type KeyboardEvent } from "react";
import { apiGet } from "@/api/browser";
import type { LabsJson, MedicinesJson, TimelineJson } from "@/api/types";
import { href } from "@/routing/paths";
import { buildIndex, find, type SearchEntry, type SearchWords } from "./search-index";

/** The field's own words, resolved by the server (desk.search.*): this island imports no copy. */
export type SearchLabels = { label: string; placeholder: string; results: string; none: string };

/**
 * The desktop search (plan desktop-parity S4; desktop.html's search, `#q`, `.pop`): a field at the head of the sidebar,
 * focused by ⌘K (Ctrl+K), over the patient in view's papers, lab values and latest medicines (search-index.ts). Its
 * three reads are the app's own (the timeline's first page, the labs, the medicines), made in the browser the first
 * time the field is used, so a page never pays for a search nobody runs. Typing lists at most eight results, each a link
 * to the screen it opens; Enter opens the first, Escape clears. A read that fails leaves its part out, never guessed.
 * The field appears only once JavaScript runs (DR-42 item 1), as the conversation's composer does.
 */
export function DeskSearch({ patientId, preview, words, labels }: { patientId: string; preview: boolean; words: SearchWords; labels: SearchLabels }) {
  const [live, setLive] = useState(false);
  const [query, setQuery] = useState("");
  const [index, setIndex] = useState<SearchEntry[] | null>(null);
  const loading = useRef(false);
  const field = useRef<HTMLInputElement>(null);
  const listId = useId();

  useEffect(() => setLive(true), []);

  useEffect(() => {
    const onKey = (e: globalThis.KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        field.current?.focus();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const load = async () => {
    if (loading.current) return;
    loading.current = true;
    const base = `/app/api/patients/${encodeURIComponent(patientId)}`;
    const body = async <T,>(path: string) => {
      const r = await apiGet<T>(path);
      return r.kind === "ok" ? r.body : null;
    };
    const [timeline, labs, medicines] = await Promise.all([
      body<TimelineJson>(`${base}/timeline`),
      body<LabsJson>(`${base}/labs`),
      body<MedicinesJson>(`${base}/medicines`),
    ]);
    setIndex(buildIndex({ timeline, labs, medicines }, patientId, words, (p) => href(p, preview)));
  };

  const hits = index === null ? [] : find(index, query);
  const asked = query.trim() !== "" && index !== null;

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter" && hits[0]) {
      e.preventDefault();
      setQuery("");
      // The first result, opened as its link would be (a plain navigation, with or without a soft router).
      location.assign(hits[0].href);
    } else if (e.key === "Escape") {
      setQuery("");
      field.current?.blur();
    }
  };

  // Without JavaScript there is no field (DR-42 item 1: no control that does nothing); its place is held, so the
  // sidebar does not move when the island comes alive.
  if (!live) return <div className="desk-search" aria-hidden="true" data-search-placeholder="" />;

  return (
    <div className="desk-search">
      <svg className="desk-search__icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path d="M10.5 4.5a6 6 0 1 1 0 12 6 6 0 0 1 0-12zM15 15l5 5" />
      </svg>
      <input
        ref={field}
        className="desk-search__field"
        type="search"
        aria-label={labels.label}
        placeholder={labels.placeholder}
        autoComplete="off"
        value={query}
        aria-controls={asked ? listId : undefined}
        onFocus={() => void load()}
        onChange={(e) => setQuery(e.currentTarget.value)}
        onKeyDown={onKeyDown}
      />
      <kbd className="desk-search__kbd" aria-hidden="true">
        ⌘K
      </kbd>
      {asked && (
        <div className="desk-search__pop" id={listId} role="region" aria-label={labels.results}>
          {hits.length === 0 ? (
            <p className="desk-search__none" role="status">
              {labels.none.replace("{0}", query.trim())}
            </p>
          ) : (
            <>
              <p className="desk-search__head" role="status">
                {labels.results}
              </p>
              <ul className="desk-search__list">
                {hits.map((h) => (
                  <li key={h.href + h.title}>
                    <a className="desk-search__hit" href={h.href} data-kind={h.kind} onClick={() => setQuery("")}>
                      <span className="desk-search__title">{h.title}</span>
                      <small className="desk-search__sub">{h.sub}</small>
                    </a>
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      )}
    </div>
  );
}

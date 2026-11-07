import "server-only";
import { notFound } from "next/navigation";
import { javaGet, signedIn } from "@/api/java";
import type { MePatient, TimelineJson } from "@/api/types";
import { t, type Lang } from "@/copy";
import { MonthList } from "@/components/MonthList/MonthList";
import { href } from "@/routing/paths";

/*
 * What the papers page and the desktop calendar (desk.tsx) share: the address a render answers, its reads, the type
 * filter with Java's month list, and the chronic-flags ask.
 */

/** The filters Java knows (TimelineFilter.fromParam): anything else is every paper, as Java reads it. */
const FILTERS = [
  { type: null, key: "timeline.filter.all" },
  { type: "lab", key: "timeline.filter.lab" },
  { type: "rx", key: "timeline.filter.rx" },
] as const;
export type Filter = (typeof FILTERS)[number]["type"];


/** What this render asks of Java, from the address the browser asked for (requestContext().here). */
export function asked(here: string): { type: Filter; cursor: string | null; month: string | null; day: string | null; list: boolean } {
  const q = new URL(here, "http://medioo.invalid").searchParams;
  const type = q.get("type");
  return {
    type: type === "lab" || type === "rx" ? type : null,
    cursor: q.get("cursor"),
    month: q.get("month"),
    // The desktop calendar's (plan desktop-parity C1): the chosen day and the list view. The phone's page reads neither.
    day: q.get("day"),
    list: q.get("view") === "list",
  };
}

/** A query string from the parameters that are set, in this order. */
export const query = (params: Record<string, string | null>) => {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== null && v !== "") q.set(k, v);
  const s = q.toString();
  return s === "" ? "" : `?${s}`;
};

/** A signed-in read of this page: a 400 (a malformed month or a cursor Java never minted) is the not-found page. */
export async function read<T>(path: string, here: string): Promise<T> {
  const answer = await javaGet<T>(path);
  if (answer.status === 400) notFound();
  return signedIn(answer, here);
}

/**
 * The type filter, Java's month list and its "more" link (the phone's page under its calendar; the desktop calendar's
 * list view, `desk.cal.list`). `at` is the page's address for a filter and a cursor, kept in the view it is drawn in.
 */
export function PaperList({
  lang,
  preview,
  timeline,
  type,
  at,
}: {
  lang: Lang;
  preview: boolean;
  timeline: TimelineJson;
  type: Filter;
  at: (q: { type: Filter; cursor?: string | null }) => string;
}) {
  return (
    <>
      <nav className="papers__filters" aria-label={t(lang, "timeline.filter.label")}>
        <ul className="papers__filter-list">
          {FILTERS.map((f) => (
            <li key={f.key}>
              <a className="papers__filter" href={at({ type: f.type })} aria-current={f.type === type ? "true" : undefined}>
                {f.type === type && (
                  <svg className="papers__check" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                    <path d="M5 12.5l4.5 4.5L19 7.5" />
                  </svg>
                )}
                {t(lang, f.key)}
              </a>
            </li>
          ))}
        </ul>
      </nav>
      {timeline.entries.length === 0 && (
        // A filter that matches nothing says so; an empty later page under "all" says only that there are no more.
        <p className="papers__note">{t(lang, type === null ? "timeline.end" : "timeline.filter.none")}</p>
      )}
      <MonthList entries={timeline.entries} lang={lang} preview={preview} />
      {timeline.next_cursor !== null && (
        <a className="papers__more" href={at({ type, cursor: timeline.next_cursor })}>
          {t(lang, "timeline.more")}
        </a>
      )}
    </>
  );
}

/**
 * BMX-8 (PO option 1), as Java's papersBody asks it: a patient with no chronic flag stored is asked once, here, under the
 * list, where the family already is. Nothing infers a flag, and the nudge engine's silence rule can never fire until
 * someone answers. The answer is given on the family tab (Java's /app/family for this patient). Java states the fact
 * (`chronic_flags_set` on GET /app/api/me); this asks exactly when it is false, for any viewer, as Java does.
 */
export function FlagsAsk({ lang, patient, preview }: { lang: Lang; patient: MePatient; preview: boolean }) {
  return (
    <section className="papers__ask" aria-labelledby="papers-ask">
      <h2 className="papers__h2" id="papers-ask">
        {t(lang, "timeline.flags.ask")}
      </h2>
      <p className="papers__foot">{t(lang, "timeline.flags.why")}</p>
      <a className="papers__ask-link" href={href(`/app/family${query({ patient: patient.id })}`, preview)}>
        {t(lang, "timeline.flags.cta")}
      </a>
    </section>
  );
}

import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { javaGet, signedIn } from "@/api/java";
import { requestContext } from "@/api/request";
import type { AttentionJson, AttentionUrgentJson, CalendarJson, FollowUpsJson, MePatient, TimelineJson } from "@/api/types";
import { formatDate, formatMonthYear, t, type Lang } from "@/copy";
import { DueCards } from "@/components/DueCard/DueCard";
import { MonthCalendar } from "@/components/MonthCalendar/MonthCalendar";
import { MonthList, typeLabel } from "@/components/MonthList/MonthList";
import { UrgencyTag } from "@/components/safety/UrgencyTag/UrgencyTag";
import { href } from "@/routing/paths";
import { signedInPage, type ShellContext } from "../shell";
import { shift } from "./months";
import { IN_FLIGHT, RELOAD_SECONDS } from "@/api/reading-check";
import { QuietCheck } from "./QuietCheck";
import "./papers.css";

/** The filters Java knows (TimelineFilter.fromParam): anything else is every paper, as Java reads it. */
const FILTERS = [
  { type: null, key: "timeline.filter.all" },
  { type: "lab", key: "timeline.filter.lab" },
  { type: "rx", key: "timeline.filter.rx" },
] as const;
type Filter = (typeof FILTERS)[number]["type"];


/** What this render asks of Java, from the address the browser asked for (requestContext().here). */
function asked(here: string): { type: Filter; cursor: string | null; month: string | null } {
  const q = new URL(here, "http://medioo.invalid").searchParams;
  const type = q.get("type");
  return { type: type === "lab" || type === "rx" ? type : null, cursor: q.get("cursor"), month: q.get("month") };
}

/** A query string from the parameters that are set, in this order. */
const query = (params: Record<string, string | null>) => {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== null && v !== "") q.set(k, v);
  const s = q.toString();
  return s === "" ? "" : `?${s}`;
};

/** A signed-in read of this page: a 400 (a malformed month or a cursor Java never minted) is the not-found page. */
async function read<T>(path: string, here: string): Promise<T> {
  const answer = await javaGet<T>(path);
  if (answer.status === 400) notFound();
  return signedIn(answer, here);
}

function Chevron({ back }: { back?: boolean }) {
  return (
    <svg className="papers__chevron" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d={back ? "M14.5 6l-6 6 6 6" : "M9.5 6l6 6-6 6"} />
    </svg>
  );
}

/**
 * The day an urgent paper is filed under, in full (Java's strip: `copy.date(lang, u.date())`): the strip has no month
 * header to hold the year, so a paper from an earlier year never reads as this year's. An upload day says so, and a
 * printed date the model was unsure of is marked, both from Java's flags.
 */
function stripDay(lang: Lang, u: AttentionUrgentJson): string {
  const day = formatDate(lang, u.date);
  if (u.date_is_upload_day) return `${day} ${t(lang, "timeline.uploaddate")}`;
  return u.date_uncertain ? t(lang, "trend.date.uncertain", day) : day;
}

/** The attention strip (`attn.strip`): the stack's urgent papers (stored NOW and THIS_WEEK only), each a link to its paper; none is nothing. */
function Strip({ items, lang, preview }: { items: AttentionUrgentJson[]; lang: Lang; preview: boolean }) {
  if (items.length === 0) return null;
  return (
    <section className="papers__strip" aria-labelledby="papers-strip">
      <h2 className="papers__h2" id="papers-strip">
        {t(lang, "attn.strip")}
      </h2>
      <ul className="papers__strip-list">
        {items.map((u) => (
          <li key={u.document_id} className="papers__strip-item">
            <a className="papers__urgent" href={href(`/app/documents/${u.document_id}`, preview)}>
              <span className="papers__urgent-what">{`${typeLabel(lang, u.document_type)} · ${stripDay(lang, u)}`}</span>
              <UrgencyTag level={u.level} lang={lang} />
            </a>
          </li>
        ))}
      </ul>
    </section>
  );
}

/**
 * The papers page (`tab.papers`) for the patient in view (plan W8; phone.html scene `papers`, Java's AppTabs.papersBody): the title and whose
 * papers they are, the owner's one way to send a paper, the attention strip, the doctor's dates, the wall calendar of
 * the month Java answered with its two neighbouring months as links, the type filter, Java's month list and its "more"
 * link, and the chronic-flags ask under it all when none is stored. Every control is a plain link, so the page reads and
 * pages without JavaScript; while a listed paper is still being read it reloads itself without JavaScript, as Java's
 * does, and with JavaScript checks quietly and re-renders once the paper is read (PO ruling P6). One render is one
 * timeline read (the one TIMELINE_VIEW, S14) and one calendar read (no audit row), whatever link brought the family here.
 */
async function papers({ lang, preview, patient }: ShellContext, here: string) {
  const title = (
    <h1 className="papers__title" id="papers-title">
      {t(lang, "timeline.docs")}
    </h1>
  );
  if (patient === null) {
    return (
      <div className="papers">
        <header className="papers__head">
          <div className="papers__heading">{title}</div>
        </header>
        <p className="papers__note">{t(lang, "patients.none")}</p>
      </div>
    );
  }
  const { type, cursor, month } = asked(here);
  const base = `/app/api/patients/${patient.id}`;
  const [timeline, calendar, follows, attention] = await Promise.all([
    read<TimelineJson>(`${base}/timeline${query({ type, cursor })}`, here),
    read<CalendarJson>(`${base}/calendar${query({ month })}`, here),
    read<FollowUpsJson>(`${base}/follow-ups`, here),
    read<AttentionJson>(`${base}/attention`, here),
  ]);
  const page = (params: Record<string, string | null>) => href(`/app/timeline${query({ patient: patient.id, ...params })}`, preview);
  const nothingAtAll = timeline.entries.length === 0 && type === null && cursor === null;
  const add = `/app/add${query({ patient: patient.id, back: page({}) })}`;
  const urgent = attention.items.filter((i): i is AttentionUrgentJson => i.kind === "urgent");
  const before = shift(calendar.month, -1);
  const after = shift(calendar.month, 1);
  // While a listed paper is still being read, a paper sent from the add sheet (which comes back here) moves on by itself.
  // Without JavaScript the page reloads every 5 s, as Java's does: the refresh sits in a <noscript>, which only a
  // browser that runs no script parses as markup. With JavaScript it never reloads (PO ruling P6, WCAG F41): QuietCheck
  // asks each paper's status, which records nothing, and re-renders the page's data once one has been read. Either way
  // each render is one more timeline read and so one more TIMELINE_VIEW (S14 counts one per render); the quiet check
  // makes one render per paper read, where the reload made one every 5 s.
  const reading = timeline.entries.filter((e) => IN_FLIGHT.has(e.status)).map((e) => e.document_id);

  return (
    <div className="papers">
      {reading.length > 0 && (
        <noscript className="papers__reload">
          <meta httpEquiv="refresh" content={String(RELOAD_SECONDS)} />
        </noscript>
      )}
      <header className="papers__head">
        <div className="papers__heading">
          {title}
          <p className="papers__sub">{t(lang, "timeline.sub", patient.name_possessive)}</p>
        </div>
        {patient.owner && !nothingAtAll && <AddLink href={add} label={t(lang, "timeline.add")} />}
      </header>
      {/* Under the heading, so the link it offers after the cap reads after the page's title, never before it. */}
      <QuietCheck
        reading={reading}
        again={page({ type, month, cursor })}
        words={{
          settled: { DONE: t(lang, "status.DONE"), NEEDS_RETAKE: t(lang, "status.NEEDS_RETAKE"), FAILED: t(lang, "status.FAILED") },
          again: t(lang, "attn.reading.action"),
        }}
      />
      {nothingAtAll ? (
        <Empty lang={lang} patient={patient} add={add} />
      ) : (
        <>
          <Strip items={urgent} lang={lang} preview={preview} />
          <DueCards items={follows.items} lang={lang} />
          <section className="papers__calendar">
            <div className="papers__months">
              <a className="papers__month" href={page({ type, month: before })}>
                <Chevron back />
                <span>{formatMonthYear(lang, `${before}-01`)}</span>
              </a>
              <a className="papers__month papers__month--next" href={page({ type, month: after })}>
                <span>{formatMonthYear(lang, `${after}-01`)}</span>
                <Chevron />
              </a>
            </div>
            <MonthCalendar
              month={calendar.month}
              days={calendar.days}
              caption={`${patient.name} · ${formatMonthYear(lang, `${calendar.month}-01`)}`}
              lang={lang}
            />
          </section>
          <nav className="papers__filters" aria-label={t(lang, "timeline.filter.label")}>
            <ul className="papers__filter-list">
              {FILTERS.map((f) => (
                <li key={f.key}>
                  <a
                    className="papers__filter"
                    href={page({ type: f.type, month })}
                    aria-current={f.type === type ? "true" : undefined}
                  >
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
            <a className="papers__more" href={page({ type, month, cursor: timeline.next_cursor })}>
              {t(lang, "timeline.more")}
            </a>
          )}
        </>
      )}
      {!patient.chronic_flags_set && <FlagsAsk lang={lang} patient={patient} preview={preview} />}
    </div>
  );
}

/** The owner's way to send a paper for this patient: Java's add sheet, which comes back here (A7's one upload entry). */
function AddLink({ href: to, label }: { href: string; label: string }) {
  return (
    <a className="papers__add" href={to} data-primary="">
      <svg className="papers__camera" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path d="M4 8h3l1.5-2.5h7L17 8h3v11H4zM12 16.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z" />
      </svg>
      <span>{label}</span>
    </a>
  );
}

/** No paper at all (Java's s16 empty panel): whose file, why send one, and, for the owner only, the camera. */
function Empty({ lang, patient, add }: { lang: Lang; patient: MePatient; add: string }) {
  return (
    <section className="papers__empty" aria-labelledby="papers-empty">
      <h2 className="papers__h2" id="papers-empty">
        {t(lang, "timeline.empty.title", patient.name_possessive)}
      </h2>
      <p className="papers__note">{t(lang, "timeline.empty.body")}</p>
      {patient.owner && <AddLink href={add} label={t(lang, "chat.empty.action")} />}
      <p className="papers__foot">{t(lang, "timeline.empty.foot")}</p>
    </section>
  );
}

/**
 * BMX-8 (PO option 1), as Java's papersBody asks it: a patient with no chronic flag stored is asked once, here, under the
 * list, where the family already is. Nothing infers a flag, and the nudge engine's silence rule can never fire until
 * someone answers. The answer is given on the family tab (Java's /app/family for this patient). Java states the fact
 * (`chronic_flags_set` on GET /app/api/me); this asks exactly when it is false, for any viewer, as Java does.
 */
function FlagsAsk({ lang, patient, preview }: { lang: Lang; patient: MePatient; preview: boolean }) {
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

/** The tab's own title, as Java's head names it (`tab.papers — app.title`), so this page is told apart from the others. */
export async function generateMetadata(): Promise<Metadata> {
  const { lang } = await requestContext();
  return { title: `${t(lang, "tab.papers")} — ${t(lang, "app.title")}` };
}

export default async function PapersPage() {
  const { here } = await requestContext();
  return signedInPage({ tab: "papers", main: (ctx) => papers(ctx, here) });
}

import type { TimelineCountsJson, TimelineEntryJson } from "@/api/types";
import { formatDayMonth, formatMonthYear, t, type CopyKey, type Lang } from "@/copy";
import { UrgencyTag } from "@/components/safety/UrgencyTag/UrgencyTag";
import { href } from "@/routing/paths";
import "./MonthList.css";

/** The paper types the bundle names (Java's TimelineView.typeLabel); any other, or none, is "a paper". */
const TYPE_KEY: Record<string, CopyKey> = {
  prescription: "type.prescription",
  lab_report: "type.lab_report",
  discharge_summary: "type.discharge_summary",
  imaging_report: "type.imaging_report",
};

/** A paper's type as the bundle names it, else the bundle's word for a paper (C4: the strip and the list say it alike). */
export function typeLabel(lang: Lang, type: string | null): string {
  const key = type === null ? undefined : TYPE_KEY[type];
  return t(lang, key ?? "timeline.paper");
}

/**
 * The day a paper is filed under, as Java filed it (D2): the day and month (the month header holds the year), an upload
 * day said as such (`timeline.uploaddate`), never presented as the visit's, and a printed date the model was unsure of
 * marked (`trend.date.uncertain`). Both flags are Java's; nothing here compares dates.
 */
export function filedDay(lang: Lang, e: { date: string; date_is_upload_day: boolean; date_uncertain: boolean }): string {
  const day = formatDayMonth(lang, e.date);
  if (e.date_is_upload_day) return `${day} ${t(lang, "timeline.uploaddate")}`;
  return e.date_uncertain ? t(lang, "trend.date.uncertain", day) : day;
}

/**
 * The one line under a read paper (Java's TimelineSummary, from the stored counts, never a model's summary): a lab or
 * imaging paper counts its values and, only when there are any, how many are outside; a prescription its medicines;
 * any other paper what it has; a paper filed under its upload day adds that its date could not be read
 * (`timeline.sum.nodate`, Java's `dateIsFallback()`, sent as `date_is_upload_day`). The parts are joined with " · ".
 */
function summary(lang: Lang, type: string | null, c: TimelineCountsJson, uploadDay: boolean): string {
  const values = () => t(lang, "timeline.sum.values", c.values) + (c.outside > 0 ? `, ${t(lang, "timeline.sum.outside", c.outside)}` : "");
  const medicines = () => t(lang, "timeline.sum.medicines", c.medicines);
  const parts =
    type === "lab_report" || type === "imaging_report"
      ? [values()]
      : type === "prescription"
        ? [medicines()]
        : [c.values > 0 ? values() : null, c.medicines > 0 ? medicines() : null];
  if (uploadDay) parts.push(t(lang, "timeline.sum.nodate"));
  return parts.filter(Boolean).join(" · ");
}

/** Where a paper that is not read is (Java's TimelineView.state): its title and its state line. */
const NOT_READ: Record<string, { title: CopyKey; state: CopyKey }> = {
  NEEDS_RETAKE: { title: "timeline.paper", state: "timeline.retake" },
  FAILED: { title: "timeline.paper", state: "timeline.failed" },
};
const READING = { title: "timeline.reading.title", state: "timeline.reading" } as const satisfies { title: CopyKey; state: CopyKey };

function Row({ e, lang, preview }: { e: TimelineEntryJson; lang: Lang; preview: boolean }) {
  const done = e.status === "DONE";
  const notRead = done ? null : (NOT_READ[e.status] ?? READING);
  const line = done ? summary(lang, e.document_type, e.counts, e.date_is_upload_day) : "";
  return (
    <a className="month-list__row" href={href(`/app/documents/${e.document_id}`, preview)}>
      <svg className="month-list__thumb" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path d="M6.5 3.5h8l3 3v14h-11zM9 10h6M9 13.5h6M9 17h4" />
      </svg>
      <span className="month-list__body">
        <span className="month-list__title">{notRead ? t(lang, notRead.title) : typeLabel(lang, e.document_type)}</span>
        <span className="month-list__meta">
          <span className="month-list__date">{filedDay(lang, e)}</span>
          {e.facility && <span className="month-list__facility">{e.facility}</span>}
        </span>
        {notRead && <span className="month-list__state">{t(lang, notRead.state)}</span>}
        {e.status === "NEEDS_RETAKE" && e.retake_reason_key && (
          <span className="month-list__line">{t(lang, e.retake_reason_key as CopyKey)}</span>
        )}
        {line && <span className="month-list__summary">{line}</span>}
        {done && e.counts.unverified > 0 && <span className="month-list__line">{t(lang, "timeline.unverified", e.counts.unverified)}</span>}
        {done && (e.chip !== null || e.review_pending) && (
          <span className="month-list__tags">
            {/* The stored level first; the review state beside it, never instead of it. */}
            <UrgencyTag level={e.chip} lang={lang} />
            {e.review_pending && <span className="month-list__review">{t(lang, "timeline.review.pending")}</span>}
          </span>
        )}
      </span>
      <svg className="month-list__chevron" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path d="M9.5 6l6 6-6 6" />
      </svg>
    </a>
  );
}

/**
 * The papers page's list (plan W8; phone.html scene `papers`, Java's TimelineView): Java's entries in Java's order, a
 * section per run of the same `month`, the key Java sent (TimelineJson: `YearMonth.from(date)`, D2's filing day), never
 * a month parsed out of `date` here, headed by that month. Each paper is one link to it, with nothing interactive
 * inside: its type, the day it is filed under, the facility as printed, the counts line and the stored level (NOW and
 * THIS_WEEK only) for a read paper, and where it is for one that is not. No picture per row, so a page of twenty costs
 * no image bytes.
 */
export function MonthList({ entries, lang, preview }: { entries: TimelineEntryJson[]; lang: Lang; preview: boolean }) {
  const runs: { month: string; entries: TimelineEntryJson[] }[] = [];
  for (const e of entries) {
    const last = runs.at(-1);
    if (last && last.month === e.month) last.entries.push(e);
    else runs.push({ month: e.month, entries: [e] });
  }
  return (
    <div className="month-list">
      {runs.map((run, i) => (
        <section key={`${run.month}-${i}`} className="month-list__month" data-month={run.month}>
          <h2 className="month-list__heading">{formatMonthYear(lang, `${run.month}-01`)}</h2>
          <ol className="month-list__items">
            {run.entries.map((e) => (
              <li key={e.document_id} className="month-list__item">
                <Row e={e} lang={lang} preview={preview} />
              </li>
            ))}
          </ol>
        </section>
      ))}
    </div>
  );
}

import Link from "next/link";
import type { AnchorHTMLAttributes, ReactNode } from "react";
import type { CalendarDayJson, CalendarFollowUpJson, CalendarJson, CalendarPaperJson } from "@/api/types";
import { banglaDigits, formatDayMonth, formatMonth, formatMonthYear, t, type CopyKey, type Lang } from "@/copy";
import { WEEK, dayStatus, dayUrgency, monthDays } from "@/components/MonthCalendar/MonthCalendar";
import { filedDay, paperWords } from "@/components/MonthList/MonthList";
import { ChipShape } from "@/components/StatusChip/StatusChip";
import { UrgencyTag } from "@/components/safety/UrgencyTag/UrgencyTag";
import { DeskCalendarStyles } from "./desk-load";

export type DeskCalendarView = "month" | "list";

export type DeskCalendarProps = {
  lang: Lang;
  view: DeskCalendarView;
  /** The month Java answered (GET …/calendar), its days the ones holding a paper or a doctor's date. */
  calendar: CalendarJson;
  /** Today on the patient's calendar (Dhaka), ISO. */
  today: string;
  /** The day the address asks for (`?day=`), as given: anything not a day of this month is not chosen. */
  asked: string | null;
  /** The months either side of `calendar.month`, `YYYY-MM`. */
  before: string;
  after: string;
  /** This screen's address for a month, a day and a view (the page's own, kept inside the preview). */
  at: (q: { month?: string | null; day?: string | null; view: DeskCalendarView }) => string;
  /** Next answers this screen's links in place (a soft navigation); else they are plain links. */
  soft: boolean;
  /** The talk-and-papers screen showing a paper. */
  open: (documentId: string) => string;
  /** The list view's body (the page's filters, list and more), drawn when `view` is "list". */
  list?: ReactNode;
};

/** The events a day shows before it counts the rest (desktop.html's cells hold about three at their smallest). */
const SHOWN = 3;

/**
 * The day the panel shows: the asked day when it is a day of this month; else today when today is in it; else the
 * month's latest day holding something; else its first day.
 */
export function chosenDay(calendar: CalendarJson, today: string, asked: string | null): string {
  const { dates } = monthDays(calendar.month);
  if (asked !== null && dates.includes(asked)) return asked;
  if (dates.includes(today)) return today;
  return calendar.days.at(-1)?.date ?? dates[0]!;
}

const digits = (lang: Lang, n: number) => (lang === "bn" ? banglaDigits(String(n)) : String(n));

/** A link of this screen: in place where Next answers it (`soft`), else a plain one. Never prefetched: one read per visit. */
function Go({ soft, href, children, ...rest }: { soft: boolean; href: string } & Omit<AnchorHTMLAttributes<HTMLAnchorElement>, "href">) {
  return soft ? (
    <Link href={href} prefetch={false} scroll={false} {...rest}>
      {children}
    </Link>
  ) : (
    <a href={href} {...rest}>
      {children}
    </a>
  );
}

function Chevron({ back }: { back?: boolean }) {
  return (
    <svg className="desk-cal__icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d={back ? "M14.5 6l-6 6 6 6" : "M9.5 6l6 6-6 6"} />
    </svg>
  );
}

type Event = { kind: "paper"; paper: CalendarPaperJson } | { kind: "visit"; follow: CalendarFollowUpJson };

/** What a day shows, most pressing first: NOW papers, THIS_WEEK papers, the doctor's dates, then the rest in Java's order. */
function eventsOf(day: CalendarDayJson | undefined): Event[] {
  if (day === undefined) return [];
  const papers = (chip: string | null) => day.papers.filter((p) => p.chip === chip).map((paper): Event => ({ kind: "paper", paper }));
  const rest = day.papers.filter((p) => p.chip !== "now" && p.chip !== "this_week").map((paper): Event => ({ kind: "paper", paper }));
  return [...papers("now"), ...papers("this_week"), ...day.follow_ups.map((follow): Event => ({ kind: "visit", follow })), ...rest];
}

/** A paper on its day: its name, and its urgency as the shape the paper's band draws (never the colour alone). */
function PaperEvent({ lang, paper }: { lang: Lang; paper: CalendarPaperJson }) {
  const level = paper.chip === "now" ? "now" : paper.chip === "this_week" ? "week" : null;
  return (
    <span className={level === null ? "desk-cal__ev" : `desk-cal__ev desk-cal__ev--${level}`}>
      {level === null ? (
        <svg className="desk-cal__ev-mark desk-cal__ev-mark--dot" viewBox="0 0 24 24" aria-hidden="true" focusable="false" data-shape="dot">
          <circle cx="12" cy="12" r="5" />
        </svg>
      ) : (
        <ChipShape shape={level === "now" ? "octagon" : "calendar-week"} className="desk-cal__ev-mark desk-cal__ev-mark--shape" />
      )}
      {paperWords(lang, paper).title}
    </span>
  );
}

/**
 * One day of the month: a link to that day (the panel then shows it), its numeral, and what it holds. Its name is the
 * phone calendar's sentence for the day (date, papers, doctor's dates, urgency), text inside the link that is read and
 * not drawn (as MonthCalendar's cell holds it); what it draws is hidden from a screen reader. Never an aria-label: the
 * name would then leave out the words drawn on the day (WCAG 2.5.3, axe label-content-name-mismatch).
 */
function Day(props: { lang: Lang; date: string; day: CalendarDayJson | undefined; fri: boolean; today: boolean; chosen: boolean; href: string; soft: boolean }) {
  const { lang, date, day, fri, today, chosen, href, soft } = props;
  const events = eventsOf(day);
  const shown = events.slice(0, SHOWN);
  const ring = (day?.follow_ups.length ?? 0) > 0;
  const className = [
    "desk-cal__day",
    fri && "desk-cal__day--fri",
    today && "desk-cal__day--today",
    chosen && "desk-cal__day--chosen",
    ring && "desk-cal__day--ring",
  ]
    .filter(Boolean)
    .join(" ");
  return (
    <Go
      soft={soft}
      href={href}
      className={className}
      data-date={date}
      data-urgency={dayUrgency(day) ?? undefined}
      aria-current={chosen ? "true" : today ? "date" : undefined}
    >
      <span className="desk-cal__status">{dayStatus(lang, date, day)}</span>
      <span className="desk-cal__n" aria-hidden="true">
        {digits(lang, Number(date.slice(8)))}
      </span>
      <span className="desk-cal__events" aria-hidden="true">
        {shown.map((e, i) =>
          e.kind === "paper" ? (
            <PaperEvent key={`p-${e.paper.document_id}`} lang={lang} paper={e.paper} />
          ) : (
            <span key={`v-${i}`} className="desk-cal__ev desk-cal__ev--visit">
              {t(lang, "desk.cal.visit")}
            </span>
          ),
        )}
        {events.length > shown.length && <span className="desk-cal__more">{`+${digits(lang, events.length - shown.length)}`}</span>}
      </span>
    </Go>
  );
}

/** A paper of the chosen day: its first page, its name, where it was done, its level, its line, and the way to it. */
function PaperCard({ lang, paper, open }: { lang: Lang; paper: CalendarPaperJson; open: string }) {
  const words = paperWords(lang, paper);
  const done = paper.status === "DONE";
  return (
    <article className="desk-cal__card" data-document={paper.document_id}>
      <div className="desk-cal__card-row">
        {/* eslint-disable-next-line @next/next/no-img-element -- next/image is banned (Global Constraints); Java serves the page */}
        <img className="desk-cal__mini" src={paper.page_one.src} alt="" decoding="async" loading="lazy" />
        <div className="desk-cal__card-text">
          <h3 className="desk-cal__card-title">{words.title}</h3>
          {paper.facility && <small className="desk-cal__card-sub">{paper.facility}</small>}
          {(paper.date_is_upload_day || paper.date_uncertain) && <small className="desk-cal__card-sub">{filedDay(lang, paper)}</small>}
          {done && (paper.chip !== null || paper.review_pending) && (
            <span className="desk-cal__tags">
              <UrgencyTag level={paper.chip} lang={lang} />
              {paper.review_pending && <span className="desk-cal__review">{t(lang, "timeline.review.pending")}</span>}
            </span>
          )}
        </div>
      </div>
      {words.state && <p className="desk-cal__line">{words.state}</p>}
      {words.retake && <p className="desk-cal__line">{words.retake}</p>}
      {words.line && <p className="desk-cal__line desk-cal__line--strong">{words.line}</p>}
      {words.unverified && <p className="desk-cal__line">{words.unverified}</p>}
      <div className="desk-cal__acts">
        <a className="desk-cal__open" href={open}>
          {t(lang, "desk.cal.open")}
        </a>
      </div>
    </article>
  );
}

/**
 * A doctor's date on the chosen day: the day as a small calendar, what it is, the doctor's words quoted under it and as
 * written (Java's crop), and the way to the paper they are on. desktop.html adds that a WhatsApp reminder comes the evening
 * before; the nudge engine sends one two days before, gated and capped (NudgeRules.follow_up_due), so that promise is
 * not made here.
 */
function VisitCard({ lang, date, follow, open }: { lang: Lang; date: string; follow: CalendarFollowUpJson; open: string }) {
  return (
    <article className="desk-cal__card desk-cal__card--visit" data-document={follow.document_id}>
      <div className="desk-cal__card-row">
        <span className="desk-cal__calchip" aria-hidden="true">
          <small className="desk-cal__calchip-month">{formatMonth(lang, date)}</small>
          <span className="desk-cal__calchip-day">{digits(lang, Number(date.slice(8)))}</span>
        </span>
        <div className="desk-cal__card-text">
          <h3 className="desk-cal__card-title">{t(lang, "desk.cal.visit.title")}</h3>
          <small className="desk-cal__card-sub">{`\u201C${follow.instruction}\u201D`}</small>
        </div>
      </div>
      {/* The words as the doctor wrote them; the quoted line above says them, so the picture is not read out again. */}
      {/* eslint-disable-next-line @next/next/no-img-element -- next/image is banned (Global Constraints); Java serves the crop */}
      <img className="desk-cal__crop" src={follow.crop.src} alt="" decoding="async" loading="lazy" />
      <div className="desk-cal__acts">
        <a className="desk-cal__open desk-cal__open--quiet" href={open}>
          {t(lang, "desk.cal.paper")}
        </a>
      </div>
    </article>
  );
}

/** A cell's class: a day, or a blank before the 1st (drawn quieter) or after the month's last day (not drawn). */
const cellClass = (date: string | null, firstWeek: boolean) =>
  date !== null ? "desk-cal__cell" : `desk-cal__cell desk-cal__cell--${firstWeek ? "before" : "after"}`;

/** The weekday heads: the phone's words, but Thursday in full as desktop.html writes it (there is room here). */
const HEAD: readonly CopyKey[] = WEEK.map((w) => (w.key === "calendar.weekday.thu" ? "desk.cal.thu" : w.key));

/**
 * The calendar screen on the desktop layout (plan desktop-parity Phase 2, row C1; desktop.html `calView()`): one screen
 * of the shell's single column (DesktopShell with no conversation).
 * - the toolbar: the month as the heading, the month and list views, and ‹ today › to move by month and back to today's day;
 * - the month across the region, Saturday first, each day a link to itself that names its papers by urgency (NOW,
 *   then THIS_WEEK, then the doctor's dates, then the rest; three, then a count), today's numeral filled, the chosen day
 *   marked, a doctor's day ringed;
 * - the chosen day (chosenDay) on the right: its papers, each with `desk.cal.open` to the talk-and-papers screen
 *   on that paper, and its doctor's dates, each with the doctor's words as written and the way to their paper;
 * - the list view: the page's list instead (filters, the papers by month, more), with no month and no panel.
 * Everything is a plain link, so it reads and moves without JavaScript; where Next answers it, in place. Its stylesheet
 * comes through its loader (desk-load.tsx), so only a page that renders it links it (DR-42).
 */
export function DeskCalendar(props: DeskCalendarProps) {
  const { lang, view, calendar, today, asked, before, after, at, soft, open, list } = props;
  const month = calendar.month;
  const chosen = chosenDay(calendar, today, asked);
  const byDate = new Map(calendar.days.map((d) => [d.date, d]));
  const { dates, firstColumn } = monthDays(month);
  const cells: Array<string | null> = [...Array<null>(firstColumn).fill(null), ...dates];
  while (cells.length % 7 !== 0) cells.push(null);
  const weeks = Array.from({ length: cells.length / 7 }, (_, w) => cells.slice(w * 7, w * 7 + 7));
  const theDay = byDate.get(chosen);

  return (
    <div className="desk-cal" data-view={view}>
      <DeskCalendarStyles />
      <section className="desk-cal__main">
        <header className="desk-cal__bar">
          <h1 className="desk-cal__title">{view === "month" ? formatMonthYear(lang, `${month}-01`) : t(lang, "timeline.docs")}</h1>
          <span className="desk-cal__space" />
          <nav className="desk-cal__views" aria-label={t(lang, "desk.cal.view")}>
            <Go soft={soft} href={at({ month, view: "month" })} className="desk-cal__view" aria-current={view === "month" ? "true" : undefined}>
              {t(lang, "desk.cal.month")}
            </Go>
            <Go soft={soft} href={at({ month, view: "list" })} className="desk-cal__view" aria-current={view === "list" ? "true" : undefined}>
              {t(lang, "desk.cal.list")}
            </Go>
          </nav>
          {view === "month" && (
            <>
              <span className="desk-cal__sep" aria-hidden="true" />
              <Go soft={soft} href={at({ month: before, view: "month" })} className="desk-cal__button" aria-label={t(lang, "desk.cal.prev")} title={t(lang, "desk.cal.prev")}>
                <Chevron back />
              </Go>
              <Go soft={soft} href={at({ month: today.slice(0, 7), day: today, view: "month" })} className="desk-cal__today">
                {t(lang, "desk.cal.today")}
              </Go>
              <Go soft={soft} href={at({ month: after, view: "month" })} className="desk-cal__button" aria-label={t(lang, "desk.cal.next")} title={t(lang, "desk.cal.next")}>
                <Chevron />
              </Go>
            </>
          )}
        </header>
        {view === "month" ? (
          <div className="desk-cal__month">
            {/* A table, as the phone's: each column headed by its weekday, each day a link in its cell. */}
            <table className="desk-cal__grid">
              <thead>
                <tr>
                  {HEAD.map((key, i) => (
                    <th key={key} scope="col" className={WEEK[i]!.fri ? "desk-cal__wd desk-cal__wd--fri" : "desk-cal__wd"}>
                      {t(lang, key)}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {weeks.map((week, w) => (
                  <tr key={w}>
                    {week.map((date, column) => (
                      <td key={column} className={cellClass(date, w === 0)}>
                        {date !== null && (
                          <Day
                            lang={lang}
                            date={date}
                            day={byDate.get(date)}
                            fri={WEEK[column]!.fri}
                            today={date === today}
                            chosen={date === chosen}
                            href={at({ month, day: date, view: "month" })}
                            soft={soft}
                          />
                        )}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="desk-cal__list">{list}</div>
        )}
      </section>
      {view === "month" && (
        <aside className="desk-cal__panel" aria-label={t(lang, "desk.cal.chosen")}>
          <h2 className="desk-cal__panel-title">{formatDayMonth(lang, chosen)}</h2>
          {theDay === undefined || (theDay.papers.length === 0 && theDay.follow_ups.length === 0) ? (
            <p className="desk-cal__none">{t(lang, "desk.cal.none")}</p>
          ) : (
            <>
              {theDay.papers.map((paper) => (
                <PaperCard key={paper.document_id} lang={lang} paper={paper} open={open(paper.document_id)} />
              ))}
              {theDay.follow_ups.map((follow, i) => (
                <VisitCard key={`${follow.document_id}-${i}`} lang={lang} date={chosen} follow={follow} open={open(follow.document_id)} />
              ))}
            </>
          )}
        </aside>
      )}
    </div>
  );
}

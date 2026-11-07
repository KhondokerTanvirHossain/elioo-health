import "server-only";
import type { ReactNode } from "react";
import type { CalendarJson, TimelineJson } from "@/api/types";
import { t } from "@/copy";
import { DeskCalendar, type DeskCalendarView } from "@/components/DeskCalendar/DeskCalendar";
import { href } from "@/routing/paths";
import type { ShellContext } from "../shell";
import { shift } from "./months";
import { FlagsAsk, PaperList, asked, query, read } from "./paper-list";

/**
 * The papers page on the desktop layout: the desktop calendar (plan desktop-parity Phase 2, C1; desktop.html
 * `calView()`), for the patient the shell shows. It reads what the phone's page reads, by the same paths, so javaGet's
 * cache() keeps each one read: the month (`?month=`, Java's current month when none) and, for the list view only, the
 * timeline page the address asks for (one TIMELINE_VIEW per render, as the phone's: S14). Nothing else is read for it:
 * the chosen day's papers are the calendar's own entries (TimelineJson.calendar sends each paper as the timeline does).
 * - `?day=` chooses the day the panel shows (DeskCalendar's chosenDay); `?view=list` is the list (filters, papers, more,
 *   and the chronic-flags ask while none is stored);
 * - a paper's open link (`desk.cal.open`) opens the talk-and-papers screen on that paper (`/app/home?patient=…&paper=…`,
 *   home/desk.tsx's deskHref, written here so this route does not import the conversation's modules).
 */
export async function calendarScreen(ctx: ShellContext, here: string, { today, soft }: { today: string; soft: boolean }): Promise<ReactNode> {
  const { lang, preview, patient } = ctx;
  if (patient === null) {
    return (
      <div className="papers">
        <h1 className="papers__title">{t(lang, "timeline.docs")}</h1>
        <p className="papers__note">{t(lang, "patients.none")}</p>
      </div>
    );
  }
  const { type, cursor, month, day, list } = asked(here);
  const view: DeskCalendarView = list ? "list" : "month";
  const base = `/app/api/patients/${patient.id}`;
  const [calendar, timeline] = await Promise.all([
    read<CalendarJson>(`${base}/calendar${query({ month })}`, here),
    list ? read<TimelineJson>(`${base}/timeline${query({ type, cursor })}`, here) : Promise.resolve(null),
  ]);
  const page = (params: Record<string, string | null>) => href(`/app/timeline${query({ patient: patient.id, ...params })}`, preview);
  const at = (q: { month?: string | null; day?: string | null; view: DeskCalendarView }) =>
    page({ month: q.month ?? null, day: q.day ?? null, view: q.view === "list" ? "list" : null });
  const open = (id: string) => href(`/app/home?patient=${encodeURIComponent(patient.id)}&paper=${encodeURIComponent(id)}`, preview);

  return (
    <DeskCalendar
      lang={lang}
      view={view}
      calendar={calendar}
      today={today}
      asked={day}
      before={shift(calendar.month, -1)}
      after={shift(calendar.month, 1)}
      at={at}
      soft={soft}
      open={open}
      list={
        timeline !== null && (
          <>
            <PaperList lang={lang} preview={preview} timeline={timeline} type={type} at={(q) => page({ view: "list", month, ...q })} />
            {!patient.chronic_flags_set && <FlagsAsk lang={lang} patient={patient} preview={preview} />}
          </>
        )
      }
    />
  );
}

import Link from "next/link";
import type { ReactNode } from "react";
import type { FollowUpItemJson, MeJson, MePatient } from "@/api/types";
import { banglaDigits, formatMonth, t, type CopyKey, type Lang } from "@/copy";
import { MioAvatar } from "@/components/MioAvatar/MioAvatar";
import { ChooseLink } from "@/components/PatientChooser/ChooseLink";
import { ChipShape } from "@/components/StatusChip/StatusChip";
import { UrgentMark } from "@/components/safety/UrgentMark/UrgentMark";
import { Wordmark } from "@/components/Wordmark/Wordmark";
import { href, reachable, servedByNext } from "@/routing/paths";
import { DesktopSidebarStyles } from "./desk-load";

/** Which ledger item, or the account row, the page is. */
export type DesktopPlace = "talk" | "calendar" | "labs" | "meds" | "settings";

// The ledger in desktop.html's order, each to the page that holds it: the calendar is the papers page, the lab values
// and the medicines are the health page, the medicines on `?show=medicines` (plan desktop-parity L1, M1).
const LEDGER: { place: Exclude<DesktopPlace, "settings">; path: string; key: CopyKey; icon: ReactNode }[] = [
  {
    place: "talk",
    path: "/app/home",
    key: "desk.nav.talk",
    icon: <path d="M12 4.5c4.7 0 8.5 3.1 8.5 7s-3.8 7-8.5 7c-1 0-2-.1-2.9-.4L5 19.5l1.2-3.4C4.8 14.9 3.5 13.3 3.5 11.5c0-3.9 3.8-7 8.5-7z" />,
  },
  {
    place: "calendar",
    path: "/app/timeline",
    key: "desk.nav.calendar",
    icon: <path d="M3.5 8a3 3 0 0 1 3-3h11a3 3 0 0 1 3 3v9.5a3 3 0 0 1-3 3h-11a3 3 0 0 1-3-3zM3.5 10h17M8 3v4M16 3v4" />,
  },
  {
    place: "labs",
    path: "/app/health",
    key: "desk.nav.labs",
    icon: <path d="M9.5 3.5h5M10.5 3.5v5.2L5.4 18a1.7 1.7 0 0 0 1.5 2.5h10.2a1.7 1.7 0 0 0 1.5-2.5l-5.1-9.3V3.5M7.5 14.5h9" />,
  },
  {
    place: "meds",
    path: "/app/health?show=medicines",
    key: "desk.nav.meds",
    icon: <path d="M6.4 17.6a3.5 3.5 0 0 1 0-5l6.2-6.2a3.5 3.5 0 0 1 5 5l-6.2 6.2a3.5 3.5 0 0 1-5 0zM9.5 9.5l5 5" />,
  },
];

// What is coming (desktop.html's `togglesoon` group, desk.nav.soon): named, marked soon, and never a link in Phase 1.
const SOON: { key: CopyKey; icon: ReactNode }[] = [
  { key: "desk.soon.summary", icon: <path d="M6 3.5h12a1 1 0 0 1 1 1v15a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-15a1 1 0 0 1 1-1zM8.5 8h7M8.5 11.5h7M8.5 15h4" /> },
  { key: "desk.soon.card", icon: <path d="M5.5 5.5h13a2.5 2.5 0 0 1 2.5 2.5v8a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 16V8a2.5 2.5 0 0 1 2.5-2.5zM12 9v6M9 12h6" /> },
  { key: "desk.soon.conditions", icon: <path d="M12 3.5a8.5 8.5 0 1 1 0 17 8.5 8.5 0 0 1 0-17zM6 6l12 12" /> },
  { key: "desk.soon.vaccines", icon: <path d="M14 4l6 6M17 7l-9.5 9.5-3 .5.5-3L14.5 4.5M10 11l3 3M3.5 20.5l2-2" /> },
  { key: "desk.soon.scans", icon: <path d="M6.5 3.5h11A2.5 2.5 0 0 1 20 6v12a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 18V6a2.5 2.5 0 0 1 2.5-2.5zM12 6.5v11M9 9h6M8.5 12h7M9 15h6" /> },
  { key: "desk.soon.devices", icon: <path d="M10 6.5h4a3 3 0 0 1 3 3v5a3 3 0 0 1-3 3h-4a3 3 0 0 1-3-3v-5a3 3 0 0 1 3-3zM9 6.5L9.5 3h5l.5 3.5M9 17.5l.5 3.5h5l.5-3.5M12 10v2.5l1.5 1" /> },
];

const Icon = ({ children }: { children: ReactNode }) => (
  <svg className="desktop-sidebar__icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
    {children}
  </svg>
);

/** A number as the page writes it: Bangla digits on bn. */
const count = (lang: Lang, n: number) => (lang === "bn" ? banglaDigits(String(n)) : String(n));

/** The first letter a reader sees (a grapheme, so a Bangla conjunct stays whole), for the avatar. */
export function initialOf(name: string): string {
  const first = new Intl.Segmenter(undefined, { granularity: "grapheme" }).segment(name)[Symbol.iterator]().next();
  return first.done ? "" : first.value.segment;
}

/**
 * The doctor's next date from GET /app/api/patients/{id}/follow-ups (soonest first, as Java sends them): the first one
 * Java does not call overdue. An overdue date is the papers page's to say (DueCard), not the sidebar's "next".
 */
export function nextVisitOf(items: FollowUpItemJson[]): FollowUpItemJson | null {
  return items.find((item) => !item.overdue) ?? null;
}

/** Whole days from `today` to `due`, both ISO calendar days (the patient's Dhaka day, which the caller supplies). */
export function daysUntil(today: string, due: string): number {
  const day = (iso: string) => {
    const [y, m, d] = iso.split("-").map(Number);
    return Date.UTC(y!, m! - 1, d!);
  };
  return Math.round((day(due) - day(today)) / 86_400_000);
}

/** A patient's urgency, as Java sent it on GET /app/api/me: NOW also when only `urgent` says so (erring to the warning). */
function levelOf(p: MePatient): "now" | "this_week" | null {
  if (p.urgency === "now" || p.urgent) return "now";
  return p.urgency === "this_week" ? "this_week" : null;
}

/**
 * The patient's urgency word, never the colour alone (the existing urgency chip words, `calendar.day.now` and
 * `calendar.day.week`, the calendar cell's): NOW is the patient's red mark (UrgentMark, the octagon and the word, the one
 * place a patient list may paint the NOW red); THIS_WEEK is the this-week calendar shape and the word.
 */
function UrgencyWord({ lang, level }: { lang: Lang; level: "now" | "this_week" }) {
  if (level === "now") {
    return (
      <span className="desktop-sidebar__urgency" data-urgency="now">
        <UrgentMark word={t(lang, "calendar.day.now")} />
      </span>
    );
  }
  return (
    <span className="desktop-sidebar__urgency desktop-sidebar__urgency--calendar" data-urgency="this_week">
      <ChipShape shape="calendar-week" className="desktop-sidebar__urgency-shape" />
      <span className="desktop-sidebar__urgency-word">{t(lang, "calendar.day.week")}</span>
    </span>
  );
}

/** A link to one of the app's pages: a soft navigation when Next answers it for this viewer, else a real one. */
function PageLink({
  path,
  preview,
  current,
  className,
  children,
}: {
  path: string;
  preview: boolean;
  current: boolean;
  className: string;
  children: ReactNode;
}) {
  const at = current ? "page" : undefined;
  return servedByNext(path, preview) ? (
    <Link className={className} href={href(path, preview)} aria-current={at} prefetch={false}>
      {children}
    </Link>
  ) : (
    <a className={className} href={href(path, preview)} aria-current={at}>
      {children}
    </a>
  );
}

/**
 * The desktop sidebar (plan desktop-parity Task 5; desktop.html `sidebar()`), from GET /app/api/me and the patient's
 * follow-ups, and nothing else:
 * - the family (`desk.family`): every patient this family sees, in me's order, each a link that has Java remember the
 *   choice (`PUT /app/api/active-patient`, ChooseLink) and opens the same page for them; the avatar's initial, the
 *   name, and the urgency word on the right; the patient in view is `aria-current="page"`;
 * - the patient's ledger (`desk.ledger`, their possessive): talk and papers, the calendar (with the paper count), lab
 *   values and medicines (`desk.nav.*`), linked to today's pages and kept inside the preview on a preview page; then
 *   what is coming (`desk.nav.soon`), a closed disclosure of six things not built yet, each marked soon, none a link;
 * - the next doctor's date (`nextVisit`, nextVisitOf), with the days left from `today` (the caller's Dhaka day);
 * - the account row: whose account this is, opening the family page.
 * No patient in view (the chooser, a family with none): the family list and the account row only.
 */
export function DesktopSidebar({
  lang,
  preview,
  me,
  active,
  current,
  nextVisit,
  today,
}: {
  lang: Lang;
  preview: boolean;
  me: MeJson;
  active: MePatient | null;
  current: DesktopPlace | null;
  nextVisit: FollowUpItemJson | null;
  /** The patient's calendar day, ISO (yyyy-MM-dd): what "in N days" counts from. */
  today: string;
}) {
  const refused = t(lang, "app.err.notfound");
  const failed = t(lang, "app.err.generic");
  const days = nextVisit === null ? null : daysUntil(today, nextVisit.due_date);
  return (
    <div className="desktop-sidebar">
      <DesktopSidebarStyles />
      <div className="desktop-sidebar__brand">
        <Wordmark lang={lang} />
        <MioAvatar size={32} still />
      </div>
      <div className="desktop-sidebar__scroll">
        <section className="desktop-sidebar__group" aria-labelledby="desktop-sidebar-family">
          <h2 className="desktop-sidebar__group-title" id="desktop-sidebar-family">
            {t(lang, "desk.family")}
          </h2>
          <ul className="desktop-sidebar__family">
            {me.patients.map((p) => {
              const level = levelOf(p);
              return (
                <li key={p.id} className="desktop-sidebar__family-item">
                  <ChooseLink
                    patientId={p.id}
                    refused={refused}
                    failed={failed}
                    className="desktop-sidebar__item desktop-sidebar__patient"
                    statusClassName="desktop-sidebar__status"
                    current={active?.id === p.id}
                  >
                    <span className="desktop-sidebar__avatar" aria-hidden="true">
                      {initialOf(p.name)}
                    </span>
                    <span className="desktop-sidebar__name">{p.name}</span>
                    {level !== null && <UrgencyWord lang={lang} level={level} />}
                  </ChooseLink>
                </li>
              );
            })}
          </ul>
        </section>
        {active !== null && (
          <section className="desktop-sidebar__group" aria-labelledby="desktop-sidebar-ledger">
            <h2 className="desktop-sidebar__group-title desktop-sidebar__ledger-title" id="desktop-sidebar-ledger">
              {t(lang, "desk.ledger", active.name_possessive)}
            </h2>
            <nav className="desktop-sidebar__ledger" aria-labelledby="desktop-sidebar-ledger">
              <ul className="desktop-sidebar__list">
                {LEDGER.filter(({ path }) => reachable(path, preview)).map(({ place, path, key, icon }) => (
                  <li key={place}>
                    <PageLink path={path} preview={preview} current={current === place} className="desktop-sidebar__item">
                      <Icon>{icon}</Icon>
                      <span className="desktop-sidebar__label">{t(lang, key)}</span>
                      {place === "calendar" && <span className="desktop-sidebar__count">{count(lang, active.documents)}</span>}
                    </PageLink>
                  </li>
                ))}
              </ul>
            </nav>
            <details className="desktop-sidebar__soon">
              <summary className="desktop-sidebar__item desktop-sidebar__soon-toggle">
                <svg className="desktop-sidebar__icon desktop-sidebar__chevron" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                  <path d="M9.5 6l6 6-6 6" />
                </svg>
                <span className="desktop-sidebar__label">{t(lang, "desk.nav.soon")}</span>
                <span className="desktop-sidebar__count">{count(lang, SOON.length)}</span>
              </summary>
              <ul className="desktop-sidebar__list">
                {SOON.map(({ key, icon }) => (
                  <li key={key} className="desktop-sidebar__item desktop-sidebar__soon-item">
                    <Icon>{icon}</Icon>
                    <span className="desktop-sidebar__label">{t(lang, key)}</span>
                    <span className="desktop-sidebar__badge">{t(lang, "desk.soon.badge")}</span>
                  </li>
                ))}
              </ul>
            </details>
          </section>
        )}
      </div>
      {active !== null && nextVisit !== null && days !== null && (
        // The calendar on the visit's day, for this patient (desktop.html's `.nextup`: data-view="papers" data-day="22").
        <PageLink
          path={`/app/timeline?patient=${encodeURIComponent(active.id)}&month=${nextVisit.due_date.slice(0, 7)}&day=${nextVisit.due_date}`}
          preview={preview}
          current={false}
          className="desktop-sidebar__visit"
        >
          <span className="desktop-sidebar__calchip">
            <small className="desktop-sidebar__visit-month">{formatMonth(lang, nextVisit.due_date)}</small>
            <span className="desktop-sidebar__visit-day">{count(lang, Number(nextVisit.due_date.slice(8, 10)))}</span>
          </span>
          <span className="desktop-sidebar__visit-text">
            <b className="desktop-sidebar__visit-title">{t(lang, "desk.visit.title")}</b>
            {days > 0 && <em className="desktop-sidebar__visit-in">{t(lang, "desk.visit.in", days)}</em>}
          </span>
        </PageLink>
      )}
      <PageLink path="/app/family" preview={preview} current={current === "settings"} className="desktop-sidebar__account">
        <span className="desktop-sidebar__avatar desktop-sidebar__avatar--quiet" aria-hidden="true">
          {initialOf(me.family.owner_name)}
        </span>
        <span className="desktop-sidebar__account-text">
          <span className="desktop-sidebar__owner">{me.family.owner_name}</span>
          <small className="desktop-sidebar__account-label">{t(lang, "desk.account")}</small>
        </span>
        <svg className="desktop-sidebar__icon desktop-sidebar__gear" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
          <path d="M12 9a3 3 0 1 1 0 6 3 3 0 0 1 0-6zM12 2.5v3M12 18.5v3M21.5 12h-3M5.5 12h-3M18.7 5.3l-2.1 2.1M7.4 16.6l-2.1 2.1M18.7 18.7l-2.1-2.1M7.4 7.4L5.3 5.3" />
        </svg>
      </PageLink>
    </div>
  );
}

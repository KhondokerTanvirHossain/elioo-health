import type { JSX } from "react";
import type { CalendarJson, Json, LabSeriesJson, LabsJson, NowBannerJson, PaperJson } from "@/api/types";
import { banglaDigits, formatMonthYear, t, type Lang } from "@/copy";
import { MessageBubble } from "@/components/MessageBubble/MessageBubble";
import { MioAvatar } from "@/components/MioAvatar/MioAvatar";
import { MonthCalendar } from "@/components/MonthCalendar/MonthCalendar";
import { OtpInput } from "@/components/OtpInput/OtpInput";
import { PhoneField } from "@/components/PhoneField/PhoneField";
import { NowBanner, type NowBannerPatient } from "@/components/safety/NowBanner/NowBanner";
import { Sheet } from "@/components/Sheet/Sheet";
import { CHIP_KINDS, StatusChip } from "@/components/StatusChip/StatusChip";
import { Toast } from "@/components/Toast/Toast";
import { TrendChart } from "@/components/TrendChart/TrendChart";
import { VerdictCard } from "@/components/safety/VerdictCard/VerdictCard";
import { Wordmark } from "@/components/Wordmark/Wordmark";
// B3's generated GET /app/api/now-banner bodies (baymax NowBannerApiContractTest): each state reads one as Java wrote it.
import nowBannerAbroad from "../../fixtures/api/now-banner/abroad.json";
import nowBannerAcked from "../../fixtures/api/now-banner/acked.json";
import nowBannerOne from "../../fixtures/api/now-banner/one.json";
import nowBannerTwoPatients from "../../fixtures/api/now-banner/two-patients.json";
// The papers B2's PaperApiContractTest wrote from Java's PaperJson (fixtures/api/documents, the 200 bodies).
import failed from "../../fixtures/api/documents/failed.json";
import nowAbroad from "../../fixtures/api/documents/now-abroad.json";
import nowBd from "../../fixtures/api/documents/now-bd.json";
import processing from "../../fixtures/api/documents/processing.json";
import retakeThird from "../../fixtures/api/documents/retake-third.json";
import routineReleased from "../../fixtures/api/documents/routine-released.json";
import thisWeekPending from "../../fixtures/api/documents/this-week-pending.json";
import unassessed from "../../fixtures/api/documents/unassessed.json";
// B3's and B6's generated calendar and labs bodies (plan W2e-b): a state shows what the API sends, never a hand fixture.
import busyCalendar from "../../fixtures/api/calendar/busy.json";
import crowdedCalendar from "../../fixtures/api/calendar/crowded.json";
import emptyCalendar from "../../fixtures/api/calendar/empty.json";
import labsSome from "../../fixtures/api/health/labs-some.json";
import labsWithUnknown from "../../fixtures/api/health/labs-with-unknown.json";
// Fictional names, written by baymax CatalogFixturesExportTest so no Bangla is typed here (Global Constraints).
import catalogNames from "../../fixtures/catalog/names.json";

/**
 * One state of one component, as the catalog renders it in each language. `empty` marks a state whose point is that
 * the component renders nothing (NowBanner when Java says `show: false`; TrendChart over one reading, which is a
 * sentence on the health screen, not a chart): catalog.spec.ts holds it to painting no element at all, and holds every
 * other state to rendering something. states.test.tsx holds the exact list of the states allowed to be empty.
 */
export type CatalogState = { component: string; state: string; render: (lang: Lang) => JSX.Element; empty?: true };

const names = catalogNames.names;

/**
 * One VerdictCard state per paper fixture, named by its file. states.test.tsx derives the expected ids from the
 * directory, so a fixture B2 adds fails there until it is listed here; types.test.ts holds every body to PaperJson,
 * the literals a JSON import widens included, which is what makes the cast below true.
 */
export const PAPER_FIXTURES: Record<string, Json<PaperJson>> = {
  failed: failed.body,
  "now-abroad": nowAbroad.body,
  "now-bd": nowBd.body,
  processing: processing.body,
  "retake-third": retakeThird.body,
  "routine-released": routineReleased.body,
  "this-week-pending": thisWeekPending.body,
  unassessed: unassessed.body,
};
const nameFor = (lang: Lang) => (lang === "bn" ? names.short : names.english);

/** The calendar's caption as the screen passes it: the patient's name and the month (the only name in the component). */
const monthCaption = (name: string, lang: Lang, month: string) => `${name} · ${formatMonthYear(lang, `${month}-01`)}`;

/** One series of a generated labs body, chosen by its canonical name. */
function seriesOf(labs: LabsJson, canonical: string): LabSeriesJson {
  const series = labs.series.find((s) => s.canonical_name === canonical);
  if (!series) throw new Error(`catalog: no series ${canonical} in the labs fixture`);
  return series;
}

/** A month of the calendar from a generated body, captioned with `name` and the month. */
const calendar = (body: CalendarJson, name: string, lang: Lang) => (
  <MonthCalendar month={body.month} days={body.days} caption={monthCaption(name, lang, body.month)} lang={lang} />
);

/**
 * The patients GET /app/api/me would list beside a banner, reduced to the id and the name the banner reads: one per
 * distinct patient of its documents, in order, named from `pool`. The name is display data the screen supplies, so it is the one thing a NowBanner state chooses; the
 * banner is the generated body, unchanged.
 */
function bannerPatients(banner: NowBannerJson, pool: string[]): NowBannerPatient[] {
  const ids = [...new Set(banner.documents.flatMap((d) => (d.patient_id === null ? [] : [d.patient_id])))];
  return ids.map((id, i) => ({ id, name: pool[i % pool.length]! }));
}

const nowBanner = (state: string, banner: NowBannerJson, pool: (lang: Lang) => string[], empty?: true): CatalogState => ({
  component: "NowBanner",
  state,
  render: (lang) => <NowBanner banner={banner} patients={bannerPatients(banner, pool(lang))} lang={lang} />,
  ...(empty ? { empty } : {}),
});

/**
 * Every catalog state, registered per component. /app/catalog-test renders each one in both languages, and
 * catalog.spec.ts runs its checks (overflow, the rendered NOW red, axe, 12 px, CSP, the drawn --boundary on every field
 * and track) over every one of them, so a state added here is covered without a test edit. Each component PR adds its
 * states here and to states.test.tsx's SHIPPED list.
 */
export const STATES: CatalogState[] = [
  { component: "Wordmark", state: "default", render: (lang) => <Wordmark lang={lang} /> },

  { component: "MioAvatar", state: "32", render: () => <MioAvatar size={32} /> },
  { component: "MioAvatar", state: "48-still", render: () => <MioAvatar size={48} still /> },
  { component: "MioAvatar", state: "96", render: () => <MioAvatar size={96} /> },

  {
    component: "MessageBubble",
    state: "recv",
    render: (lang) => (
      <MessageBubble surface="recv" tail>
        {t(lang, "chat.hello", nameFor(lang))}
      </MessageBubble>
    ),
  },
  {
    component: "MessageBubble",
    state: "sent",
    render: (lang) => (
      <MessageBubble surface="sent" tail>
        {t(lang, "chat.camera")}
      </MessageBubble>
    ),
  },
  {
    // A token that cannot wrap at a space (a long name typed without one) must wrap inside the bubble, never push the
    // row wider than the screen (plan Review Focus 3).
    component: "MessageBubble",
    state: "long-unbroken",
    render: (lang) => (
      <MessageBubble surface="recv" tail>
        {t(lang, "chat.hello", names.long_unbroken)}
      </MessageBubble>
    ),
  },

  ...CHIP_KINDS.map(
    (kind): CatalogState => ({ component: "StatusChip", state: kind, render: (lang) => <StatusChip kind={kind} lang={lang} /> }),
  ),

  // The sign-in fields (W2e-a). A fictional code, in the digits a family would type in each language. The lifetime
  // line is the verify page's, at Java's default otpTtl of 10 minutes (BaymaxProperties).
  { component: "OtpInput", state: "empty", render: (lang) => <OtpInput length={6} lang={lang} name="code" ttlMinutes={10} /> },
  {
    component: "OtpInput",
    state: "filled",
    render: (lang) => (
      <OtpInput length={6} lang={lang} name="code" ttlMinutes={10} defaultValue={lang === "bn" ? banglaDigits("042917") : "042917"} />
    ),
  },
  {
    // A code Java refused (VerifyFailure.WRONG, the copy key login.bad.wrong), shown back as typed.
    component: "OtpInput",
    state: "error",
    render: (lang) => (
      <OtpInput
        length={6}
        lang={lang}
        name="code"
        ttlMinutes={10}
        error={{ key: "login.bad.wrong" }}
        defaultValue={lang === "bn" ? banglaDigits("042918") : "042918"}
      />
    ),
  },
  { component: "PhoneField", state: "empty", render: (lang) => <PhoneField lang={lang} name="whatsapp_number" /> },
  {
    // A number Java refused (INVALID_NUMBER, the copy key login.number.invalid), shown back as typed: too short.
    component: "PhoneField",
    state: "error",
    render: (lang) => <PhoneField lang={lang} name="whatsapp_number" error="login.number.invalid" defaultValue="01700 0000" />,
  },

  // The NOW banner over each of B3's generated bodies; acked.json is the one Java says not to show. The seen button is
  // inert here: the catalog passes no onSeen, and without one NowBannerSeen sends no request at all
  // (NowBanner.test.tsx › theCatalogButtonSendsNothing taps every one of these).
  nowBanner("one-patient", nowBannerOne.body, (lang) => [nameFor(lang)]),
  nowBanner("two-patients", nowBannerTwoPatients.body, (lang) => [nameFor(lang), names.long]),
  nowBanner("abroad", nowBannerAbroad.body, (lang) => [nameFor(lang)]),
  nowBanner("hidden", nowBannerAcked.body, (lang) => [nameFor(lang)], true),
  // A long name, spaced and unbroken: the title wraps inside the banner, never past it (plan Review Focus 3).
  nowBanner("long-name", nowBannerOne.body, () => [names.long]),
  nowBanner("long-unbroken", nowBannerOne.body, () => [names.long_unbroken]),

  ...Object.entries(PAPER_FIXTURES).map(
    ([name, body]): CatalogState => ({
      component: "VerdictCard",
      state: name,
      render: (lang) => <VerdictCard paper={body as PaperJson} lang={lang} />,
    }),
  ),

  { component: "Toast", state: "default", render: (lang) => <Toast messageKey="profile.saved" lang={lang} /> },

  {
    // Opened as a modal on load (showModal), so the catalog shows it as it is used; catalog.spec.ts closes both before
    // its whole-page axe run (a modal makes the rest of the page inert) and checks this one on its own.
    component: "Sheet",
    state: "open",
    render: (lang) => (
      <Sheet titleKey="retake.tips" open lang={lang}>
        <ul>
          <li>{t(lang, "retake.tip.1")}</li>
          <li>{t(lang, "retake.tip.2")}</li>
          <li>{t(lang, "retake.tip.3")}</li>
        </ul>
      </Sheet>
    ),
  },

  { component: "MonthCalendar", state: "empty-month", render: (lang) => calendar(emptyCalendar.body, nameFor(lang), lang) },
  { component: "MonthCalendar", state: "busy-month", render: (lang) => calendar(busyCalendar.body, nameFor(lang), lang) },
  // The caption is the one place the calendar names a patient: a long name, and one with no space to wrap at, must
  // wrap inside the grid's width, never widen it (plan Review Focus 3).
  { component: "MonthCalendar", state: "long-name", render: (lang) => calendar(busyCalendar.body, names.long, lang) },
  { component: "MonthCalendar", state: "long-unbroken", render: (lang) => calendar(busyCalendar.body, names.long_unbroken, lang) },
  // Two papers and a doctor's date on one day, a this-week day alone, and a day where NOW outranks this week.
  { component: "MonthCalendar", state: "crowded-month", render: (lang) => calendar(crowdedCalendar.body, nameFor(lang), lang) },

  // One reading is no chart (it is a sentence on the health screen, W9): this state renders nothing, on purpose.
  {
    component: "TrendChart",
    state: "one-point",
    render: (lang) => <TrendChart series={seriesOf(labsSome.body, "potassium")} lang={lang} />,
    empty: true,
  },
  { component: "TrendChart", state: "rising", render: (lang) => <TrendChart series={seriesOf(labsSome.body, "creatinine")} lang={lang} /> },
  // Every chip kind but critical low, over two calendar years: critical high, outside low, unknown, outside high, inside.
  {
    component: "TrendChart",
    state: "with-unknown",
    render: (lang) => <TrendChart series={seriesOf(labsWithUnknown.body, "hba1c")} lang={lang} />,
  },
  {
    component: "TrendChart",
    state: "critical-low",
    render: (lang) => <TrendChart series={seriesOf(labsWithUnknown.body, "potassium")} lang={lang} />,
  },
];

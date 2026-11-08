import "server-only";
import { notFound } from "next/navigation";
import type { ReactNode } from "react";
import { javaGet, signedIn } from "@/api/java";
import { requestContext } from "@/api/request";
import type { MeJson, MePatient, NowBannerJson } from "@/api/types";
import type { Lang } from "@/copy";
import { AppShell } from "@/components/AppShell/AppShell";
import { PatientChooser } from "@/components/PatientChooser/PatientChooser";
import { NowBanner } from "@/components/safety/NowBanner/NowBanner";
import { NowBannerHold } from "@/components/safety/NowBanner/NowBannerHold";
import type { Tab } from "@/components/TabBar/TabBar";
import type { DesktopPlace } from "@/components/DesktopSidebar/DesktopSidebar";
import { desktopLayout } from "@/routing/desktop";
import { activePatient } from "./active-patient";
import { shellWords } from "./shell-words";

/**
 * What a page's slots are rendered from: the request, GET /app/api/me, the patient whose papers are shown, and
 * `nowShown`, whether the global NOW banner is on this page (GET /app/api/now-banner's `show`), so a page keeps Mio
 * still and its NOW result in view wherever the banner is.
 */
export type ShellContext = { lang: Lang; preview: boolean; me: MeJson; patient: MePatient | null; nowShown: boolean };

/** A slot: what the page renders, given whose papers are shown (null for a family with no patient). */
export type Slot = ReactNode | ((ctx: ShellContext) => ReactNode | Promise<ReactNode>);

const fill = async (slot: Slot | undefined, ctx: ShellContext) => (typeof slot === "function" ? await slot(ctx) : slot);

/**
 * What the desktop layout's frame is given (desktop-frame.tsx): the request, whose papers are shown, the page's tab, the
 * NOW bar (undefined when no banner shows) and the page's three desktop columns, already rendered.
 */
export type DesktopFrameInput = {
  lang: Lang;
  preview: boolean;
  here: string;
  me: MeJson;
  patient: MePatient | null;
  tab: Tab | null;
  /** The sidebar's item this screen is, when the tab alone does not say (the health page's medicines); else the tab's. */
  place: DesktopPlace | undefined;
  bar: ReactNode | undefined;
  paper: ReactNode;
  /** undefined: a screen of one column (the calendar), drawn with no conversation and no divider. */
  talk: ReactNode | undefined;
  panel: ReactNode | undefined;
};

/** The desktop layout's frame: DesktopShell and its sidebar around the columns (desktop-frame.tsx's desktopFrame). */
export type DesktopFrame = (input: DesktopFrameInput) => Promise<ReactNode>;

/** The page's own `?patient=`, read from the address the browser asked for (requestContext().here). */
function askedPatient(here: string): string | null {
  return new URL(here, "http://medioo.invalid").searchParams.get("patient");
}

/**
 * Every signed-in page renders through this (plan W4, Shared interfaces → "the shell and the banner render per page"):
 * one read of GET /app/api/me through javaGet (the preview gate, the cookie and nothing else), and the shell around
 * the page's slots. A page is rendered on every navigation and a layout is not, which is why this is a helper a page
 * calls and not `(signed-in)/layout.tsx`; it also reads the NOW banner here (W5), for the same reason: a NOW paper
 * that arrived while the family moved from page to page by a soft navigation shows on the next page, never only after
 * a hard load. The banner is global: every signed-in page, whoever's papers it shows, the chooser included. It is
 * rendered live inside NowBannerHold, keyed by what Java sent, so a banner that changed starts shown again.
 *
 * - No session: the sign-in page holding this place (signedIn, a 307 to `/app/?next=…`).
 * - A preview page for a family outside the preview: the not-found page. javaGet's gate stops every read but `me`;
 *   this stops the page itself, which might read nothing else.
 * - `?patient=` this family does not see: the not-found page (Java's 404). Two or more and no choice: the chooser in
 *   place of `main`, and no patient named anywhere. A screen never decides a patient.
 * - `asksItself` (the add sheet, W7): a page that asks whose it is in its own words, from its own read (whom the
 *   viewer may send for, which is not whom they may see). With two or more and no choice it gets `main` with no
 *   patient, never the chooser in its place; the toolbar still names nobody.
 * - `patient`, for a page that is about one patient's thing (a paper, W6): that patient, whose id Java sent with the
 *   thing, in place of `?patient=` and of the remembered choice, so a paper opened from WhatsApp shows the paper and not
 *   the chooser; `null` for a page about nobody (a paper that is gone), which names nobody and shows no chooser.
 * - `everyone` (plan W10, family): the page is about every patient this family sees, not one, so the chooser never
 *   stands in for it. Nothing is decided either: with two or more and no choice, `patient` is null and the toolbar
 *   names nobody, exactly as under the chooser. An asked `?patient=` is still checked (not found is still not found).
 * - `asked` (plan W10, the profile page): a patient the page's own address names (`/app/patients/{id}/profile`),
 *   resolved through activePatient in place of `?patient=`, as Java's ProfilePages chooses the patient its link names:
 *   the toolbar names that patient, and one this family does not see is the not-found page.
 * - `desktop` (plan desktop-parity Task 5): the page's two desktop columns, the paper and the conversation. When
 *   desktopLayout(ctx) says so (a preview request, until the flip) the page renders DesktopShell with DesktopSidebar
 *   from 900 px, and today's AppShell, marked `phone`, below it, so nothing below 900 px changes. Every other request,
 *   and every page that gives no `desktop`, renders today's AppShell alone, exactly as before. With the chooser in
 *   place of `main`, the paper column holds the chooser and the conversation is empty: the sidebar names nobody.
 *   `desktop.panel` (Task 8) is a paper's details over the conversation, when the page has one open (undefined: none).
 *   A page that gives no `desktop.talk` (the calendar, plan desktop-calendar) is one column: its `paper` slot holds a
 *   screen that lays itself out, and the chooser, when it stands in, fills that column alone.
 *   `desktop.place` is the sidebar's item the screen is, where one tab holds two (the health page: the lab values, or
 *   the medicines on `?show=medicines`, plan desktop-parity M1); without it the tab's.
 *   `desktop.frame` is desktop-frame.tsx's desktopFrame, which renders DesktopShell and DesktopSidebar: the page passes
 *   it, so only a page that can render the desktop layout imports them (DR-42), and this module names it as a type.
 *   The NOW banner is the slim bar there (NowBanner variant "bar", across the content columns), the block below 900 px.
 * `everyone` and `asked` are amendments of the plan's Global Constraint "a screen never decides a patient", approved by
 * the PO 2026-10-23 (P2). `patient` (W6) is a third, put to the PO for sight (the plan's Global Constraints): like
 * `asked` it resolves through activePatient, so a paper about a patient this family's `me` does not list is not found.
 */
export async function signedInPage({
  tab,
  main,
  aside,
  asksItself = false,
  everyone,
  asked,
  patient: about,
  desktop,
}: {
  tab: Tab | null;
  main: Slot;
  aside?: Slot;
  asksItself?: boolean;
  everyone?: boolean;
  asked?: string;
  patient?: string | null;
  desktop?: { frame: DesktopFrame; paper: Slot; talk?: Slot; panel?: Slot; place?: DesktopPlace };
}) {
  const { lang, preview, here } = await requestContext();
  const [meAnswer, bannerAnswer] = await Promise.all([javaGet<MeJson>("/app/api/me"), javaGet<NowBannerJson>("/app/api/now-banner")]);
  const me = signedIn(meAnswer, here);
  if (preview && me.preview !== true) notFound();
  const banner = signedIn(bannerAnswer, here);
  const view: ReturnType<typeof activePatient> =
    about === null ? { kind: "none" } : activePatient(me, about ?? asked ?? askedPatient(here));
  if (view.kind === "not-found") notFound();
  const patient = view.kind === "patient" ? view.patient : null;
  const ctx: ShellContext = { lang, preview, me, patient, nowShown: banner.show };
  const chooser = view.kind === "choose" && !asksItself && everyone !== true;
  const content = chooser ? <PatientChooser lang={lang} patients={me.patients} /> : await fill(main, ctx);
  const nowBanner = (variant: "block" | "bar") =>
    banner.show ? (
      <NowBannerHold key={JSON.stringify(banner.seen)}>
        <NowBanner banner={banner} patients={me.patients} lang={lang} live variant={variant} preview={preview} />
      </NowBannerHold>
    ) : undefined;
  const onDesktop = desktop !== undefined && desktopLayout(ctx);
  const phone = (
    <AppShell
      lang={lang}
      preview={preview}
      me={me}
      active={patient}
      words={shellWords(lang, me, patient)}
      tab={tab}
      main={content}
      aside={aside === undefined || chooser ? undefined : await fill(aside, ctx)}
      banner={nowBanner("block")}
      phone={onDesktop}
    />
  );
  if (!onDesktop) return phone;

  const frame = await desktop.frame({
    lang,
    preview,
    here,
    me,
    patient,
    tab,
    place: desktop.place,
    bar: nowBanner("bar"),
    paper: chooser ? <PatientChooser lang={lang} patients={me.patients} titleId="desktop-patient-chooser-title" /> : await fill(desktop.paper, ctx),
    talk: desktop.talk === undefined ? undefined : chooser ? null : await fill(desktop.talk, ctx),
    panel: chooser || desktop.panel === undefined ? undefined : ((await fill(desktop.panel, ctx)) ?? undefined),
  });
  return (
    <>
      {phone}
      {frame}
    </>
  );
}

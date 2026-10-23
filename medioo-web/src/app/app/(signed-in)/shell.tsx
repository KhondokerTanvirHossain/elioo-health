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
 * - `everyone` (plan W10, family): the page is about every patient this family sees, not one, so the chooser never
 *   stands in for it. Nothing is decided either: with two or more and no choice, `patient` is null and the toolbar
 *   names nobody, exactly as under the chooser. An asked `?patient=` is still checked (not found is still not found).
 * - `asked` (plan W10, the profile page): a patient the page's own address names (`/app/patients/{id}/profile`),
 *   resolved through activePatient in place of `?patient=`, as Java's ProfilePages chooses the patient its link names:
 *   the toolbar names that patient, and one this family does not see is the not-found page.
 * Both are amendments of the plan's Global Constraint "a screen never decides a patient", approved by the PO 2026-10-23 (P2).
 */
export async function signedInPage({
  tab,
  main,
  aside,
  asksItself = false,
  everyone,
  asked,
}: {
  tab: Tab | null;
  main: Slot;
  aside?: Slot;
  asksItself?: boolean;
  everyone?: boolean;
  asked?: string;
}) {
  const { lang, preview, here } = await requestContext();
  const [meAnswer, bannerAnswer] = await Promise.all([javaGet<MeJson>("/app/api/me"), javaGet<NowBannerJson>("/app/api/now-banner")]);
  const me = signedIn(meAnswer, here);
  if (preview && me.preview !== true) notFound();
  const banner = signedIn(bannerAnswer, here);
  const view = activePatient(me, asked ?? askedPatient(here));
  if (view.kind === "not-found") notFound();
  const patient = view.kind === "patient" ? view.patient : null;
  const ctx: ShellContext = { lang, preview, me, patient, nowShown: banner.show };
  const chooser = view.kind === "choose" && !asksItself && everyone !== true;
  const content = chooser ? <PatientChooser lang={lang} patients={me.patients} /> : await fill(main, ctx);
  return (
    <AppShell
      lang={lang}
      preview={preview}
      me={me}
      active={patient}
      words={shellWords(lang, me, patient)}
      tab={tab}
      main={content}
      aside={aside === undefined || chooser ? undefined : await fill(aside, ctx)}
      banner={
        banner.show ? (
          <NowBannerHold key={JSON.stringify(banner.seen)}>
            <NowBanner banner={banner} patients={me.patients} lang={lang} live />
          </NowBannerHold>
        ) : undefined
      }
    />
  );
}

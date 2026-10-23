import "server-only";
import { notFound } from "next/navigation";
import type { ReactNode } from "react";
import { javaGet, signedIn } from "@/api/java";
import { requestContext } from "@/api/request";
import type { MeJson, MePatient } from "@/api/types";
import type { Lang } from "@/copy";
import { AppShell } from "@/components/AppShell/AppShell";
import { PatientChooser } from "@/components/PatientChooser/PatientChooser";
import type { Tab } from "@/components/TabBar/TabBar";
import { activePatient } from "./active-patient";
import { shellWords } from "./shell-words";

/** What a page's slots are rendered from: the request, GET /app/api/me, and the patient whose papers are shown. */
export type ShellContext = { lang: Lang; preview: boolean; me: MeJson; patient: MePatient | null };

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
 * calls and not `(signed-in)/layout.tsx`; from W5 it also reads the NOW banner here, for the same reason.
 *
 * - No session: the sign-in page holding this place (signedIn, a 307 to `/app/?next=…`).
 * - A preview page for a family outside the preview: the not-found page. javaGet's gate stops every read but `me`;
 *   this stops the page itself, which might read nothing else.
 * - `?patient=` this family does not see: the not-found page (Java's 404). Two or more and no choice: the chooser in
 *   place of `main`, and no patient named anywhere. A screen never decides a patient.
 * - `asksItself` (the add sheet, W7): a page that asks whose it is in its own words, from its own read (whom the
 *   viewer may send for, which is not whom they may see). With two or more and no choice it gets `main` with no
 *   patient, never the chooser in its place; the toolbar still names nobody.
 */
export async function signedInPage({
  tab,
  main,
  aside,
  asksItself = false,
}: {
  tab: Tab | null;
  main: Slot;
  aside?: Slot;
  asksItself?: boolean;
}) {
  const { lang, preview, here } = await requestContext();
  const me = signedIn(await javaGet<MeJson>("/app/api/me"), here);
  if (preview && me.preview !== true) notFound();
  const view = activePatient(me, askedPatient(here));
  if (view.kind === "not-found") notFound();
  const patient = view.kind === "patient" ? view.patient : null;
  const ctx: ShellContext = { lang, preview, me, patient };
  const chooser = view.kind === "choose" && !asksItself;
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
    />
  );
}

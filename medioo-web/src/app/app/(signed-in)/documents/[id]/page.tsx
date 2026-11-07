import { signedIn } from "@/api/java";
import { requestContext } from "@/api/request";
import { PaperGone } from "@/components/Paper/PaperShared";
import { PaperSummary } from "@/components/Paper/PaperSummary";
import { deskScreen, type DeskScreen } from "../../home/desk";
import { desktopFrame } from "../../desktop-frame";
import { signedInPage, type ShellContext } from "../../shell";
import { bannerOnlyRefresh, heldMain, heldPart, keptMain, keptPart } from "./kept";
import { paperRead } from "./paper-read";
import { paperWords } from "./paper-words";

/**
 * `/app/documents/{id}`: one paper (plan W6), from its one read (paperRead) and nothing else, inside the shell, the
 * paper's own patient shown. No session is the sign-in page holding this place (a 307, so a no-JavaScript browser gets
 * there too, Review Focus 2); a preview page for a family outside the preview is the not-found page before the paper is
 * read (javaGet's gate). A paper Java no longer shows this family (deleted, by them or another member) says so, with
 * the way back: `doc.gone`, and `doc.deleted` when this browser's own delete brought it here. A banner-only refresh (a
 * stale `now.banner.seen` tap, banner-only.ts) reads no paper: the page keeps what it shows, so one open stays one
 * view.
 *
 * On the desktop layout (plan desktop-parity Task 8; Review Focus 1) the paper is the talk-and-papers screen with this
 * paper selected and its details panel open (deskScreen, over the paper read here), so a WhatsApp link lands on the
 * paper with everything showing: the NOW bar, the red card with 999, the panel. Each column is held like the main, so a
 * banner-only refresh keeps the whole screen. Below 900 px, and for every request the gate keeps on today's layout,
 * today's paper page, unchanged.
 */
export default async function PaperPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { id } = await params;
  const { lang, preview, here } = await requestContext();
  const held = await bannerOnlyRefresh(here);
  if (held) {
    return signedInPage({
      tab: null,
      patient: held.patient,
      main: keptMain(held),
      desktop: { frame: desktopFrame, paper: keptPart(held, "paper"), talk: keptPart(held, "talk"), panel: keptPart(held, "panel") },
    });
  }
  const answer = await paperRead(id);
  if (answer.status === 404) {
    const deleted = (await searchParams).deleted === "1";
    return signedInPage({
      tab: null,
      patient: null,
      main: heldMain(here, null, <PaperGone lang={lang} preview={preview} deleted={deleted} />),
    });
  }
  const paper = signedIn(answer, here);
  const who = paper.patient.id;
  let screen: Promise<DeskScreen> | undefined;
  const desk = (ctx: ShellContext) => (screen ??= deskScreen(ctx, here, { paperId: paper.id, paper, panel: true }));
  return signedInPage({
    tab: null,
    patient: who,
    main: heldMain(here, who, <PaperSummary paper={paper} lang={lang} preview={preview} words={paperWords(lang)} />),
    desktop: {
      frame: desktopFrame,
      paper: async (ctx) => heldPart(here, who, "paper", (await desk(ctx)).paper),
      talk: async (ctx) => heldPart(here, who, "talk", (await desk(ctx)).talk),
      panel: async (ctx) => heldPart(here, who, "panel", (await desk(ctx)).panel),
    },
  });
}

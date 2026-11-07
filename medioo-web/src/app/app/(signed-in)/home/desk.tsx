import "server-only";
import type { ReactNode } from "react";
import { javaGet, signedIn } from "@/api/java";
import {
  isReadingPaper,
  type AttentionJson,
  type AttentionUrgentJson,
  type PaperJson,
  type ThreadItemJson,
  type ThreadJson,
  type ThreadPaperJson,
} from "@/api/types";
import { t } from "@/copy";
import { Conversation } from "@/components/Conversation/Conversation";
import { PinnedUrgent } from "@/components/Conversation/PinnedUrgent";
import { PaperPanel } from "@/components/PaperPanel/PaperPanel";
import { PaperViewer } from "@/components/PaperViewer/PaperViewer";
import { mergeAsks } from "@/components/Thread/merge";
import { href } from "@/routing/paths";
import { paperRead } from "../documents/[id]/paper-read";
import type { ShellContext } from "../shell";

/** The three desktop columns of the talk-and-papers screen: the viewer, the conversation, and the panel (or none). */
export type DeskScreen = { paper: ReactNode; talk: ReactNode; panel: ReactNode | undefined };

/** What the address asks of the screen: the paper to show (or the newest), the paper already read, the panel open. */
export type DeskAsk = { paperId: string | null; paper?: PaperJson; panel: boolean };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** The paper cards of a thread, newest first: the viewer's papers and its strip. */
export const cardsOf = (items: readonly ThreadItemJson[]): PaperJson[] => items.flatMap((i) => (i.kind === "paper" ? [i.card] : []));

/** When a paper came, for its place in the thread: Java's `received_at`; else its filed day; else now (being read). */
function arrived(paper: PaperJson): string {
  if (paper.received_at !== null) return paper.received_at;
  return isReadingPaper(paper) ? new Date().toISOString() : `${paper.date}T00:00:00Z`;
}

/**
 * The thread with `paper` in it: as it is when the paper is already there (the thread's own card, never replaced), else
 * with the paper as a paper item at the place its time gives it, newest first, so a paper beyond the thread's first page
 * (an old link, a WhatsApp message about it) still has its card in the conversation, outlined, with its warning.
 */
export function withPaper(items: readonly ThreadItemJson[], paper: PaperJson): ThreadItemJson[] {
  if (cardsOf(items).some((c) => c.id === paper.id)) return [...items];
  const item: ThreadPaperJson = { kind: "paper", at: arrived(paper), card: paper };
  const at = Date.parse(item.at);
  const k = items.findIndex((i) => Date.parse(i.at) < at);
  return k < 0 ? [...items, item] : [...items.slice(0, k), item, ...items.slice(k)];
}

/** The screen's link to one of this patient's papers: `/app/home` with the patient and the paper named. */
export function deskHref(patientId: string | null, preview: boolean): (id: string) => string {
  const who = patientId === null ? "" : `patient=${encodeURIComponent(patientId)}&`;
  return (id) => href(`/app/home?${who}paper=${encodeURIComponent(id)}`, preview);
}

/**
 * The talk-and-papers screen on the desktop layout (plan desktop-parity Task 8; desktop.html `chatView()`), for the
 * patient the shell shows, from their thread (the two reads the chat page makes, javaGet's cache() keeping them one
 * read each) and nothing else, but for one case: a named paper the thread's first page does not hold is read
 * (GET /app/api/documents/{id}, the paper page's own read) and shown only when it is this patient's. A paper of another
 * patient, one that is gone and an id that is not one are not shown: the newest is, as with no paper named.
 * - the viewer (PaperViewer) shows the selected paper among this patient's papers;
 * - the conversation (Conversation) outlines its card, and holds still while the NOW bar is shown;
 * - the panel (PaperPanel) shows its details when `panel` asks, its close the same address without the panel.
 * The attention stack is not drawn here: the NOW bar and the paper's card carry its urgent item (P19), the sidebar the
 * next visit, the retake card its actions, the viewer a paper being read. But for one case: an urgent item (NOW or
 * THIS_WEEK) whose paper has no card on this screen. Java filters the stack's urgent rows in SQL, so no number of newer
 * papers and messages can hide a NOW one, while the thread's first page can; and once the family has tapped seen (`now.banner.seen`) the
 * bar is gone too. Such an item is drawn as the phone's stack draws it (PinnedUrgent: the band with Java's fixed
 * sentence, not-a-doctor, the way to the paper), pinned at the head of the conversation, which then opens there and holds still
 * when it is NOW, as the phone's chat does. Its paper is not read for it: an open nobody asked for is no DOCUMENT_VIEW.
 * The attention read is the chat page's own, one read through javaGet's cache().
 */
export async function deskScreen(ctx: ShellContext, here: string, ask: DeskAsk): Promise<DeskScreen> {
  const { lang, preview, patient } = ctx;
  if (patient === null) {
    const hrefFor = deskHref(null, preview);
    return { paper: <PaperViewer lang={lang} preview={preview} paper={null} papers={[]} selectedId={null} hrefFor={hrefFor} />, talk: null, panel: undefined };
  }
  const id = encodeURIComponent(patient.id);
  const [plain, asked, attention] = await Promise.all([
    javaGet<ThreadJson>(`/app/api/patients/${id}/thread`),
    javaGet<ThreadJson>(`/app/api/patients/${id}/thread?asks=1`),
    javaGet<AttentionJson>(`/app/api/patients/${id}/attention`),
  ]);
  let items = mergeAsks(signedIn(plain, here).items, signedIn(asked, here).items);
  let named: PaperJson | undefined = ask.paper ?? cardsOf(items).find((c) => c.id === ask.paperId);
  if (named === undefined && ask.paperId !== null && UUID.test(ask.paperId)) {
    const answer = await paperRead(ask.paperId);
    if (answer.status !== 404) named = signedIn(answer, here);
  }
  if (named !== undefined && named.patient.id !== patient.id) named = undefined;
  if (named !== undefined) items = withPaper(items, named);

  const papers = cardsOf(items);
  const onScreen = new Set(papers.map((p) => p.id));
  const unseen = signedIn(attention, here).items.filter(
    (i): i is AttentionUrgentJson => i.kind === "urgent" && !onScreen.has(i.document_id),
  );
  const pinned =
    unseen.length === 0 ? undefined : <PinnedUrgent items={unseen} lang={lang} preview={preview} possessive={patient.name_possessive} />;
  const selected = named ?? papers[0] ?? null;
  const hrefFor = deskHref(patient.id, preview);
  const selectedId = selected?.id ?? null;
  return {
    // The screen's level-one heading, out of sight (desktop.html's chatView() `h1.sr-only`): the phone's own is not drawn
    // from 900 px.
    paper: (
      <>
        <h1 className="desktop-shell__title">{t(lang, "desk.talk.title", patient.name_possessive)}</h1>
        <PaperViewer lang={lang} preview={preview} paper={selected} papers={papers} selectedId={selectedId} hrefFor={hrefFor} />
      </>
    ),
    talk: (
      <Conversation
        lang={lang}
        preview={preview}
        patient={patient}
        items={items}
        selectedId={selectedId}
        hrefFor={hrefFor}
        pinned={pinned}
        hold={ctx.nowShown || unseen.some((i) => i.level === "now")}
      />
    ),
    panel:
      ask.panel && selected !== null ? <PaperPanel lang={lang} preview={preview} paper={selected} closeHref={hrefFor(selected.id)} /> : undefined,
  };
}

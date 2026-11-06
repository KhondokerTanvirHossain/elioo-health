import type { ReactNode } from "react";
import { isReadingPaper, type MePatient, type PaperJson, type ThreadItemJson } from "@/api/types";
import { t, type Lang } from "@/copy";
import { composerNoJs, composerWords } from "@/app/app/(signed-in)/home/chat-words";
import { MessageBubble } from "@/components/MessageBubble/MessageBubble";
import { MioAvatar } from "@/components/MioAvatar/MioAvatar";
import { AskAnswer } from "@/components/safety/AskAnswer/AskAnswer";
import { call999Words } from "@/components/safety/AskAnswer/call999";
import { DaySeparator, dhakaDay } from "./DaySeparator";
import { Composer, ConversationStyles, OpenAtNewest } from "./desk-load";
import { QuickActions } from "./QuickActions";
import { TalkCard } from "./TalkCard";

/** The column that scrolls (Conversation.css): OpenAtNewest scrolls it, never the window. */
const COLUMN = ".conversation";
/** The selected paper's item, which the column opens on. */
const SELECTED = "[data-talk-selected]";

/**
 * One paper: the photo the family sent (its first page, on their side), then Medioo's card, then, on the newest paper
 * only (`newest`), a retake's actions.
 */
function PaperItem({
  lang,
  preview,
  paper,
  at,
  link,
  selected,
  newest,
}: {
  lang: Lang;
  preview: boolean;
  paper: PaperJson;
  at: string;
  link: string;
  selected: boolean;
  newest: boolean;
}) {
  const first = paper.pages[0];
  return (
    <li
      className="conversation__item conversation__item--paper"
      data-talk-item="paper"
      data-paper={paper.id}
      data-at={at}
      data-talk-selected={selected ? "" : undefined}
    >
      {first && (
        <div className="conversation__sent">
          <a className="conversation__photo" href={link} aria-current={selected ? "true" : undefined}>
            {/* eslint-disable-next-line @next/next/no-img-element -- next/image is banned (Global Constraints) */}
            <img className="conversation__photo-img" src={first.src} alt={t(lang, "retake.photo", 1)} loading="lazy" decoding="async" />
          </a>
        </div>
      )}
      <TalkCard lang={lang} paper={paper} link={link} selected={selected} />
      {newest && !isReadingPaper(paper) && <QuickActions lang={lang} preview={preview} paper={paper} />}
    </li>
  );
}

/**
 * The conversation column of the talk-and-papers screen (desk.nav.talk; plan desktop-parity Task 7; desktop.html
 * `chatView()`'s `.convo`), over the patient's thread as Java sent it (newest first; the page merges the asks with
 * mergeAsks) and nothing else:
 * - the header: Mio, Medioo, and whose papers this is about with "not a doctor" (`desk.talk.sub`): the desktop's
 *   footer disclaimer is gone (X1), and this is where it is said;
 * - the thread, oldest first so the newest is beside the composer, a day line (DaySeparator) before each day's first
 *   item (keyed on its place, so a list out of order, which shows a day's line again, still draws):
 *   - a paper is the photo the family sent (page 1, linking to `hrefFor(id)`), then Medioo's card (TalkCard: the phone's
 *     VerdictCard, the red band and 999 verbatim, outlined when `selectedId` is its paper, with `desk.panel.open`), then,
 *     when it is the newest paper and a retake card, its actions (QuickActions; desktop.html's one `.suggest`, after the
 *     newest paper only);
 *   - a delivered message is its `web_body`, the WhatsApp text without the lines that held a link (Java's), in Medioo's
 *     bubble; a `detail` message (WhatsApp's "details" reply) is left out, because the card and the panel show it, and so
 *     is a message with nothing left to show;
 *   - an ask is the question sent and Java's answer as stored, as the phone draws it (AskAnswer);
 *   - an item of a kind this build does not know is left out, never drawn as a paper;
 * - the composer (variant `desk`: the paperclip for the owner only, the field and send).
 * The column opens on the selected paper's item when there is one, otherwise on the newest (OpenAtNewest), and holds
 * still while the NOW bar is shown (`hold`).
 * Its stylesheets and its client islands (the composer, OpenAtNewest, the tips sheet) come through its loader
 * (desk-load.tsx), so only a page that renders the conversation links them (DR-42).
 */
export function Conversation({
  lang,
  preview,
  patient,
  items,
  selectedId,
  hrefFor,
  hold = false,
}: {
  lang: Lang;
  preview: boolean;
  patient: MePatient;
  items: ThreadItemJson[];
  selectedId: string | null;
  hrefFor: (id: string) => string;
  hold?: boolean;
}) {
  const rows: ReactNode[] = [];
  const newestPaper = items.find((i): i is Extract<ThreadItemJson, { kind: "paper" }> => i.kind === "paper")?.card.id ?? null;
  let day: string | null = null;
  for (const [k, item] of [...items].reverse().entries()) {
    const row = drawn(item, k);
    if (row === null) continue;
    const itemDay = dhakaDay(item.at);
    if (itemDay !== day) {
      rows.push(<DaySeparator key={`d${rows.length}`} lang={lang} at={item.at} />);
      day = itemDay;
    }
    rows.push(row);
  }
  const answered = items.flatMap((i) => (i.kind === "ask" ? [i.turn_id] : []));
  const selected = selectedId !== null && items.some((i) => i.kind === "paper" && i.card.id === selectedId);

  function drawn(item: ThreadItemJson, k: number): ReactNode {
    switch (item.kind) {
      case "paper":
        return (
          <PaperItem
            key={`p${item.card.id}`}
            lang={lang}
            preview={preview}
            paper={item.card}
            at={item.at}
            link={hrefFor(item.card.id)}
            selected={item.card.id === selectedId}
            newest={item.card.id === newestPaper}
          />
        );
      case "message":
        if (item.message_kind === "detail" || item.web_body.trim() === "") return null;
        return (
          <li key={`m${k}`} className="conversation__item" data-talk-item="message" data-at={item.at}>
            <MessageBubble surface="recv">{item.web_body}</MessageBubble>
          </li>
        );
      case "ask":
        return (
          <li key={`a${item.turn_id}`} className="conversation__item conversation__item--ask" data-talk-item="ask" data-at={item.at}>
            <MessageBubble surface="sent">{item.question}</MessageBubble>
            <AskAnswer
              answer={item.answer}
              call999={item.call_999 ? call999Words(lang) : null}
              medicines={item.medicines}
              crop={{ retry: t(lang, "crop.retry"), alt: t(lang, "health.crop.alt") }}
            />
          </li>
        );
      default:
        return null;
    }
  }

  return (
    <div className="conversation" data-patient={patient.id}>
      <ConversationStyles />
      <div className="conversation__head">
        <MioAvatar size={32} still={hold} />
        <div className="conversation__title">
          <b className="conversation__name">{t(lang, "app.title")}</b>
          <small className="conversation__sub">{t(lang, "desk.talk.sub", patient.name_possessive)}</small>
        </div>
      </div>
      <ol className="conversation__items">{rows}</ol>
      <Composer
        variant="desk"
        words={composerWords(lang, patient.name_possessive)}
        owner={patient.owner}
        patientId={patient.id}
        preview={preview}
        answered={answered}
        nojs={
          <noscript>
            <p className="composer__nojs">{composerNoJs(lang)}</p>
          </noscript>
        }
      />
      <OpenAtNewest hold={hold} within={COLUMN} at={selected ? SELECTED : null} />
    </div>
  );
}

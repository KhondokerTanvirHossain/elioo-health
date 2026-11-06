import Link from "next/link";
import type { ReactNode } from "react";
import { isReadingPaper, type PaperJson } from "@/api/types";
import { banglaDigits, t, type Lang } from "@/copy";
import { deleteWords, readingWords } from "@/app/app/(signed-in)/documents/[id]/paper-words";
import { DeletePaper } from "@/components/DeletePaper/DeletePaper";
import { MioAvatar } from "@/components/MioAvatar/MioAvatar";
import { typeText } from "@/components/Paper/paper-text";
import { ReadingProgress } from "@/components/ReadingProgress/ReadingProgress";
import { UrgencyTag } from "@/components/safety/UrgencyTag/UrgencyTag";
import { href } from "@/routing/paths";
import { PaperStrip } from "./PaperStrip";
import { viewerDay } from "./viewer-day";
import { Zoom, type ViewerZoomWords } from "./Zoom";
import "./PaperViewer.css";

const Icon = ({ d }: { d: string }) => (
  <svg className="paper-viewer__icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
    <path d={d} />
  </svg>
);

/** The paper before (older) or after (newer) in a newest-first list: a link, or a link that goes nowhere at either end. */
function Step({ which, target, label, hrefFor }: { which: "prev" | "next"; target: PaperJson | undefined; label: string; hrefFor: (id: string) => string }) {
  const icon = <Icon d={which === "prev" ? "M14.5 6l-6 6 6 6" : "M9.5 6l6 6-6 6"} />;
  if (target === undefined) {
    return (
      <a className="paper-viewer__button" data-step={which} aria-label={label} aria-disabled="true" role="link">
        {icon}
      </a>
    );
  }
  return (
    <Link className="paper-viewer__button" data-step={which} href={hrefFor(target.id)} aria-label={label} title={label} prefetch={false}>
      {icon}
    </Link>
  );
}

/** The ten digits the page writes numbers in: Bangla on bn (the zoom island takes them as a word; it imports no copy). */
const digitsOf = (lang: Lang) => (lang === "bn" ? banglaDigits("0123456789") : "0123456789");

/** No paper yet (desktop.html's first visit): Mio, the invitation, and the way to send the first one. */
function FirstVisit({ lang, preview }: { lang: Lang; preview: boolean }) {
  return (
    <div className="paper-viewer paper-viewer--first">
      <div className="paper-viewer__first">
        <MioAvatar size={96} />
        <h2 className="paper-viewer__first-title">{t(lang, "desk.first.title")}</h2>
        <p className="paper-viewer__first-body">{t(lang, "desk.first.body")}</p>
        <a className="paper-viewer__pick" href={href("/app/add", preview)}>
          <Icon d="M15.5 8.5l-6.4 6.4a1.8 1.8 0 0 0 2.5 2.5l7.1-7.1a3.5 3.5 0 0 0-5-5l-7.4 7.4a5.3 5.3 0 0 0 7.5 7.5l6-6" />
          {t(lang, "desk.first.pick")}
        </a>
      </div>
    </div>
  );
}

/**
 * The paper column of the talk-and-papers screen (desk.nav.talk; plan desktop-parity Task 6; desktop.html `chatView()`'s `.viewer`), from the paper
 * in view and the patient's papers (newest first; the thread's paper cards), and nothing else:
 * - the toolbar: the paper before and after (`hrefFor`, disabled at either end), the paper's type and day (marked when
 *   it is the upload day or unsure, F7), whose paper
 *   and the lab as printed, its stored urgency (UrgencyTag: NOW and THIS_WEEK only; ROUTINE says nothing, D27), zoom
 *   (Zoom), and delete for the owner only (a share member has none, and Java refuses theirs anyway);
 * - the canvas: every page Java stored, page 1 at once and the rest when scrolled to; a paper still being read shows
 *   its pages under the reading line, with the quiet check that moves it on (ReadingProgress), never an empty canvas;
 * - the strip of the patient's papers (PaperStrip).
 * No paper at all: the first-visit invitation, which sends the family to the add page.
 */
export function PaperViewer({
  lang,
  preview,
  paper,
  papers,
  selectedId,
  hrefFor,
}: {
  lang: Lang;
  preview: boolean;
  paper: PaperJson | null;
  papers: PaperJson[];
  selectedId: string | null;
  hrefFor: (id: string) => string;
}) {
  if (paper === null) return <FirstVisit lang={lang} preview={preview} />;

  const reading = isReadingPaper(paper);
  const at = papers.findIndex((p) => p.id === paper.id);
  // Newest first: the paper before this one in time is the next in the list, the one after it the previous.
  const older = at >= 0 ? papers[at + 1] : undefined;
  const newer = at > 0 ? papers[at - 1] : undefined;

  const type = reading ? t(lang, "progress.title") : typeText(lang, paper.document_type);
  const title = reading ? type : `${type} · ${viewerDay(lang, paper)}`;
  const whose = t(lang, "desk.viewer.sub", paper.patient.name_possessive);
  const facility = reading ? null : paper.facility;
  const sub = facility === null || facility.trim() === "" ? whose : `${whose} · ${facility}`;

  const words: ViewerZoomWords = {
    out: t(lang, "desk.viewer.zout"),
    in: t(lang, "desk.viewer.zin"),
    fit: t(lang, "desk.viewer.fit"),
    digits: digitsOf(lang),
  };
  const start: ReactNode = (
    <>
      <Step which="prev" target={older} label={t(lang, "desk.viewer.prev")} hrefFor={hrefFor} />
      <Step which="next" target={newer} label={t(lang, "desk.viewer.next")} hrefFor={hrefFor} />
      <div className="paper-viewer__heading">
        <b className="paper-viewer__title">{title}</b>
        <small className="paper-viewer__sub">{sub}</small>
      </div>
      <span className="paper-viewer__spacer" />
      {!reading && <UrgencyTag level={paper.urgency} lang={lang} />}
    </>
  );
  const end: ReactNode = paper.owner ? (
    <>
      <span className="paper-viewer__sep" aria-hidden="true" />
      <span className="paper-viewer__delete">
        <DeletePaper documentId={paper.id} words={{ ...deleteWords(lang), open: t(lang, "desk.viewer.delete") }} />
      </span>
    </>
  ) : null;
  const check = reading ? { documentId: paper.id, again: hrefFor(paper.id), words: readingWords(lang) } : null;

  return (
    <div className="paper-viewer" data-paper={paper.id}>
      <Zoom words={words} start={start} end={end} canvasLabel={title}>
        {reading && (
          <div className="paper-viewer__reading-box">
            <ReadingProgress check={check} />
            <p className="paper-viewer__reading">{t(lang, "progress.title")}</p>
          </div>
        )}
        {paper.pages.map((p) => (
          // eslint-disable-next-line @next/next/no-img-element -- next/image is banned (Global Constraints)
          <img
            key={p.n}
            className="paper-viewer__page"
            src={p.src}
            alt={t(lang, "detail.page", p.n)}
            loading={p.n === 1 ? undefined : "lazy"}
            decoding="async"
          />
        ))}
      </Zoom>
      <PaperStrip
        lang={lang}
        papers={papers}
        selectedId={selectedId}
        hrefFor={hrefFor}
        label={t(lang, "desk.viewer.strip", paper.patient.name_possessive)}
      />
    </div>
  );
}

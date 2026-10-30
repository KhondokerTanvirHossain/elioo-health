import Link from "next/link";
import type { ReactNode } from "react";
import { t, type CopyKey, type Lang } from "@/copy";
import { isReadingPaper, type PaperJson, type PaperReadingJson, type PaperResultJson, type PaperValueJson } from "@/api/types";
import { DeletePaper } from "@/components/DeletePaper/DeletePaper";
import { MessageBubble } from "@/components/MessageBubble/MessageBubble";
import { PageImage } from "@/components/PageImage/PageImage";
import { POLL_SECONDS } from "@/components/ReadingProgress/poll";
import { ReadingProgress } from "@/components/ReadingProgress/ReadingProgress";
import { NowBlock } from "@/components/safety/NowBlock/NowBlock";
import { VerdictCard } from "@/components/safety/VerdictCard/VerdictCard";
import { href, reachable } from "@/routing/paths";
import { FollowUpQuote, PaperQuote, ValueRow, ValueRows, type From } from "./PaperParts";
import { DoctorLines, Medicines, paperHref, unverified, type PaperWords } from "./PaperShared";
import { metaLine, patientLine, typeText } from "./paper-text";
import { drawsRowsAbove, valueGroups } from "./rows";
import "./Paper.css";

type Ctx = { lang: Lang; preview: boolean; words: PaperWords };

/** This patient's papers (Java's papers page until F8). */
function papersHref(patientId: string, preview: boolean): string {
  return href("/app/timeline", preview) + `?patient=${encodeURIComponent(patientId)}`;
}

function PageLinks({ paper, ctx, label }: { paper: PaperResultJson | PaperReadingJson; ctx: Ctx; label?: (n: number) => string }) {
  return (
    <div className="paper__pages">
      {paper.pages.map((p) => (
        <PageImage key={p.n} src={p.src} words={ctx.words.page(p.n, label?.(p.n))} />
      ))}
    </div>
  );
}

function Group({ id, title, why, children }: { id: string; title: string; why?: string; children: ReactNode }) {
  return (
    <section id={id} className="paper__group">
      <h2 className="paper__heading">{title}</h2>
      {why !== undefined && <p className="paper__why">{why}</p>}
      {children}
    </section>
  );
}

const rows = (values: PaperValueJson[], paper: PaperResultJson, ctx: Ctx, from: From) => (
  <ValueRows values={values} documentId={paper.id} lang={ctx.lang} preview={ctx.preview} from={from} />
);

/** The head every paper page shares: back to this patient's papers, whose paper it is, its title and its meta line. */
function Head({ paper, ctx, title, meta }: { paper: PaperJson; ctx: Ctx; title: string; meta?: string }) {
  return (
    <header className="paper__head">
      <a className="paper__back" href={papersHref(paper.patient.id, ctx.preview)}>
        {t(ctx.lang, "app.back.papers")}
      </a>
      <p className="paper__patient">{patientLine(ctx.lang, paper.patient)}</p>
      <h1 className="paper__title">{title}</h1>
      {meta !== undefined && <p className="paper__meta">{meta}</p>}
    </header>
  );
}

/** Medioo's released words, labelled as such (F3); "a person is checking" only for PENDING; withheld says so. */
function GateLine({ paper, lang }: { paper: PaperResultJson; lang: Lang }) {
  if (paper.gate === "RELEASED" && paper.wording) {
    return (
      <section className="paper__says" aria-label={t(lang, "result.says.label")} data-gate="RELEASED">
        <p className="paper__says-label">{t(lang, "result.says.label")}</p>
        <MessageBubble surface="recv" tail>
          <span data-wording>{paper.wording}</span>
        </MessageBubble>
        <p className="paper__note">{t(lang, "result.says.note")}</p>
      </section>
    );
  }
  if (paper.gate === "PENDING") {
    return (
      <section className="paper__gate" data-gate="PENDING">
        <span className="paper__review">{t(lang, "result.review.chip")}</span>
        <p className="paper__line">{t(lang, "result.gate.pending", paper.review_hours ?? 0)}</p>
      </section>
    );
  }
  if (paper.gate === "REJECTED" || paper.gate === "FAILED_SAFETY") {
    return (
      <section className="paper__gate" data-gate={paper.gate}>
        <p className="paper__line">{t(lang, "result.gate.withheld")}</p>
      </section>
    );
  }
  return null;
}

/** The result summary (DocumentPages.summaryBody, s10/s11). */
function Result({ paper, ctx }: { paper: PaperResultJson; ctx: Ctx }) {
  const { lang, preview } = ctx;
  const above = drawsRowsAbove(paper);
  const g = valueGroups(paper.values.filter((v) => !(above && v.in_now_block)));
  const missing = unverified(paper, "values");
  const bring = paper.banner !== null;
  const followMissing = unverified(paper, "follow_up");
  const chat = "/app/home";
  return (
    <>
      <Head paper={paper} ctx={ctx} title={typeText(lang, paper.document_type)} meta={metaLine(lang, paper.date, paper.date_is_upload_day, paper.facility)} />
      <VerdictCard paper={paper} lang={lang} wording={false} />
      {paper.filed && <p className="paper__meta">{t(lang, "result.filed")}</p>}
      {paper.now && (
        <NowBlock
          paper={paper}
          now={paper.now}
          lang={lang}
          proof={{
            row: (v) => <ValueRow value={v} documentId={paper.id} lang={lang} preview={preview} from="summary" />,
            followUp: (f) => <FollowUpQuote line={f} documentId={paper.id} lang={lang} />,
            quote: (l) => <PaperQuote text={l.text} crop={l.crop} documentId={paper.id} lang={lang} />,
            pages: <PageLinks paper={paper} ctx={ctx} label={(n) => t(lang, "now.page", n)} />,
            outsideHref: g.outside.length > 0 ? "#outside" : null,
          }}
        />
      )}
      <GateLine paper={paper} lang={lang} />
      {missing > 0 && <p className="paper__note">{t(lang, "value.unverified")}</p>}
      {g.outside.length > 0 && <Group id="outside" title={t(lang, "value.group.outside")}>{rows(g.outside, paper, ctx, "summary")}</Group>}
      {g.unknown.length > 0 && (
        <Group id="unknown" title={t(lang, "value.group.unknown")} why={t(lang, "value.group.unknown.why")}>
          {rows(g.unknown, paper, ctx, "summary")}
        </Group>
      )}
      {g.inside.length > 0 && (
        <Group id="inside" title={t(lang, "value.group.inside")}>
          {rows(g.inside.slice(0, 1), paper, ctx, "summary")}
          {g.inside.length > 1 && (
            <Link className="paper__more" href={paperHref(paper.id, preview, "/all") + "#values"} prefetch={false}>
              {t(lang, "value.more.inside", g.inside.length - 1)}
            </Link>
          )}
        </Group>
      )}
      {Object.keys(paper.doctor).length > 0 && (
        <section id="doctor" className="paper__group">
          <h2 className="paper__heading">{t(lang, "doctor.title")}</h2>
          <DoctorLines paper={paper} ctx={ctx} />
        </section>
      )}
      <Medicines paper={paper} ctx={ctx} />
      {(bring || paper.follow_up.length > 0 || followMissing > 0) && (
        <section id="next" className="paper__group">
          <h2 className="paper__heading">{t(lang, "next.title")}</h2>
          {bring && <p className="paper__line">{t(lang, "next.bring")}</p>}
          {paper.follow_up.map((f, i) => (
            <FollowUpQuote key={i} line={f} documentId={paper.id} lang={lang} />
          ))}
          {followMissing > 0 && <p className="paper__note">{t(lang, "next.unverified", followMissing)}</p>}
        </section>
      )}
      <section id="pages" className="paper__group">
        <h2 className="paper__heading">{t(lang, "doc.pages")}</h2>
        <PageLinks paper={paper} ctx={ctx} />
      </section>
      <nav className="paper__actions">
        <Link className="paper__action" href={paperHref(paper.id, preview, "/all")} prefetch={false}>
          {t(lang, "result.all")}
        </Link>
        {reachable(chat, preview) && (
          <a className="paper__action" href={href(chat, preview)}>
            {t(lang, "result.ask")}
          </a>
        )}
      </nav>
      {paper.owner && <DeletePaper documentId={paper.id} words={ctx.words.delete} />}
    </>
  );
}

/** The retake card (DocumentPages.retakeBody, s12): never red, never amber; the reason in words, the photo sent, tips. */
function Retake({ paper, ctx }: { paper: PaperResultJson; ctx: Ctx }) {
  const { lang, preview } = ctx;
  const retake = paper.retake!;
  const third = retake.ordinal >= 3;
  const attempt = retake.ordinal === 3 ? t(lang, "retake.meta.third") : retake.ordinal > 3 ? t(lang, "retake.meta.nth", retake.ordinal) : null;
  const meta = [t(lang, "retake.meta", metaLine(lang, paper.date, false, null), paper.page_count), attempt].filter(Boolean).join(" · ");
  const add = (replaces: boolean) =>
    href("/app/add", preview) +
    `?patient=${encodeURIComponent(paper.patient.id)}` +
    (replaces ? `&replaces=${encodeURIComponent(paper.id)}` : "") +
    `&back=${encodeURIComponent(`/app/documents/${paper.id}`)}`;
  const tips: CopyKey[] = retake.ordinal >= 2 ? ["retake.tip.1", "retake.tip.2", "retake.tip.3"] : ["retake.tip.1", "retake.tip.2"];
  return (
    <>
      <Head paper={paper} ctx={ctx} title={t(lang, third ? "retake.title.third" : "retake.title")} meta={meta} />
      <section id="retake" className="paper__card">
        <p className="paper__lead">{t(lang, "retake.head")}</p>
        <p className="paper__line">{t(lang, retake.reason_key)}</p>
        <PageLinks paper={paper} ctx={ctx} label={(n) => t(lang, "retake.photo", n)} />
        {third ? (
          <p className="paper__line">{t(lang, "retake.third.p")}</p>
        ) : (
          <>
            <h2 className="paper__heading">{t(lang, "retake.tips")}</h2>
            <ul className="paper__tips">
              {tips.map((k) => (
                <li key={k}>{t(lang, k)}</li>
              ))}
            </ul>
          </>
        )}
        {!paper.owner ? (
          <p className="paper__meta">{t(lang, "retake.viewonly")}</p>
        ) : (
          <div className="paper__actions">
            {!retake.replaced && (
              <a className="paper__action" href={add(true)} data-primary={third ? undefined : ""}>
                {t(lang, third ? "retake.again.same" : "retake.again")}
              </a>
            )}
            <a className="paper__action" href={add(false)} data-primary={third || retake.replaced ? "" : undefined}>
              {t(lang, "retake.other")}
            </a>
          </div>
        )}
        {(third || !paper.owner) && (
          <a className="paper__action" href={papersHref(paper.patient.id, preview)}>
            {t(lang, "retake.later")}
          </a>
        )}
        <p className="paper__note">{t(lang, "retake.free")}</p>
        <p className="paper__note">{t(lang, "retake.nothing_kept")}</p>
      </section>
      {paper.owner && <DeletePaper documentId={paper.id} words={ctx.words.delete} />}
    </>
  );
}

/** FAILED (DocumentPages.failedBody): our side, said honestly, never a retake's tips and never the stored error. */
function Failed({ paper, ctx }: { paper: PaperResultJson; ctx: Ctx }) {
  const { lang, preview } = ctx;
  const again =
    href("/app/add", preview) +
    `?patient=${encodeURIComponent(paper.patient.id)}&back=${encodeURIComponent(`/app/documents/${paper.id}`)}`;
  return (
    <>
      <Head paper={paper} ctx={ctx} title={t(lang, "failed.title")} />
      <section id="failed" className="paper__card">
        <p className="paper__line">{t(lang, "failed.p")}</p>
        {paper.owner ? (
          <a className="paper__action" href={again} data-primary="">
            {t(lang, "failed.again")}
          </a>
        ) : (
          <p className="paper__meta">{t(lang, "retake.viewonly")}</p>
        )}
        <p className="paper__note">{t(lang, "failed.free")}</p>
      </section>
      {paper.owner && <DeletePaper documentId={paper.id} words={ctx.words.delete} />}
    </>
  );
}

/**
 * A paper still being read (DocumentPages.progressBody): three real steps, nothing about a result, and the poll. With
 * JavaScript, ReadingProgress asks the status quietly and draws the result once, with no reload; without it, the page
 * reloads itself every POLL_SECONDS (`<noscript>`, inert wherever script runs), as Java's reading page does, so a family
 * whose browser runs no script still moves on to the result (PO ruling P6). Each such reload is one read of the paper,
 * as each of Java's own reloads is.
 */
function Reading({ paper, ctx }: { paper: PaperReadingJson; ctx: Ctx }) {
  const { lang, preview } = ctx;
  const received = paper.status === "RECEIVED";
  return (
    <section id="progress" className="paper__card" data-status={paper.status}>
      <p className="paper__patient">{patientLine(lang, paper.patient)}</p>
      <h1 className="paper__title">{t(lang, "progress.title")}</h1>
      <p className="paper__meta">{t(lang, "progress.sent", paper.page_count)}</p>
      <ol className="paper__steps" aria-live="polite">
        <li className={received ? "paper__step paper__step--current" : "paper__step paper__step--done"} aria-current={received ? "step" : undefined}>
          {t(lang, "progress.step.received")}
        </li>
        <li className={received ? "paper__step" : "paper__step paper__step--current"} aria-current={received ? undefined : "step"}>
          {t(lang, "progress.step.processing")}
          {!received && <span className="paper__step-note">{t(lang, "progress.step.processing.note")}</span>}
        </li>
        <li className="paper__step">
          {t(lang, "progress.step.done")}
          <span className="paper__step-note">{t(lang, "progress.step.done.note")}</span>
        </li>
      </ol>
      <p className="paper__line">{t(lang, "progress.leave")}</p>
      <a className="paper__action" href={papersHref(paper.patient.id, preview)}>
        {t(lang, "progress.timeline")}
      </a>
      <ReadingProgress documentId={paper.id} />
      <noscript>
        <meta httpEquiv="refresh" content={String(POLL_SECONDS)} />
      </noscript>
    </section>
  );
}

/**
 * One paper's page (plan W6), from GET /app/api/documents/{id} and nothing else, by the status Java sent: the result
 * (DONE), the retake card (NEEDS_RETAKE), the failed page (FAILED), or the reading page (RECEIVED, PROCESSING).
 * Delete and every upload entry only for the owner; a share member is told why.
 */
export function PaperSummary({ paper, lang, preview, words }: { paper: PaperJson; lang: Lang; preview: boolean; words: PaperWords }) {
  const ctx: Ctx = { lang, preview, words };
  if (isReadingPaper(paper)) return <article className="paper"><Reading paper={paper} ctx={ctx} /></article>;
  return (
    <article className="paper" data-status={paper.status}>
      {paper.status === "NEEDS_RETAKE" && paper.retake ? (
        <Retake paper={paper} ctx={ctx} />
      ) : paper.status === "FAILED" ? (
        <Failed paper={paper} ctx={ctx} />
      ) : (
        <Result paper={paper} ctx={ctx} />
      )}
    </article>
  );
}


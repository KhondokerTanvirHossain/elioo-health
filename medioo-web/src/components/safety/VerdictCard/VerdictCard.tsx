import type { ReactNode } from "react";
import { t, type CopyKey, type Lang } from "@/copy";
import type { BannerLevel, PaperJson, PaperStatus } from "@/api/types";
import "./VerdictCard.css";

/** The band's class, one to one with the banner level Java sent. Nothing else in the paper chooses it. */
const BAND_CLASS: Record<BannerLevel, string> = {
  now: "verdict-card--now",
  this_week: "verdict-card--week",
};

/** The band's shape, so the urgency is never colour alone: an octagon for NOW, a calendar for this week. */
const BAND_SHAPE: Record<BannerLevel, ReactNode> = {
  now: (
    <>
      <path d="M8.2 3h7.6L21 8.2v7.6L15.8 21H8.2L3 15.8V8.2z" />
      <path d="M12 7.5v5.5M12 16.2v.01" />
    </>
  ),
  this_week: (
    <>
      <rect x="3.5" y="5" width="17" height="15" rx="2.5" />
      <path d="M3.5 10h17M8 3v4M16 3v4M12 13v3" />
    </>
  ),
};

/**
 * The title of a paper Java sent no counts line for (still being read, a retake, a failed read, a DONE paper with no
 * values): the word the HTML pages show for that status, so no card is an empty bubble. Exhaustive over the statuses.
 */
const STATUS_TITLE: Record<PaperStatus, CopyKey> = {
  RECEIVED: "progress.step.received",
  PROCESSING: "progress.step.processing",
  DONE: "status.DONE",
  NEEDS_RETAKE: "retake.title",
  FAILED: "failed.title",
};

/**
 * Medioo's answer to one paper, as Java decided it (PaperJson.java). Every safety line comes from a field Java sent and
 * none is derived here:
 * - the band from `banner` alone (`.verdict-card--now` only for level "now", `.verdict-card--week` for "this_week",
 *   none otherwise), its words from `banner.line_key`; never from the values' chips;
 * - the wording only when `gate` is RELEASED (F3), whatever else the body holds;
 * - the not-a-doctor line from `now.notdoctor_key`, and the tel:999 link only when `now.call_999` is true (F7), its
 *   label the fixed key `now.call999`, which that flag alone gates;
 * - the title from `counts.line_key`, or the status's word when Java sent no counts line.
 *
 * `wording` false leaves the wording out (the paper page, W6, shows it in its own labelled bubble below, so it is not
 * said twice); it never shows a wording the gate withheld.
 */
export function VerdictCard({ paper, lang, wording: withWording = true }: { paper: PaperJson; lang: Lang; wording?: boolean }) {
  const banner = paper.banner ?? null;
  const className = ["verdict-card"];
  if (banner) className.push(BAND_CLASS[banner.level]);
  const counts = paper.counts ?? null;
  const title = counts ? t(lang, counts.line_key, counts.values, counts.outside) : t(lang, STATUS_TITLE[paper.status]);
  const wording = withWording && paper.gate === "RELEASED" && paper.wording ? paper.wording : null;
  const now = paper.now ?? null;
  return (
    <article className={className.join(" ")}>
      {banner && (
        <p className="verdict-card__band">
          <svg className="verdict-card__shape" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            {BAND_SHAPE[banner.level]}
          </svg>
          {t(lang, banner.line_key)}
        </p>
      )}
      <div className="verdict-card__meta">
        <p className="verdict-card__title">{title}</p>
        {paper.retake && <p className="verdict-card__reason">{t(lang, paper.retake.reason_key)}</p>}
        {wording && <p className="verdict-card__wording">{wording}</p>}
        {now && <p className="verdict-card__notdoctor">{t(lang, now.notdoctor_key)}</p>}
        {now?.call_999 === true && (
          <a className="verdict-card__call" href="tel:999">
            {t(lang, "now.call999")}
          </a>
        )}
      </div>
    </article>
  );
}

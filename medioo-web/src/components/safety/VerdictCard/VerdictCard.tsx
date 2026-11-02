import type { ReactNode } from "react";
import { t, type CopyKey, type Lang } from "@/copy";
import type { BannerLevel, PaperJson, PaperStatus, PaperValueJson } from "@/api/types";
import { MioAvatar } from "@/components/MioAvatar/MioAvatar";
import { CHIP_KEY, CHIP_KINDS, StatusChip, type ChipKind } from "@/components/StatusChip/StatusChip";
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

/** The fixed sentence of a stored level, as Java's UiCopy.urgency(lang, level, false) reads it from messages_{bn,en}. */
const LEVEL_LINE: Record<BannerLevel, CopyKey> = {
  now: "urgency.now",
  this_week: "urgency.this_week",
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

/** The chip kind Java named, read back from its `chip_key` (PaperDisplay.chipKey); a key this table does not know is unknown. */
const KIND_OF_KEY = new Map<string, ChipKind>(CHIP_KINDS.map((kind) => [CHIP_KEY[kind], kind]));
const chipOf = (value: PaperValueJson): ChipKind => KIND_OF_KEY.get(value.chip_key) ?? "unk";

/** How many value rows the thread's card shows: the approved card's (phone.html verdictCard, `p.values.slice(0, 3)`). */
export const THREAD_ROWS = 3;

/**
 * The rows the thread's card shows, at most THREAD_ROWS: the values Java put in the paper's NOW block (`in_now_block`)
 * first, then the rest, each group in the order Java sent it. Nothing is ranked here: `pin` is the range bar's position
 * (PaperDisplay.pin, 0–100), not an importance, so it orders nothing. The paper page shows every value.
 */
export function threadRows(values: PaperValueJson[]): PaperValueJson[] {
  return [...values.filter((v) => v.in_now_block), ...values.filter((v) => !v.in_now_block)].slice(0, THREAD_ROWS);
}

/** The band: the level's shape and its sentence, never colour alone. */
function Band({ level, line }: { level: BannerLevel; line: string }) {
  return (
    <p className="verdict-card__band">
      <svg className="verdict-card__shape" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        {BAND_SHAPE[level]}
      </svg>
      {line}
    </p>
  );
}

/**
 * Medioo's answer to one paper, as Java decided it (PaperJson.java). Every safety line comes from a field Java sent and
 * none is derived here:
 * - the band from `banner` alone (`.verdict-card--now` only for level "now", `.verdict-card--week` for "this_week",
 *   none otherwise), its words from `banner.line_key`; never from the values' chips;
 * - the wording only when `gate` is RELEASED (F3), whatever else the body holds; while it is PENDING, the line that a
 *   person is checking it, with Java's `review_hours`;
 * - the not-a-doctor line from `now.notdoctor_key`, and the tel:999 link only when `now.call_999` is true (F7), its
 *   label the fixed key `now.call999`, which that flag alone gates;
 * - the title from `counts.line_key`, or the status's word when Java sent no counts line.
 *
 * The thread's card (plan W5, the amendment to W2d-a in PR 127) adds, when its caller passes them: `link`, the paper the card opens
 * (the title is the link, and the whole card answers a tap); `mio`, Mio beside it, still, as on every NOW surface; and,
 * on a card with a `link` whose paper Java sent a banner for, up to THREAD_ROWS values as rows (threadRows): its own
 * crop, its name and reading as printed, and the chip Java named (`chip_key`) through StatusChip. The rest are the
 * paper's. A routine card lists no value: the paper does.
 *
 * The not-a-doctor line and the 999 link come straight under the band and the title, before any row, so a NOW card is
 * read in that order however many values the paper printed.
 *
 * `wording` false leaves the wording out (the paper page, W6, shows it in its own labelled bubble below, so it is not
 * said twice); it never shows a wording the gate withheld.
 */
export function VerdictCard({
  paper,
  lang,
  link,
  mio,
  wording: withWording = true,
}: {
  paper: PaperJson;
  lang: Lang;
  link?: string;
  mio?: boolean;
  wording?: boolean;
}) {
  const banner = paper.banner ?? null;
  const className = ["verdict-card"];
  if (banner) className.push(BAND_CLASS[banner.level]);
  if (link !== undefined) className.push("verdict-card--link");
  const counts = paper.counts ?? null;
  const title = counts ? t(lang, counts.line_key, counts.values, counts.outside) : t(lang, STATUS_TITLE[paper.status]);
  const wording = withWording && paper.gate === "RELEASED" && paper.wording ? paper.wording : null;
  const pending = paper.gate === "PENDING" && typeof paper.review_hours === "number" ? paper.review_hours : null;
  const now = paper.now ?? null;
  const values = link !== undefined && banner && paper.values ? threadRows(paper.values) : [];
  const card = (
    <article className={className.join(" ")}>
      {banner && <Band level={banner.level} line={t(lang, banner.line_key)} />}
      <div className="verdict-card__meta">
        <p className="verdict-card__title">
          {link === undefined ? (
            title
          ) : (
            <a className="verdict-card__open" href={link}>
              {title}
            </a>
          )}
        </p>
        {now && <p className="verdict-card__notdoctor">{t(lang, now.notdoctor_key)}</p>}
        {now?.call_999 === true && (
          <a className="verdict-card__call" href="tel:999">
            {t(lang, "now.call999")}
          </a>
        )}
        {paper.retake && <p className="verdict-card__reason">{t(lang, paper.retake.reason_key)}</p>}
        {values.length > 0 && (
          <ul className="verdict-card__values">
            {values.map((v, k) => (
              <li key={v.id ?? k} className="verdict-card__value" data-value-row>
                {/* eslint-disable-next-line @next/next/no-img-element -- next/image is banned (Global Constraints) */}
                <img
                  className="verdict-card__crop"
                  src={v.crop.src}
                  alt=""
                  width={v.crop.width ?? undefined}
                  height={v.crop.height ?? undefined}
                />
                <span className="verdict-card__reading">
                  <span className="verdict-card__name">{v.name}</span>
                  <span className="verdict-card__number">{[v.value, v.unit].filter(Boolean).join(" ")}</span>
                </span>
                <StatusChip kind={chipOf(v)} lang={lang} />
              </li>
            ))}
          </ul>
        )}
        {pending !== null && <p className="verdict-card__pending">{t(lang, "result.gate.pending", pending)}</p>}
        {wording && <p className="verdict-card__wording">{wording}</p>}
      </div>
    </article>
  );
  if (!mio) return card;
  return (
    <div className="verdict-card__beside">
      <MioAvatar size={32} still />
      {card}
    </div>
  );
}

/**
 * A card for a stored NOW or THIS_WEEK level that is not a whole paper (the attention stack's urgent card, plan W5): the
 * band a paper with that level carries, its shape and its fixed sentence (Java's ChatView words it with UiCopy.urgency),
 * over the caller's lines. A level this file does not know gets no band, never a guessed one. A NOW card also says
 * `now.notdoctor`, as a NOW paper's card does from `now.notdoctor_key`: the stack can show a NOW result for a paper the
 * thread's first page does not reach, and every page that shows a result says it is not a doctor.
 */
export function UrgencyCard({ level, lang, children }: { level: string; lang: Lang; children: ReactNode }) {
  const known = Object.hasOwn(BAND_CLASS, level) ? (level as BannerLevel) : null;
  return (
    <article className={known ? `verdict-card ${BAND_CLASS[known]}` : "verdict-card"}>
      {known && <Band level={known} line={t(lang, LEVEL_LINE[known])} />}
      <div className="verdict-card__meta">
        {children}
        {known === "now" && <p className="verdict-card__notdoctor">{t(lang, "now.notdoctor")}</p>}
      </div>
    </article>
  );
}

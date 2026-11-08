import Link from "next/link";
import type { AnchorHTMLAttributes, ReactNode } from "react";
import type { BpPointJson, LabSeriesJson, LabsJson, TrendPointJson } from "@/api/types";
import { t, type Lang } from "@/copy";
import { paperDate } from "@/components/LabList/LabList";
import { rangeText } from "@/components/Paper/paper-text";
import { StatusChip, chipKindOf } from "@/components/StatusChip/StatusChip";
import { TrendChart, chartable } from "@/components/TrendChart/TrendChart";
import { DeskLabsStyles } from "./desk-load";

export type DeskLabsProps = {
  lang: Lang;
  /** `GET /app/api/patients/{id}/labs` as Java sent it. */
  labs: LabsJson;
  /** The value the address asks for (`?lab=`): a reading of a series (any of its observation ids), or `bp`. */
  asked: string | null;
  /** This screen's address for a value (the page's own, kept inside the preview). */
  at: (lab: string) => string;
  /** Next answers this screen's links in place (a soft navigation); else they are plain links. */
  soft: boolean;
  /** The talk-and-papers screen showing a paper. */
  open: (documentId: string) => string;
  /** Where a paper is sent from, when a page takes one for this viewer; null draws no link. */
  send: string | null;
};

/** The key a value is chosen by: its series' newest reading, or `bp` for the blood-pressure pairs. */
const BP = "bp";
const keyOf = (s: LabSeriesJson) => s.points[0]!.observation_id;

/**
 * The value the screen shows: the series holding the asked reading (any of its readings, so a link made before a newer
 * paper arrived still opens it), the blood pressure when `bp` is asked and there is one; else the first value in Java's
 * order; null when nothing is read. Returned as its key (keyOf, or `bp`).
 */
export function chosenLab(labs: LabsJson, asked: string | null): string | null {
  const series = labs.series.filter((s) => s.points.length > 0);
  const hasBp = labs.bp.points.length > 0;
  if (asked === BP && hasBp) return BP;
  const holding = asked === null ? undefined : series.find((s) => s.points.some((p) => p.observation_id === asked));
  if (holding) return keyOf(holding);
  if (series.length > 0) return keyOf(series[0]!);
  return hasBp ? BP : null;
}

/**
 * A bound as its paper printed it: the number in the paper's own digits when `printed` (Java's `ref_text`) holds a
 * number equal to it ("4.0" for 4), else the number as Java sent it. Only the digits are taken, never a bound: `ref_text`
 * is not written (DR-48, PR 143), and a printed number that is not this one ("40" for 4) is never used.
 */
export function boundText(n: number, printed: string | null): string {
  const same = (printed ?? "").match(/\d+(?:\.\d+)?/g)?.find((x) => Number(x) === n);
  return same ?? String(n);
}

/**
 * The range a reading's paper printed, in the paper page's words (`value.range`, `value.range.one`), from the numbers
 * Java kept for it (PaperDisplay.drawnBand: only where they agree with its chip); null when Java kept none, so nothing
 * is said rather than "no range printed" over a paper that printed one Medioo could not place.
 */
function rangeOf(lang: Lang, p: TrendPointJson): string | null {
  if (p.ref_low === null && p.ref_high === null) return null;
  return rangeText(lang, {
    ref_low: p.ref_low === null ? null : boundText(p.ref_low, p.ref_text),
    ref_high: p.ref_high === null ? null : boundText(p.ref_high, p.ref_text),
  });
}

/** A link of this screen: in place where Next answers it (`soft`), else a plain one. Never prefetched: one read per visit. */
function Go({ soft, href, children, ...rest }: { soft: boolean; href: string } & Omit<AnchorHTMLAttributes<HTMLAnchorElement>, "href">) {
  return soft ? (
    <Link href={href} prefetch={false} scroll={false} {...rest}>
      {children}
    </Link>
  ) : (
    <a href={href} {...rest}>
      {children}
    </a>
  );
}

function Chevron() {
  return (
    <svg className="desk-labs__chev" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d="M9.5 6l6 6-6 6" />
    </svg>
  );
}

/** One value in the list: its name and newest paper's date (and on how many papers), its newest reading, unit and chip. */
function SeriesRow({ lang, s, current, href, soft }: { lang: Lang; s: LabSeriesJson; current: boolean; href: string; soft: boolean }) {
  const p = s.points[0]!;
  return (
    <Go soft={soft} href={href} className="desk-labs__row" data-lab={keyOf(s)} aria-current={current ? "true" : undefined}>
      <span className="desk-labs__nm">
        {s.name}
        <small className="desk-labs__when">
          {paperDate(lang, p)}
          {s.points.length > 1 && ` · ${t(lang, "desk.labs.papers", s.points.length)}`}
        </small>
      </span>
      <span className="desk-labs__vv">
        <span className="desk-labs__v">
          {p.value}
          {s.unit !== null && <small className="desk-labs__u">{` ${s.unit}`}</small>}
        </span>
        <StatusChip kind={chipKindOf(p.kind, p.side)} lang={lang} />
      </span>
    </Go>
  );
}

/** The blood pressure in the list: its newest pair, each half with its own chip. */
function BpRow({ lang, pair, count, current, href, soft }: { lang: Lang; pair: BpPointJson; count: number; current: boolean; href: string; soft: boolean }) {
  return (
    <Go soft={soft} href={href} className="desk-labs__row" data-lab={BP} aria-current={current ? "true" : undefined}>
      <span className="desk-labs__nm">
        {t(lang, "health.bp")}
        <small className="desk-labs__when">
          {paperDate(lang, pair)}
          {count > 1 && ` · ${t(lang, "desk.labs.papers", count)}`}
        </small>
      </span>
      <span className="desk-labs__vv">
        <span className="desk-labs__v">{`${pair.systolic.value}/${pair.diastolic.value}`}</span>
        <span className="desk-labs__chips">
          <StatusChip kind={chipKindOf(pair.systolic.kind, pair.systolic.side)} lang={lang} />
          <StatusChip kind={chipKindOf(pair.diastolic.kind, pair.diastolic.side)} lang={lang} />
        </span>
      </span>
    </Go>
  );
}

/** A panel of the detail (desktop.html's `.panel`): its heading, then what it holds. */
function Panel({ name, title, children }: { name: string; title: string; children: ReactNode }) {
  return (
    <section className="desk-labs__panel" data-panel={name}>
      <h3 className="desk-labs__ph">{title}</h3>
      {children}
    </section>
  );
}

/** A reading's crop as a picture of its line, named as such; the value itself is said by the text beside it. */
function CropImage({ lang, p }: { lang: Lang; p: TrendPointJson }) {
  // eslint-disable-next-line @next/next/no-img-element -- next/image is banned (Global Constraints); Java serves the crop
  return <img className="desk-labs__crop" src={p.crop.src} alt={t(lang, "health.crop.alt")} decoding="async" loading="lazy" />;
}

/** "As written on the paper": one link per paper, newest first, its crops then its date, to that paper and its conversation. */
function Source({ lang, documentId, points, date, href }: { lang: Lang; documentId: string; points: TrendPointJson[]; date: string; href: string }) {
  return (
    <a className="desk-labs__src" href={href} data-observation={points[0]!.observation_id} data-document={documentId}>
      <span className="desk-labs__src-body">
        {points.map((p) => (
          <CropImage key={p.observation_id} lang={lang} p={p} />
        ))}
        <small className="desk-labs__src-date">{date}</small>
      </span>
      <Chevron />
    </a>
  );
}

/** One value chosen: the figure, the chip, the date and range, over time, and each reading as written. */
function SeriesDetail({ lang, s, open }: { lang: Lang; s: LabSeriesJson; open: (id: string) => string }) {
  const p = s.points[0]!;
  const id = `desk-lab-${p.observation_id}`;
  const range = rangeOf(lang, p);
  let over: ReactNode;
  if (s.points.length === 1) {
    over = <p className="desk-labs__note">{t(lang, "desk.labs.once")}</p>;
  } else if (chartable(s.points)) {
    over = (
      <>
        <TrendChart series={s} lang={lang} labelledBy={id} />
        <p className="desk-labs__foot">{t(lang, "desk.labs.foot")}</p>
      </>
    );
  } else {
    // A reading printed as a bound (">500") is not drawn on a line: each reading as written, with its own chip.
    over = (
      <ol className="desk-labs__readings" aria-labelledby={id}>
        {s.points.map((r) => (
          <li key={r.observation_id} className="desk-labs__reading" data-reading-for={r.observation_id}>
            <span className="desk-labs__reading-date">{paperDate(lang, r)}</span>
            <span className="desk-labs__reading-v">
              {r.value}
              {s.unit !== null && <small className="desk-labs__u">{` ${s.unit}`}</small>}
            </span>
            <StatusChip kind={chipKindOf(r.kind, r.side)} lang={lang} />
          </li>
        ))}
      </ol>
    );
  }
  return (
    <section className="desk-labs__detail" aria-labelledby={id}>
      <h2 className="desk-labs__name" id={id}>
        {s.name}
        {s.unit !== null && ` ${s.unit}`}
      </h2>
      <p className="desk-labs__figure">
        {p.value}
        {s.unit !== null && <small className="desk-labs__figure-u">{s.unit}</small>}
      </p>
      <p className="desk-labs__sub">
        <StatusChip kind={chipKindOf(p.kind, p.side)} lang={lang} />
        <span>
          {t(lang, "desk.labs.latest", paperDate(lang, p))}
          {range !== null && ` · ${range}`}
        </span>
      </p>
      <Panel name="over-time" title={t(lang, "desk.labs.over_time")}>
        {over}
      </Panel>
      <Panel name="as-written" title={t(lang, "desk.labs.as_written")}>
        {s.points.map((r) => (
          <Source key={r.observation_id} lang={lang} documentId={r.document_id} points={[r]} date={paperDate(lang, r)} href={open(r.document_id)} />
        ))}
      </Panel>
    </section>
  );
}

/** One half of a pair: its value beside its own chip, in one group carrying the half's observation id. */
function Half({ lang, p }: { lang: Lang; p: TrendPointJson }) {
  return (
    <span className="desk-labs__half" data-observation-id={p.observation_id}>
      <span className="desk-labs__reading-v">{p.value}</span>
      <StatusChip kind={chipKindOf(p.kind, p.side)} lang={lang} />
    </span>
  );
}

/** The blood pressure chosen: the newest pair as the figure, every pair over time, each pair's two crops as written. */
function BpDetail({ lang, pairs, open }: { lang: Lang; pairs: BpPointJson[]; open: (id: string) => string }) {
  const newest = pairs[0]!;
  const id = "desk-lab-bp";
  return (
    <section className="desk-labs__detail" aria-labelledby={id}>
      <h2 className="desk-labs__name" id={id}>
        {t(lang, "health.bp")}
      </h2>
      <p className="desk-labs__figure">{`${newest.systolic.value}/${newest.diastolic.value}`}</p>
      <p className="desk-labs__sub">
        <span>{t(lang, "desk.labs.latest", paperDate(lang, newest))}</span>
      </p>
      <Panel name="over-time" title={t(lang, "desk.labs.over_time")}>
        {pairs.length === 1 && <p className="desk-labs__note">{t(lang, "desk.labs.once")}</p>}
        <ol className="desk-labs__readings" aria-labelledby={id}>
          {pairs.map((pair) => (
            <li key={pair.systolic.observation_id} className="desk-labs__reading" data-reading-for={pair.systolic.observation_id}>
              <span className="desk-labs__reading-date">{paperDate(lang, pair)}</span>
              <span className="desk-labs__halves">
                <Half lang={lang} p={pair.systolic} />
                <span className="desk-labs__slash">/</span>
                <Half lang={lang} p={pair.diastolic} />
              </span>
            </li>
          ))}
        </ol>
      </Panel>
      <Panel name="as-written" title={t(lang, "desk.labs.as_written")}>
        {pairs.map((pair) => (
          <Source
            key={pair.systolic.observation_id}
            lang={lang}
            documentId={pair.document_id}
            points={[pair.systolic, pair.diastolic]}
            date={paperDate(lang, pair)}
            href={open(pair.document_id)}
          />
        ))}
      </Panel>
    </section>
  );
}

/**
 * The lab values on the desktop layout (plan desktop-parity Phase 2, row L1; desktop.html `labsView()`): one screen of
 * the shell's single column (DesktopShell with no conversation).
 * - the list (340 px, 300 px from 900 to 1199): every value in Java's order (newest paper first; never re-ordered or
 *   joined here: Java keys a series by canonical name AND unit), then the blood pressure; each a link to itself, its name,
 *   its newest paper's date and on how many papers, its newest reading with its unit and chip;
 * - the chosen value (chosenLab): its newest reading as a figure, its chip, its date and the range its paper printed;
 *   "over time" (TrendChart when every reading is a plain number, one reading's sentence, or the readings as written);
 *   and "as written on the paper", each reading's own crop, a link to the talk-and-papers screen on that paper.
 * Nothing read yet: desktop.html's empty page under the screen's heading, with the way to send a paper. Every link is plain HTML, so the screen
 * reads and moves without JavaScript. Its stylesheet comes through its loader (desk-load.tsx), so only a page that
 * renders it links it (DR-42).
 */
export function DeskLabs({ lang, labs, asked, at, soft, open, send }: DeskLabsProps) {
  const chosen = chosenLab(labs, asked);
  if (chosen === null) {
    return (
      <div className="desk-labs desk-labs--none">
        <DeskLabsStyles />
        <h1 className="desk-labs__title desk-labs__title--bar">{t(lang, "desk.nav.labs")}</h1>
        <div className="desk-labs__empty">
          <span className="desk-labs__empty-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" focusable="false">
              <path d="M9 3h6M10 3v6L4.5 18.5A1.7 1.7 0 0 0 6 21h12a1.7 1.7 0 0 0 1.5-2.5L14 9V3" />
            </svg>
          </span>
          <h2 className="desk-labs__empty-title">{t(lang, "health.labs.none")}</h2>
          <p className="desk-labs__empty-text">{t(lang, "desk.labs.none.more")}</p>
          {send !== null && (
            <a className="desk-labs__send" href={send}>
              {t(lang, "desk.attach")}
            </a>
          )}
        </div>
      </div>
    );
  }
  const series = labs.series.filter((s) => s.points.length > 0);
  const pairs = labs.bp.points;
  const current = chosen === BP ? null : series.find((s) => keyOf(s) === chosen)!;
  return (
    <div className="desk-labs">
      <DeskLabsStyles />
      <nav className="desk-labs__list" aria-label={t(lang, "desk.labs.list")}>
        <h1 className="desk-labs__title">{t(lang, "desk.nav.labs")}</h1>
        {series.map((s) => (
          <SeriesRow key={keyOf(s)} lang={lang} s={s} current={keyOf(s) === chosen} href={at(keyOf(s))} soft={soft} />
        ))}
        {pairs.length > 0 && <BpRow lang={lang} pair={pairs[0]!} count={pairs.length} current={chosen === BP} href={at(BP)} soft={soft} />}
      </nav>
      <div className="desk-labs__pane">
        {current === null ? <BpDetail lang={lang} pairs={pairs} open={open} /> : <SeriesDetail lang={lang} s={current} open={open} />}
      </div>
    </div>
  );
}

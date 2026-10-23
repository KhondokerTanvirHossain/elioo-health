import { formatDate, formatDayMonth, t, type Lang } from "@/copy";
import type { LabSeriesJson, TrendPointJson } from "@/api/types";
import { CHIP_KEY, CHIP_SHAPE, ChipShape, chipKindOf } from "@/components/StatusChip/StatusChip";
import "./TrendChart.css";

// The drawing, in viewBox units. Reading i of n sits at the centre of the i-th of n equal columns, so the list under the
// drawing (n equal flex items, TrendChart.css) puts each reading's date, value and word exactly under its marker. The
// drawing holds no text: every word is HTML at its own CSS size, which SVG scaling cannot shrink below the 12 px floor.
const W = 320;
const H = 104;
const TOP = 16;
const BOTTOM = 88;
const MARKER = 18;

/** A printed value as a number, only when the whole text is a plain decimal: "<0.5", ">500", "5,4", "5.9 H" are not. */
function plainNumber(value: string): number | null {
  const v = value.trim();
  return /^[+-]?\d+(\.\d+)?$/.test(v) ? Number(v) : null;
}

/**
 * Whether a series is drawn as a chart: two or more readings, every one of them a plain number. The one rule, which the
 * chart itself applies and the health screen asks (W9's LabList): when it is false the screen lists the readings as
 * written, so a reading printed as a bound (">500", "<0.5") is never silently left out of a line that then ends on an
 * older reading. One reading is the screen's sentence, never a chart.
 */
export function chartable(points: readonly TrendPointJson[]): boolean {
  return points.length >= 2 && points.every((p) => plainNumber(p.value) !== null);
}

/** The widest a reading's own range bar is drawn: narrower than a column, so two neighbours' bars never touch. */
const BAR = 36;

/** The printed range a reading stands on, as Java sent it: both numbers, one, or none (then nothing is drawn). */
type Range = { low: number | null; high: number | null };

/**
 * The range the chart may draw behind a reading: Java's numbers as sent, and nothing behind a reading the chart draws
 * unknown. Java never sends a number beside an unknown (PaperDisplay.drawnBand); were one sent, the chart fails closed.
 */
function drawnRange(p: TrendPointJson, kind: ReturnType<typeof chipKindOf>): Range | null {
  if (kind === "unk" || (p.ref_low === null && p.ref_high === null)) return null;
  return { low: p.ref_low, high: p.ref_high };
}

/**
 * Neighbouring readings (in drawing order) that carry the very same numbers, as runs of indices: each run is one band
 * or one limit line. A reading with no range ends a run, and so does a different range, so a band never spans a
 * reading whose paper printed something else, or nothing.
 */
function rangeRuns(ranges: ReadonlyArray<Range | null>): Array<{ range: Range; from: number; to: number }> {
  const runs: Array<{ range: Range; from: number; to: number }> = [];
  ranges.forEach((range, i) => {
    if (!range) return;
    const last = runs.at(-1);
    if (last && last.to === i - 1 && last.range.low === range.low && last.range.high === range.high) last.to = i;
    else runs.push({ range, from: i, to: i });
  });
  return runs;
}

/** A reading's date as the chart writes it, on screen and aloud: with the year when the series spans years, and marked
 *  when the trend cannot be sure of it (an upload day standing in for a printed date, or a date the model was unsure of). */
function dateText(lang: Lang, p: TrendPointJson, withYear: boolean): string {
  const day = withYear ? formatDate(lang, p.date) : formatDayMonth(lang, p.date);
  if (p.date_is_upload_day) return t(lang, "trend.date.upload", day);
  if (p.date_uncertain) return t(lang, "trend.date.uncertain", day);
  return day;
}

/**
 * One marker's readings over time (plan W2e-b): a series of B6's `GET /app/api/patients/{id}/labs`. A dashed line
 * through the readings, oldest on the left, each marker the shape its chip has (`chipKindOf(kind, side)`, the one place
 * an API value becomes a chip kind); under it, one column per reading with its date (marked when uncertain or an upload
 * day), its value and unit as printed, and its chip's word, so a status is shape and word, never colour alone. That list
 * is what a screen reader reads, one `trend.point` sentence per reading, in date order, named by the series; the drawing
 * is decorative.
 *
 * Behind the line, the range each reading's OWN paper printed (PO ruling R3, DR-48; B6's `ref_low` / `ref_high`, PR 143),
 * only where Java sent a number, so React judges nothing (see `rangeRuns`): both numbers are a filled pale band, one is
 * a thin limit line on that side only, none is nothing whatever `ref_text` says. Neighbours that carry the very same
 * numbers share one band; a reading whose range differs stands on its own short bar, so two labs' ranges are never
 * blended into one. The scale takes in the drawn bounds, so a band is drawn at its own numbers, never clipped. The range
 * colours no marker and changes no word: a reading the chart draws says its range after its sentence
 * (`trend.point.range`, the `ref_text` Java sends, only as that accessible label), and a legend under the chart says
 * what the pale band is. Nothing is drawn unless `chartable` holds. Colours are tokens through classes (TrendChart.css),
 * no inline style, and the critical shape is never red here: the red belongs to the NOW components.
 */
export function TrendChart({ series, lang }: { series: LabSeriesJson; lang: Lang }) {
  if (!chartable(series.points)) return null;
  const withYear = new Set(series.points.map((p) => p.date.slice(0, 4))).size > 1;
  const readings = series.points
    .map((p, i) => ({ p, i, n: plainNumber(p.value)!, kind: chipKindOf(p.kind, p.side) }))
    // Oldest first; the API sends the newest first, and within one day keeps its own order (index, reversed).
    .sort((a, b) => a.p.date.localeCompare(b.p.date) || b.i - a.i);

  const ranges = readings.map((r) => drawnRange(r.p, r.kind));
  const runs = rangeRuns(ranges);
  // The scale spans the readings and every bound drawn, so a band sits at its own printed numbers, never clipped.
  const bounds = ranges.flatMap((r) => (r ? [r.low, r.high] : [])).filter((b): b is number => b !== null);
  const values = [...readings.map((r) => r.n), ...bounds];
  const min = Math.min(...values);
  const max = Math.max(...values);
  const yOf = (v: number) => (max === min ? (TOP + BOTTOM) / 2 : BOTTOM - ((v - min) / (max - min)) * (BOTTOM - TOP));
  const column = W / readings.length;
  const bar = Math.min(BAR, column * 0.6);
  const placed = readings.map((r, i) => ({ ...r, x: column * (i + 0.5), y: yOf(r.n), ranged: ranges[i] !== null }));

  return (
    <div className="trend-chart">
      <svg className="trend-chart__plot" viewBox={`0 0 ${W} ${H}`} aria-hidden="true" focusable="false">
        {runs.map(({ range, from, to }) => {
          const left = placed[from]!.x - bar / 2;
          const right = placed[to]!.x + bar / 2;
          const ids = placed
            .slice(from, to + 1)
            .map(({ p }) => p.observation_id)
            .join(" ");
          if (range.low !== null && range.high !== null) {
            const top = yOf(range.high);
            return (
              <rect
                key={`band-${ids}`}
                className="trend-chart__band"
                data-range-for={ids}
                x={left}
                y={top}
                width={right - left}
                height={yOf(range.low) - top}
                rx={3}
              />
            );
          }
          // One printed bound: a thin line at it, on its side only, never a region to an invented other bound.
          const side = range.high !== null ? "high" : "low";
          const y = yOf((range.high ?? range.low)!);
          return (
            <line
              key={`edge-${ids}`}
              className={`trend-chart__edge trend-chart__edge--${side}`}
              data-range-for={ids}
              data-range-side={side}
              x1={left}
              x2={right}
              y1={y}
              y2={y}
            />
          );
        })}
        <polyline className="trend-chart__line" points={placed.map(({ x, y }) => `${x},${y}`).join(" ")} />
        {placed.map(({ p, x, y, kind }) => (
          <g key={p.observation_id} className={`trend-chart__point trend-chart__point--${kind}`} data-observation-id={p.observation_id}>
            <ChipShape shape={CHIP_SHAPE[kind]} className="trend-chart__marker" x={x - MARKER / 2} y={y - MARKER / 2} size={MARKER} />
          </g>
        ))}
      </svg>
      <ol className="trend-chart__readings" aria-label={series.name}>
        {placed.map(({ p, kind, ranged }) => {
          const date = dateText(lang, p, withYear);
          const word = t(lang, CHIP_KEY[kind]);
          const sentence = series.unit
            ? t(lang, "trend.point", date, p.value, series.unit, word)
            : t(lang, "trend.point.nounit", date, p.value, word);
          // The range is said only where it is drawn, in the words Java sends for it (`ref_text`).
          const spoken = ranged && p.ref_text !== null ? t(lang, "trend.point.range", sentence, p.ref_text) : sentence;
          return (
            <li key={p.observation_id} className={`trend-chart__reading trend-chart__reading--${kind}`} data-reading-for={p.observation_id}>
              <span className="trend-chart__spoken">{spoken}</span>
              <span className="trend-chart__value" aria-hidden="true">
                {p.value}
              </span>
              {series.unit && (
                <span className="trend-chart__unit" aria-hidden="true">
                  {series.unit}
                </span>
              )}
              <span className="trend-chart__word" aria-hidden="true">
                {word}
              </span>
              <span className="trend-chart__date" aria-hidden="true">
                {date}
              </span>
            </li>
          );
        })}
      </ol>
      {runs.length > 0 && (
        <p className="trend-chart__legend" aria-hidden="true">
          {/* A key for each kind drawn here: the pale band, the limit line. */}
          {runs.some((r) => r.range.low !== null && r.range.high !== null) && <span className="trend-chart__key trend-chart__key--band" />}
          {runs.some((r) => r.range.low === null || r.range.high === null) && <span className="trend-chart__key trend-chart__key--edge" />}
          <span>{t(lang, "trend.band.legend")}</span>
        </p>
      )}
    </div>
  );
}

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
 * Not drawn: a band. B6's point carries no printed range, and a band that encodes no range would put an out-of-range
 * reading inside what looks like one. Nothing is drawn unless `chartable` holds. Colours are tokens through classes
 * (TrendChart.css), no inline style, and the critical shape is never red here: the red belongs to the NOW components.
 */
export function TrendChart({ series, lang }: { series: LabSeriesJson; lang: Lang }) {
  if (!chartable(series.points)) return null;
  const withYear = new Set(series.points.map((p) => p.date.slice(0, 4))).size > 1;
  const readings = series.points
    .map((p, i) => ({ p, i, n: plainNumber(p.value)!, kind: chipKindOf(p.kind, p.side) }))
    // Oldest first; the API sends the newest first, and within one day keeps its own order (index, reversed).
    .sort((a, b) => a.p.date.localeCompare(b.p.date) || b.i - a.i);

  const values = readings.map((r) => r.n);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const column = W / readings.length;
  const placed = readings.map((r, i) => ({
    ...r,
    x: column * (i + 0.5),
    y: max === min ? (TOP + BOTTOM) / 2 : BOTTOM - ((r.n - min) / (max - min)) * (BOTTOM - TOP),
  }));

  return (
    <div className="trend-chart">
      <svg className="trend-chart__plot" viewBox={`0 0 ${W} ${H}`} aria-hidden="true" focusable="false">
        <polyline className="trend-chart__line" points={placed.map(({ x, y }) => `${x},${y}`).join(" ")} />
        {placed.map(({ p, x, y, kind }) => (
          <g key={p.observation_id} className={`trend-chart__point trend-chart__point--${kind}`} data-observation-id={p.observation_id}>
            <ChipShape shape={CHIP_SHAPE[kind]} className="trend-chart__marker" x={x - MARKER / 2} y={y - MARKER / 2} size={MARKER} />
          </g>
        ))}
      </svg>
      <ol className="trend-chart__readings" aria-label={series.name}>
        {placed.map(({ p, kind }) => {
          const date = dateText(lang, p, withYear);
          const word = t(lang, CHIP_KEY[kind]);
          const spoken = series.unit
            ? t(lang, "trend.point", date, p.value, series.unit, word)
            : t(lang, "trend.point.nounit", date, p.value, word);
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
    </div>
  );
}

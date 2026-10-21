import { formatDate, formatDayMonth, type Lang } from "@/copy";
import type { TrendPointJson } from "@/api/types";
import { CHIP_SHAPE, ChipShape, chipKindOf } from "@/components/StatusChip/StatusChip";
import "./TrendChart.css";

// The drawing, in viewBox units (320 × 160, the prototype's trend sheet): readings sit between TOP and BOTTOM, left to
// right from LEFT to RIGHT, oldest first; the dates under them on DATE_Y.
const W = 320;
const H = 160;
const LEFT = 40;
const RIGHT = 280;
const TOP = 40;
const BOTTOM = 112;
const DATE_Y = 148;
const MARKER = 18;
const BAND = 28;

/** A printed value as a number, only when the whole text is one (Number, not parseFloat: "5.9 H" is not 5.9). */
function numeric(value: string): number | null {
  const n = Number(value.trim());
  return value.trim() !== "" && Number.isFinite(n) ? n : null;
}

/**
 * One marker's readings over time (plan W2e-b), the points of one series of B6's `GET /app/api/patients/{id}/labs`: an
 * SVG line through the readings, oldest on the left, each reading's marker the shape its chip has
 * (`chipKindOf(kind, side)`), its value as printed above it. A reading judged against the range its paper printed
 * stands on a band; an `unknown` reading (no range printed, or one bound only) has none, because nothing was judged.
 * Fewer than two readings that are numbers render nothing: one reading is a sentence on the health screen (W9),
 * not a chart. Colours are tokens through classes (TrendChart.css); no inline style. The critical shape is never red
 * here: the red belongs to the NOW components.
 */
export function TrendChart({ points, lang }: { points: TrendPointJson[]; lang: Lang }) {
  const readings = points
    .map((p, i) => ({ p, i, n: numeric(p.value) }))
    .filter((r): r is { p: TrendPointJson; i: number; n: number } => r.n !== null)
    // Oldest first; the API sends the newest first, and within one day keeps its own order (index, reversed).
    .sort((a, b) => a.p.date.localeCompare(b.p.date) || b.i - a.i);
  if (readings.length < 2) return null;

  const values = readings.map((r) => r.n);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const step = (RIGHT - LEFT) / (readings.length - 1);
  const placed = readings.map((r, i) => ({
    ...r,
    x: LEFT + i * step,
    y: max === min ? (TOP + BOTTOM) / 2 : BOTTOM - ((r.n - min) / (max - min)) * (BOTTOM - TOP),
  }));
  const label = placed.map(({ p }) => `${formatDate(lang, p.date)} ${p.value}`).join(", ");
  const dated = [placed[0]!, placed.at(-1)!];

  return (
    <svg className="trend-chart" viewBox={`0 0 ${W} ${H}`} role="img" aria-label={label}>
      {placed
        .filter(({ p }) => p.kind !== "unknown")
        .map(({ p, x }) => (
          <rect
            key={`band-${p.observation_id}`}
            className="trend-chart__band"
            data-band-for={p.observation_id}
            x={x - BAND / 2}
            y={TOP - 20}
            width={BAND}
            height={BOTTOM - TOP + 32}
            rx={BAND / 2}
          />
        ))}
      <polyline className="trend-chart__line" points={placed.map(({ x, y }) => `${x},${y}`).join(" ")} />
      {placed.map(({ p, x, y }) => {
        const kind = chipKindOf(p.kind, p.side);
        return (
          <g key={p.observation_id} className={`trend-chart__point trend-chart__point--${kind}`} data-observation-id={p.observation_id} data-shape={CHIP_SHAPE[kind]}>
            <ChipShape shape={CHIP_SHAPE[kind]} className="trend-chart__marker" x={x - MARKER / 2} y={y - MARKER / 2} size={MARKER} />
            <text className="trend-chart__value" x={x} y={y - MARKER / 2 - 4} textAnchor="middle">
              {p.value}
            </text>
          </g>
        );
      })}
      {/* The first date starts under the first reading and the last ends under the last, so neither runs off the side. */}
      {dated.map(({ p, x }, i) => (
        <text
          key={`date-${i}`}
          className="trend-chart__date"
          x={i === 0 ? x - MARKER / 2 : x + MARKER / 2}
          y={DATE_Y}
          textAnchor={i === 0 ? "start" : "end"}
        >
          {formatDayMonth(lang, p.date)}
        </text>
      ))}
    </svg>
  );
}

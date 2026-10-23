import { formatDate, t, type Lang } from "@/copy";
import type { BpPointJson, LabSeriesJson, LabsJson, TrendPointJson } from "@/api/types";
import { Crop } from "@/components/Crop/Crop";
import { StatusChip, chipKindOf } from "@/components/StatusChip/StatusChip";
import { TrendChart, chartable } from "@/components/TrendChart/TrendChart";
import "./LabList.css";

/** What a paper's date says: the day, marked when it is the upload day or a date the model was unsure of (D2). */
type Dated = { date: string; date_is_upload_day: boolean; date_uncertain: boolean };

/**
 * A paper's date as the health screen writes it: the day with its year, and the trend's two marks, so a date the
 * family cannot be sure of is said to be unsure (the upload day standing in for a printed date, or an unsure reading).
 */
export function paperDate(lang: Lang, p: Dated): string {
  const day = formatDate(lang, p.date);
  if (p.date_is_upload_day) return t(lang, "trend.date.upload", day);
  if (p.date_uncertain) return t(lang, "trend.date.uncertain", day);
  return day;
}

/**
 * A reading's own crop of its own paper (Java's `crop.src`, never another reading's): what lets the family catch a
 * misread value here (`landing.c.p`). Its alternative text says what the picture is, never what was read off it.
 */
function PaperCrop({ p, lang }: { p: TrendPointJson; lang: Lang }) {
  return (
    <Crop
      src={p.crop.src}
      width={null}
      height={null}
      documentId={p.document_id}
      words={{ retry: t(lang, "crop.retry"), alt: t(lang, "health.crop.alt") }}
      className="lab-list__crop"
    />
  );
}

/** One reading as written: its date, its value and unit as printed, its chip (shape and word), and its paper's crop. */
function Reading({ p, unit, lang }: { p: TrendPointJson; unit: string | null; lang: Lang }) {
  return (
    <li className="lab-list__reading" data-reading-for={p.observation_id} data-value-row data-crop-for={p.observation_id}>
      <span className="lab-list__line">
        <span className="lab-list__date">{paperDate(lang, p)}</span>
        <span className="lab-list__printed">
          <span className="lab-list__value">{p.value}</span>
          {unit !== null && <span className="lab-list__reading-unit">{unit}</span>}
        </span>
        <StatusChip kind={chipKindOf(p.kind, p.side)} lang={lang} />
      </span>
      <PaperCrop p={p} lang={lang} />
    </li>
  );
}

/**
 * One series as Java sent it. A single reading is a sentence (`health.one_reading`), never a chart. Two or more go to
 * TrendChart when it can draw them (`chartable`, the chart's own rule: every reading a plain number), and the chart says
 * each reading itself; under it, each reading's paper, newest first as Java sent them, its date beside its crop.
 * Otherwise (a reading printed as a bound, `>500`, `<0.5`) every reading is listed as written, with its date, chip and
 * crop, and nothing is drawn: a series is never shown as nothing, and never as a line that leaves a reading out. Every
 * list is named by the heading, which carries the unit, so one name in two units is never two lists named alike.
 */
function Series({ series, lang }: { series: LabSeriesJson; lang: Lang }) {
  const [first] = series.points;
  const headingId = `lab-${first?.observation_id ?? series.canonical_name}`;
  let body;
  if (series.points.length === 1 && first) {
    const date = paperDate(lang, first);
    body = (
      <div className="lab-list__once-row" data-value-row data-crop-for={first.observation_id}>
        <p className="lab-list__once-line">
          <span className="lab-list__once">
            {series.unit === null
              ? t(lang, "health.one_reading.nounit", first.value, date)
              : t(lang, "health.one_reading", first.value, series.unit, date)}
          </span>
          <StatusChip kind={chipKindOf(first.kind, first.side)} lang={lang} />
        </p>
        <PaperCrop p={first} lang={lang} />
      </div>
    );
  } else if (chartable(series.points)) {
    body = (
      <>
        <TrendChart series={series} lang={lang} labelledBy={headingId} />
        <ul className="lab-list__papers">
          {series.points.map((p) => (
            <li key={p.observation_id} className="lab-list__paper" data-value-row data-crop-for={p.observation_id}>
              <span className="lab-list__date">{paperDate(lang, p)}</span>
              <PaperCrop p={p} lang={lang} />
            </li>
          ))}
        </ul>
      </>
    );
  } else {
    body = (
      <ol className="lab-list__readings" aria-labelledby={headingId}>
        {series.points.map((p) => (
          <Reading key={p.observation_id} p={p} unit={series.unit} lang={lang} />
        ))}
      </ol>
    );
  }
  return (
    <section className="lab-list__series" data-lab-series data-canonical={series.canonical_name} data-unit={series.unit ?? ""}>
      <h3 className="lab-list__title" id={headingId}>
        <span className="lab-list__name">{series.name}</span>
        {series.unit !== null && (
          <>
            {" "}
            <span className="lab-list__unit">{series.unit}</span>
          </>
        )}
      </h3>
      {body}
    </section>
  );
}

/**
 * One half of a blood-pressure pair: its value, then its own chip, then its own crop, in one group that carries the
 * half's name and observation id, so a chip is never read as the other half's.
 */
function Half({ half, p, lang }: { half: "systolic" | "diastolic"; p: TrendPointJson; lang: Lang }) {
  return (
    <span className="lab-list__half" data-half={half} data-observation-id={p.observation_id} data-value-row data-crop-for={p.observation_id}>
      <span className="lab-list__half-reading">
        <span className="lab-list__value">{p.value}</span>
        <StatusChip kind={chipKindOf(p.kind, p.side)} lang={lang} />
      </span>
      <PaperCrop p={p} lang={lang} />
    </span>
  );
}

/**
 * One blood-pressure reading: the two halves Java paired off one line of one paper, each its value beside its own chip
 * and crop, with the slash between them read aloud too, so the two are heard apart.
 */
function Pair({ pair, lang }: { pair: BpPointJson; lang: Lang }) {
  return (
    <li className="lab-list__pair" data-bp-pair={pair.systolic.observation_id}>
      <span className="lab-list__date">{paperDate(lang, pair)}</span>
      <span className="lab-list__halves">
        <Half half="systolic" p={pair.systolic} lang={lang} />
        <span className="lab-list__slash">/</span>
        <Half half="diastolic" p={pair.diastolic} lang={lang} />
      </span>
    </li>
  );
}

/**
 * The lab values of the health screen (plan W9), `GET /app/api/patients/{id}/labs` as Java sent it: every series in
 * Java's order, never joined, split or re-ordered here (Java keys a series by canonical name AND unit, so one printed
 * name in two units is two series, each headed by its name and unit), then the blood-pressure pairs as Java paired
 * them (one line of one paper, never by order). Every reading is shown beside its own paper's crop. Nothing read yet:
 * `health.labs.none`.
 */
export function LabList({ labs, lang }: { labs: LabsJson; lang: Lang }) {
  if (labs.series.length === 0 && labs.bp.points.length === 0) {
    return <p className="lab-list__none">{t(lang, "health.labs.none")}</p>;
  }
  return (
    <div className="lab-list">
      {labs.series.map((series) => (
        <Series key={series.points[0]?.observation_id ?? series.canonical_name} series={series} lang={lang} />
      ))}
      {labs.bp.points.length > 0 && (
        <section className="lab-list__series" data-bp>
          <h3 className="lab-list__title" id="lab-bp">
            <span className="lab-list__name">{t(lang, "health.bp")}</span>
          </h3>
          <ol className="lab-list__readings" aria-labelledby="lab-bp">
            {labs.bp.points.map((pair) => (
              <Pair key={pair.systolic.observation_id} pair={pair} lang={lang} />
            ))}
          </ol>
        </section>
      )}
    </div>
  );
}

import { useId } from "react";
import type { MePatient, NowBannerJson } from "@/api/types";
import { t, type CopyKey, type Lang } from "@/copy";
import { MioAvatar } from "@/components/MioAvatar/MioAvatar";
import { NowBannerSeen } from "./NowBannerSeen";
import "./NowBanner.css";

// The shipped NOW sentence, the one the banner falls back to when Java names a key it cannot read as a NOW sentence: a
// NOW banner never renders blank, and never words the line itself. Java sends `urgency.now` (NowBannerApiHandler), and
// types.test.ts holds every committed fixture to a key that resolves without this fallback, so the fallback is a last
// resort for drift, never the contract. Only an `urgency.now*` key with no `{0}` placeholder is read as the line:
// another surface's words, or a sentence that would render a raw `{0}`, is not a safety line.
const SHIPPED_LINE: CopyKey = "urgency.now";

function lineKey(lang: Lang, key: string): CopyKey {
  if (key !== SHIPPED_LINE && !key.startsWith(`${SHIPPED_LINE}.`)) return SHIPPED_LINE;
  try {
    return /\{\d+\}/.test(t(lang, key as CopyKey)) ? SHIPPED_LINE : (key as CopyKey);
  } catch {
    return SHIPPED_LINE;
  }
}

/** The two fields of a GET /app/api/me patient the banner reads; it depends on nothing else of that shape. */
export type NowBannerPatient = Pick<MePatient, "id" | "name">;

/**
 * The global NOW banner (plan W2d-b), over GET /app/api/now-banner as Java sent it. Nothing when `show` is false.
 * Otherwise an alert: the octagon, the names of the patients whose NOW papers this viewer has not yet seen in the
 * title (Part B decision 3: the name is the title's, never inside the safety sentence), the sentence `line_key` names,
 * read verbatim, a link to the first unseen paper, the 999 link only when Java says the viewer is in Bangladesh
 * (`call_999`), and the "seen" button (NowBannerSeen), which sends `seen` back exactly as received.
 *
 * Mio is still here, as on every NOW surface. `patients` is GET /app/api/me's list, the only source of a name; a
 * patient it does not hold is not named, and a title with no name left falls back to the attention strip's words.
 *
 * A server component (plan W5): it passes the seen island words, the seen list and `live`, and no function. `live` is
 * set by a signed-in page (shell.tsx), which renders this inside NowBannerHold; the hold receives Java's answer to the
 * acknowledgement and renders it (a 2xx is not "hidden": a stale tap answers `show: true`). Without `live` the button
 * is inert and sends nothing (the catalog).
 */
export function NowBanner({
  banner,
  patients,
  lang,
  live,
}: {
  banner: NowBannerJson;
  patients: NowBannerPatient[];
  lang: Lang;
  live?: boolean;
}) {
  const titleId = useId();
  if (!banner.show) return null;

  const unseen = banner.documents.filter((d) => !d.acked);
  const byId = new Map(patients.map((p) => [p.id, p]));
  const named: NowBannerPatient[] = [];
  for (const d of unseen) {
    const p = d.patient_id === null ? undefined : byId.get(d.patient_id);
    if (p && !named.includes(p)) named.push(p);
  }
  const first = unseen[0] ?? banner.documents[0];

  return (
    <section className="now-banner" role="alert" aria-labelledby={titleId}>
      <div className="now-banner__head">
        <svg className="now-banner__shape" viewBox="0 0 24 24" aria-hidden="true" focusable="false" data-shape="octagon">
          <path d="M8.2 3h7.6L21 8.2v7.6L15.8 21H8.2L3 15.8V8.2z" />
          <path d="M12 7.5v5.5M12 16.2v.01" />
        </svg>
        <h2 className="now-banner__title" id={titleId}>
          {named.length === 0
            ? t(lang, "attn.strip")
            : named.map((p) => (
                <span key={p.id} className="now-banner__name">
                  {p.name}
                </span>
              ))}
        </h2>
        <MioAvatar size={32} still />
      </div>
      <p className="now-banner__line">{t(lang, lineKey(lang, banner.line_key))}</p>
      <div className="now-banner__actions">
        {first && (
          <a className="now-banner__open" href={`/app/documents/${encodeURIComponent(first.document_id)}`}>
            {t(lang, "attn.open")}
          </a>
        )}
        {banner.call_999 && (
          <a className="now-banner__call" href="tel:999">
            {t(lang, "now.call999")}
          </a>
        )}
        <NowBannerSeen
          seen={banner.seen}
          label={t(lang, "now.banner.seen")}
          failedLabel={t(lang, "app.err.generic")}
          live={live === true}
        />
      </div>
    </section>
  );
}

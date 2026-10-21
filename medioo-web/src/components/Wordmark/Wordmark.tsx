import type { Lang } from "@/copy";
import "./Wordmark.css";

const LIGHT = "/brand/medioo-light.svg";
const DARK = "/brand/medioo-dark.svg";
// The masters' viewBox is 2460 × 880; this is the prototypes' header size, at the same ratio.
const WIDTH = 132;
const HEIGHT = 47;

/**
 * The medioo wordmark, from the PO's vector masters (docs/design/2026-10-21-approved), chosen by theme.
 *
 * - With no `data-theme` on <html>, the <picture> follows prefers-color-scheme, so the browser fetches one file.
 * - A `data-theme` override is two more <img>, one per theme, shown by CSS (Wordmark.css) and never by script. They
 *   are lazy, so the one an unset data-theme leaves at display: none is never fetched.
 *
 * The name is a Latin word; on a Bangla page it is marked lang="en" so a screen reader says it as English.
 */
export function Wordmark({ lang }: { lang: Lang }) {
  return (
    <span className="wordmark" lang={lang === "en" ? undefined : "en"}>
      <picture className="wordmark__auto">
        <source srcSet={DARK} media="(prefers-color-scheme: dark)" />
        <img className="wordmark__img" src={LIGHT} alt="Medioo" width={WIDTH} height={HEIGHT} />
      </picture>
      {/* eslint-disable-next-line @next/next/no-img-element -- next/image is banned (Global Constraints) */}
      <img className="wordmark__img wordmark--light" src={LIGHT} alt="Medioo" width={WIDTH} height={HEIGHT} loading="lazy" />
      {/* eslint-disable-next-line @next/next/no-img-element -- next/image is banned (Global Constraints) */}
      <img className="wordmark__img wordmark--dark" src={DARK} alt="Medioo" width={WIDTH} height={HEIGHT} loading="lazy" />
    </span>
  );
}

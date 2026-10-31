import type { PaperValueJson } from "@/api/types";

/**
 * The range bar, only where Java placed the pin (`pin`, PaperDisplay.pin): the band is the printed range. No copy and no
 * directive, so the value sheet draws it on the server and a value row's client view (ValueRowView) draws it too.
 */
/** What the bar is drawn from: a pin and the printed range's two ends, or no pin and nothing else. */
export type BarFields = { pin: null } | (Pick<PaperValueJson, "ref_low" | "ref_high"> & { pin: number });

export function ValueBar({ value }: { value: BarFields }) {
  if (value.pin === null) return null;
  return (
    <span className="value-bar" aria-hidden="true">
      <svg className="value-bar__drawing" viewBox="0 0 100 10" focusable="false" preserveAspectRatio="none">
        <rect className="value-bar__line" x="0" y="4" width="100" height="2" />
        <rect className="value-bar__band" x="25" y="2" width="50" height="6" />
        <circle className="value-bar__pin" cx={value.pin.toFixed(1)} cy="5" r="4" />
      </svg>
      <span className="value-bar__ends">
        <span>{value.ref_low}</span>
        <span>{value.ref_high}</span>
      </span>
    </span>
  );
}

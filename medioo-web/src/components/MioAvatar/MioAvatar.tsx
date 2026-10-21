import "./MioAvatar.css";

export type MioSize = 32 | 48 | 96;

// Mio as the PO drew him (docs/design/2026-10-21-approved): the head for the small sizes, the whole figure at 96.
const HEAD = "/brand/mio-head.webp"; // 96 × 96
const FIGURE = "/brand/mio.webp"; // 340 × 419
const FIGURE_HEIGHT = Math.round((96 * 419) / 340);

/**
 * Mio, decorative (alt=""): the name or the message beside him carries the meaning.
 *
 * He moves only as a whole figure: the animation is on the <img> itself, plays at most twice, and not at all under
 * prefers-reduced-motion (MioAvatar.css). `still` renders no animation class; every NOW surface sets it, because Mio
 * never animates on a NOW result (master plan, Brand).
 */
export function MioAvatar({ size, still }: { size: MioSize; still?: boolean }) {
  const figure = size === 96;
  const className = ["mio-avatar", figure ? "mio-avatar--figure" : "mio-avatar--head", `mio-avatar--${size}`];
  if (!still) className.push(figure ? "mio-avatar--wave" : "mio-avatar--hop");
  return (
    // eslint-disable-next-line @next/next/no-img-element -- next/image is banned (Global Constraints)
    <img
      className={className.join(" ")}
      src={figure ? FIGURE : HEAD}
      alt=""
      width={size}
      height={figure ? FIGURE_HEIGHT : size}
    />
  );
}

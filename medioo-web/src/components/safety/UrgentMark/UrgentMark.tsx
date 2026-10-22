import "./UrgentMark.css";

/**
 * The patient's red mark (plan W4): the switcher's and the chooser's way of saying that this patient's attention stack
 * holds a NOW paper (`me.patients[].urgent`, Java's AttentionUseCase.urgent). Colour, shape and word, never colour
 * alone: the NOW token's dot drawn as an octagon (the NOW banner's shape), and the attention strip's words
 * (`attn.strip`), resolved by the server and passed in, so a client island can render the mark and ship no copy.
 *
 * It stays after the NOW banner's seen button (`now.banner.seen`): that button hides the banner only, and the patient
 * keeps the mark (Global Constraints).
 * It lives under safety/, the one place the NOW red may be painted (now-usage.ts NOW_ALLOWED).
 */
export function UrgentMark({ word }: { word: string }) {
  return (
    <span className="urgent-mark">
      <svg className="urgent-mark__dot" viewBox="0 0 24 24" aria-hidden="true" focusable="false" data-shape="octagon">
        <path d="M8.2 3h7.6L21 8.2v7.6L15.8 21H8.2L3 15.8V8.2z" />
      </svg>
      <span className="urgent-mark__word">{word}</span>
    </span>
  );
}

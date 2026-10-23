/**
 * The pressed option's tick on a segmented track (ThemeSwitch, LanguageSwitch): the shape the flags and the toast draw,
 * so which option is chosen is said by a shape as well as by the edge and the weight (colour is never the only signal).
 */
export function ChoiceTick() {
  return (
    <svg className="family-choice__tick" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d="M5 12.5l4.5 4.5L19 7.5" />
    </svg>
  );
}

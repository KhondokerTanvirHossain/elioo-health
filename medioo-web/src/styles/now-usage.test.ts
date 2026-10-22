import { describe, expect, it } from "vitest";
import type { TokenFile } from "./build-tokens";
import json from "./medioo.tokens.json";
import {
  NOW_ALLOWED,
  WEEK_ALLOWED,
  controlBoundaryViolations,
  controlSelectors,
  fillBoundaryViolations,
  fillPainters,
  nowUsageViolations,
  roleUsageViolations,
  tokenUsageViolations,
} from "./now-usage";

// The NOW red is painted only by the components that show a stored NOW (plan W2a): the checker below runs over the
// built CSS in built/now-usage.built.test.ts. These are its own tests, in both directions.
const tokens = json as unknown as TokenFile;

describe("tokenUsageViolations", () => {
  it("aNowVarOutsideTheAllowedComponentsIsReported", () => {
    expect(nowUsageViolations(".day-cell { color: var(--now-ink) }", NOW_ALLOWED)).toEqual([".day-cell uses --now-ink"]);
  });

  it("aNowVarInsideTheAllowedComponentsIsAccepted", () => {
    const css = `
      .now-banner { background: var(--now); color: var(--on-now); }
      .now-banner__call { color: var(--now-ink); }
      .now-banner--abroad { background: var(--now-soft); }
      .verdict-card--now .verdict-card__band { background: var(--now); color: var(--on-now); }
      .status-chip--crit-high { background: var(--now); }
      .status-chip--crit-low:hover { color: var(--on-now); }
    `;
    expect(nowUsageViolations(css, NOW_ALLOWED)).toEqual([]);
  });

  it("theTokenDefinitionsThemselvesAreNotUses", () => {
    const css = `
      :root { --now: #d12c24; --on-now: #ffffff; --now-ink: #a3221c; }
      :root[data-theme="dark"] { --now-soft: rgba(217, 50, 41, 0.18); }
      @media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) { --now: #d93229; } }
    `;
    expect(nowUsageViolations(css, NOW_ALLOWED)).toEqual([]);
  });

  it("aSelectorListIsJudgedPerSelector", () => {
    expect(nowUsageViolations(".now-banner, .tab-bar { background: var(--now) }", NOW_ALLOWED)).toEqual([".tab-bar uses --now"]);
  });

  it("aCustomPropertyThatForwardsNowIsAUse", () => {
    expect(nowUsageViolations(".tab-bar { --accent: var(--now) }", NOW_ALLOWED)).toEqual([".tab-bar uses --now"]);
  });

  it("aRootAliasOfNowIsAUse", () => {
    expect(nowUsageViolations(":root { --danger: var(--now) } .tab-bar { color: var(--danger) }", NOW_ALLOWED)).toEqual([
      ":root uses --now (as --danger)",
    ]);
    // A var() fallback is a use too, wherever it sits in the value.
    expect(nowUsageViolations(".tab-bar { color: var(--x, var(--now-ink)) }", NOW_ALLOWED)).toEqual([".tab-bar uses --now-ink"]);
  });

  it("anAllowedPrefixIsNotEnough", () => {
    expect(nowUsageViolations(".now-bannerx { color: var(--now) }", NOW_ALLOWED)).toEqual([".now-bannerx uses --now"]);
    expect(nowUsageViolations(".status-chip--critique { color: var(--now) }", NOW_ALLOWED)).toEqual([
      ".status-chip--critique uses --now",
    ]);
    // A descendant of another component is that component's use, not the banner's.
    expect(nowUsageViolations(".tab-bar .now-banner { color: var(--now) }", NOW_ALLOWED)).toEqual([
      ".tab-bar .now-banner uses --now",
    ]);
  });

  it("aSiblingOrHasSelectorPaintsOutsideTheComponentAndIsReported", () => {
    // The element painted is the subject: a sibling of the banner is not the banner.
    expect(nowUsageViolations(".now-banner ~ .day-cell { color: var(--now) }", NOW_ALLOWED)).toEqual([
      ".now-banner ~ .day-cell uses --now",
    ]);
    expect(nowUsageViolations(".now-banner + .tab-bar { background: var(--now) }", NOW_ALLOWED)).toEqual([
      ".now-banner + .tab-bar uses --now",
    ]);
    expect(nowUsageViolations(".status-chip--crit-high ~ * { color: var(--now) }", NOW_ALLOWED)).toEqual([
      ".status-chip--crit-high ~ * uses --now",
    ]);
    expect(nowUsageViolations(".now-banner:has(~ .x) .x { color: var(--now) }", NOW_ALLOWED)).toEqual([
      ".now-banner:has(~ .x) .x uses --now",
    ]);
    // Inside the component, in either combinator, is the component's own use.
    expect(nowUsageViolations(".now-banner .now-banner__call { color: var(--now-ink) }", NOW_ALLOWED)).toEqual([]);
    expect(nowUsageViolations(".now-banner > span { color: var(--on-now) }", NOW_ALLOWED)).toEqual([]);
    // A + inside :nth-child() is not a combinator, and ~= inside an attribute selector is not one either.
    expect(nowUsageViolations(".now-banner__row:nth-child(2n+1) { background: var(--now-soft) }", NOW_ALLOWED)).toEqual([]);
    expect(nowUsageViolations('.now-banner[class~="now-banner--abroad"] { background: var(--now-soft) }', NOW_ALLOWED)).toEqual([]);
  });

  it("everyClassInTheSelectorBelongsToTheAllowedComponent", () => {
    expect(nowUsageViolations(".now-banner .tab-bar { color: var(--now) }", NOW_ALLOWED)).toEqual([".now-banner .tab-bar uses --now"]);
    expect(nowUsageViolations(".now-banner:is(.tab-bar) { color: var(--now) }", NOW_ALLOWED)).toEqual([
      ".now-banner:is(.tab-bar) uses --now",
    ]);
    expect(nowUsageViolations(".verdict-card--now .verdict-card__band { color: var(--on-now) }", NOW_ALLOWED)).toEqual([]);
    expect(nowUsageViolations(".status-chip--crit-low .status-chip__arrow { fill: var(--now) }", NOW_ALLOWED)).toEqual([]);
  });

  it("aKeyframesBlockIsJudgedByItsNameAndByWhoAnimatesWithIt", () => {
    const pulse = "@keyframes now-banner-pulse { 0% { box-shadow: 0 0 0 0 var(--now-soft) } to { box-shadow: 0 0 0 12px var(--now-soft) } }";
    expect(nowUsageViolations(pulse, NOW_ALLOWED)).toEqual([]);
    expect(nowUsageViolations(`${pulse} .now-banner { animation: now-banner-pulse 1s infinite }`, NOW_ALLOWED)).toEqual([]);
    expect(nowUsageViolations("@keyframes tab-pulse { to { color: var(--now) } }", NOW_ALLOWED)).toEqual([
      "@keyframes tab-pulse uses --now",
    ]);
    expect(nowUsageViolations("@keyframes now-bannerx { to { color: var(--now) } }", NOW_ALLOWED)).toEqual([
      "@keyframes now-bannerx uses --now",
    ]);
    // A NOW keyframe borrowed by another component paints that component red.
    expect(nowUsageViolations(`${pulse} .tab-bar { animation: now-banner-pulse 1s }`, NOW_ALLOWED)).toEqual([
      ".tab-bar uses --now-soft (through @keyframes now-banner-pulse)",
    ]);
  });

  it("aTokenNamedLikeNowIsNotNow", () => {
    // --nowrap is not --now: only --now, --now-* and --on-now are the NOW tokens.
    expect(nowUsageViolations(".x { --nowrap: 1; white-space: var(--nowrap) }", NOW_ALLOWED)).toEqual([]);
  });

  it("weekIsCheckedTheSameWay", () => {
    expect(tokenUsageViolations(".status-chip--out-high { background: var(--week-soft) }", "week", WEEK_ALLOWED)).toEqual([
      ".status-chip--out-high uses --week-soft",
    ]);
    expect(
      tokenUsageViolations(".verdict-card--week .verdict-card__band { background: var(--week-soft) }", "week", WEEK_ALLOWED),
    ).toEqual([]);
  });

  it("theCalendarsUrgencyMarksMayPaintTheirOwnColourAndNothingElseMay", () => {
    // PO ruling R2 (DR-48): the MonthCalendar's NOW mark is the NOW red and its THIS_WEEK mark the amber, in the
    // cell and in the legend. Only the NOW modifiers may use the NOW tokens, only the THIS_WEEK ones --week.
    const now = `
      .month-calendar__alert--now .month-calendar__mark--urgency { stroke: var(--now) }
      .month-calendar__alert--now .month-calendar__alert-word { color: var(--now-ink) }
      .month-calendar__key--now .month-calendar__mark--urgency { stroke: var(--now) }
    `;
    expect(nowUsageViolations(now, NOW_ALLOWED)).toEqual([]);
    const week = `
      .month-calendar__alert--week .month-calendar__mark--urgency { stroke: var(--week) }
      .month-calendar__alert--week .month-calendar__alert-word { color: var(--week) }
      .month-calendar__key--week .month-calendar__mark--urgency { stroke: var(--week) }
    `;
    expect(tokenUsageViolations(week, "week", WEEK_ALLOWED)).toEqual([]);
    // The dangerous direction: the red on the THIS_WEEK mark, the amber on the NOW mark, either on the calendar's
    // other parts (a day, its numeral, its paper dot, the doctor's-date ring), or on a modifier that only starts alike.
    expect(nowUsageViolations(".month-calendar__alert--week .month-calendar__mark--urgency { stroke: var(--now) }", NOW_ALLOWED)).toEqual([
      ".month-calendar__alert--week .month-calendar__mark--urgency uses --now",
    ]);
    expect(
      tokenUsageViolations(".month-calendar__alert--now .month-calendar__alert-word { color: var(--week) }", "week", WEEK_ALLOWED),
    ).toEqual([".month-calendar__alert--now .month-calendar__alert-word uses --week"]);
    for (const selector of [
      ".month-calendar",
      ".month-calendar__day",
      ".month-calendar__alert",
      ".month-calendar__mark--dot",
      ".month-calendar__mark--ring",
      ".month-calendar__n--follow",
      ".month-calendar__legend li",
      ".month-calendar__alert--nowish",
    ]) {
      expect(nowUsageViolations(`${selector} { color: var(--now) }`, NOW_ALLOWED), selector).toEqual([`${selector} uses --now`]);
      expect(tokenUsageViolations(`${selector} { color: var(--week) }`, "week", WEEK_ALLOWED), selector).toEqual([`${selector} uses --week`]);
    }
    // A NOW modifier reaching into another component is that component's use.
    expect(nowUsageViolations(".month-calendar__alert--now .status-chip { color: var(--now) }", NOW_ALLOWED)).toEqual([
      ".month-calendar__alert--now .status-chip uses --now",
    ]);
  });
});

describe("roleUsageViolations", () => {
  it("aNonTextTokenNeverPaintsText", () => {
    expect(roleUsageViolations(".hint { color: var(--label3) }", tokens)).toEqual([".hint paints text with non-text --label3"]);
    expect(roleUsageViolations("input::placeholder { color: var(--label3) }", tokens)).toEqual([
      "input::placeholder paints text with non-text --label3",
    ]);
    expect(roleUsageViolations(".hint { color: var(--label2) }", tokens)).toEqual([]);
    expect(roleUsageViolations("input::placeholder { color: var(--label2) }", tokens)).toEqual([]);
    // A non-text token where it belongs, as a glyph's fill, is not text.
    expect(roleUsageViolations(".icon { fill: var(--label3) }", tokens)).toEqual([]);
  });

  it("everyPropertyThatPaintsTextIsText", () => {
    for (const prop of ["caret-color", "text-decoration-color", "-webkit-text-stroke-color", "text-emphasis-color"]) {
      expect(roleUsageViolations(`.hint { ${prop}: var(--label3) }`, tokens), prop).toEqual([".hint paints text with non-text --label3"]);
    }
    // SVG text is painted with fill and stroke.
    expect(roleUsageViolations("svg text { fill: var(--label3) }", tokens)).toEqual(["svg text paints text with non-text --label3"]);
    expect(roleUsageViolations(".chart tspan { stroke: var(--label3) }", tokens)).toEqual([
      ".chart tspan paints text with non-text --label3",
    ]);
  });

  it("aForwardedAliasIsTheTokenItForwards", () => {
    expect(roleUsageViolations(".x { --hint: var(--label3) } .x p { color: var(--hint) }", tokens)).toEqual([
      ".x p paints text with non-text --label3 (via --hint)",
    ]);
    expect(roleUsageViolations(".x { --a: var(--label3) } .y { --b: var(--a) } .y p { color: var(--b) }", tokens)).toEqual([
      ".y p paints text with non-text --label3 (via --b)",
    ]);
    expect(roleUsageViolations(".x { --hint: var(--label2) } .x p { color: var(--hint) }", tokens)).toEqual([]);
  });

  it("aSurfaceIsNeverAnIndicator", () => {
    // A surface is checked as something text sits on, never at 3:1 against another surface, so it cannot draw a
    // border, an outline or a glyph that has to be seen.
    expect(roleUsageViolations(".card { border-color: var(--bg3) }", tokens)).toEqual([".card draws an indicator with surface --bg3"]);
    expect(roleUsageViolations(".field { border: 1px solid var(--bg) }", tokens)).toEqual([".field draws an indicator with surface --bg"]);
    expect(roleUsageViolations(".x { outline-color: var(--tint-soft) }", tokens)).toEqual([".x draws an indicator with surface --tint-soft"]);
    expect(roleUsageViolations(".icon { stroke: var(--paper) }", tokens)).toEqual([".icon draws an indicator with surface --paper"]);
    expect(roleUsageViolations(".icon { fill: var(--week-soft) }", tokens)).toEqual([".icon draws an indicator with surface --week-soft"]);
    // A surface as a background, a separator as a border and a checked fill as a ring are where they belong.
    expect(roleUsageViolations(".card { background: var(--bg3); border-top: 1px solid var(--sep) }", tokens)).toEqual([]);
    expect(roleUsageViolations(".btn { border-color: var(--tint); outline: 3px solid var(--tint) }", tokens)).toEqual([]);
    // Q1 (A): the control's wash is a surface, so it cannot be the control's border either; the boundary token is.
    expect(roleUsageViolations(".field { border: 1px solid var(--fill) }", tokens)).toEqual([".field draws an indicator with surface --fill"]);
    expect(roleUsageViolations(".field { background: var(--fill); border: 1px solid var(--boundary) }", tokens)).toEqual([]);
  });
});

// Q1 (A), PR #120: fill is a wash of about 1.15:1, so a field or a segmented track shown by it alone has no boundary
// anyone can see. Every selector that paints --fill also carries --boundary (3:1 on bg, bg2, bg3) in a border, an
// outline or a box-shadow ring. Both directions are tested here; built/now-usage.built.test.ts runs it on the build.
describe("fillBoundaryViolations", () => {
  it("aFieldStyledWithFillAloneIsReported", () => {
    expect(fillBoundaryViolations(".field { background: var(--fill) }")).toEqual([".field paints --fill and carries no --boundary"]);
    expect(fillBoundaryViolations(".segmented__track { background-color: var(--fill); border-radius: var(--radius-md) }")).toEqual([
      ".segmented__track paints --fill and carries no --boundary",
    ]);
    // A border in another colour is not the boundary: sep is decorative and exempt from any contrast.
    expect(fillBoundaryViolations("input { background: var(--fill); border: 1px solid var(--sep) }")).toEqual([
      "input paints --fill and carries no --boundary",
    ]);
  });

  it("aBoundaryThatIsNamedButNeverDrawnIsReported", () => {
    // A colour with no width and no style draws nothing: a <div> track has border-style none and outline-style none.
    expect(fillBoundaryViolations(".seg__track { background: var(--fill) } .seg__track { border-color: var(--boundary) }")).toEqual([
      ".seg__track paints --fill and carries no --boundary",
    ]);
    expect(fillBoundaryViolations(".field { background: var(--fill); outline-color: var(--boundary) }")).toEqual([
      ".field paints --fill and carries no --boundary",
    ]);
    // A shorthand with a zero width, or with no style, draws nothing either.
    expect(fillBoundaryViolations(".field { background: var(--fill); border: 0 solid var(--boundary) }")).toEqual([
      ".field paints --fill and carries no --boundary",
    ]);
    expect(fillBoundaryViolations(".field { background: var(--fill); border: 1px var(--boundary) }")).toEqual([
      ".field paints --fill and carries no --boundary",
    ]);
    // A shadow is a ring only with zero offsets, zero blur and a spread of at least 1px.
    expect(fillBoundaryViolations(".field { background: var(--fill); box-shadow: 0 0 0 0 var(--boundary) }")).toEqual([
      ".field paints --fill and carries no --boundary",
    ]);
    expect(fillBoundaryViolations(".field { background: var(--fill); box-shadow: 0 4px 12px var(--boundary) }")).toEqual([
      ".field paints --fill and carries no --boundary",
    ]);
    expect(fillBoundaryViolations(".field { background: var(--fill); box-shadow: inset 0 0 1px var(--boundary) }")).toEqual([
      ".field paints --fill and carries no --boundary",
    ]);
  });

  it("aFieldCarryingTheBoundaryPasses", () => {
    expect(fillBoundaryViolations(".field { background: var(--fill); border: 1px solid var(--boundary) }")).toEqual([]);
    expect(fillBoundaryViolations(".segmented__track { background: var(--fill); box-shadow: inset 0 0 0 1px var(--boundary) }")).toEqual([]);
    expect(fillBoundaryViolations("textarea { background: var(--fill); outline: 1px solid var(--boundary) }")).toEqual([]);
    // A colour longhand counts when the same selector (or the one it is a state of) gives that edge a width and a style.
    expect(
      fillBoundaryViolations(".field { background: var(--fill); border: 1px solid var(--sep) } .field { border-color: var(--boundary) }"),
    ).toEqual([]);
    expect(
      fillBoundaryViolations(".field { background: var(--fill); border-width: 1px; border-style: solid; border-color: var(--boundary) }"),
    ).toEqual([]);
    expect(
      fillBoundaryViolations(".field { background: var(--fill); outline: 1px solid var(--sep); outline-color: var(--boundary) }"),
    ).toEqual([]);
    // One drawn edge is a boundary: an underlined field.
    expect(fillBoundaryViolations(".field { background: var(--fill); border-bottom: 1px solid var(--boundary) }")).toEqual([]);
    // A ring among other shadows is still a ring.
    expect(
      fillBoundaryViolations(".field { background: var(--fill); box-shadow: 0 1px 2px var(--sep), inset 0 0 0 1px var(--boundary) }"),
    ).toEqual([]);
    // A state of a field that carries the boundary is still that field: its hover or invalid wash needs no second one.
    expect(
      fillBoundaryViolations('.field { border: 1px solid var(--boundary) } .field:hover, .field[aria-invalid="true"] { background: var(--fill) }'),
    ).toEqual([]);
    // Only --fill is a control's wash; a card on bg3 is not a field.
    expect(fillBoundaryViolations(".card { background: var(--bg3) }")).toEqual([]);
  });

  it("aSelectorListIsJudgedPerSelector", () => {
    expect(
      fillBoundaryViolations(".field, .segmented__track { background: var(--fill) } .field { border: 1px solid var(--boundary) }"),
    ).toEqual([".segmented__track paints --fill and carries no --boundary"]);
  });

  it("aDescendantOfABorderedAncestorIsNotItsState", () => {
    // The ancestor's border is around the form, not around the input inside it: a descendant made only of a
    // pseudo-class or an attribute is another element, never a state of the selector before the space.
    expect(
      fillBoundaryViolations(".form { border: 1px solid var(--boundary) } .form :is(input, textarea) { background: var(--fill) }"),
    ).toEqual([".form :is(input, textarea) paints --fill and carries no --boundary"]);
    expect(fillBoundaryViolations(".form { border: 1px solid var(--boundary) } .form :is(input) { background: var(--fill) }")).toEqual([
      ".form :is(input) paints --fill and carries no --boundary",
    ]);
    expect(
      fillBoundaryViolations('.seg { border: 1px solid var(--boundary) } .seg [aria-pressed="true"] { background: var(--fill) }'),
    ).toEqual(['.seg [aria-pressed="true"] paints --fill and carries no --boundary']);
    expect(fillBoundaryViolations(".seg { border: 1px solid var(--boundary) } .seg :where(*) { background: var(--fill) }")).toEqual([
      ".seg :where(*) paints --fill and carries no --boundary",
    ]);
    expect(fillBoundaryViolations(".seg { border: 1px solid var(--boundary) } .seg > :hover { background: var(--fill) }")).toEqual([
      ".seg > :hover paints --fill and carries no --boundary",
    ]);
    // A field inside a form still carries its own boundary wherever it sits.
    expect(fillBoundaryViolations(".field { border: 1px solid var(--boundary) } .form .field { background: var(--fill) }")).toEqual([]);
  });

  it("aBoundaryRemovedInAStateIsReported", () => {
    // The field relies on fill alone while hovered or focused: that state is the one reported.
    expect(
      fillBoundaryViolations(".field { background: var(--fill); border: 1px solid var(--boundary) } .field:hover { border: none }"),
    ).toEqual([".field:hover removes its --boundary (border: none)"]);
    expect(
      fillBoundaryViolations(".field { background: var(--fill); box-shadow: inset 0 0 0 1px var(--boundary) } .field:focus { box-shadow: none }"),
    ).toEqual([".field:focus removes its --boundary (box-shadow: none)"]);
    // A compound or a descendant override reaches the same element.
    expect(
      fillBoundaryViolations(".field { background: var(--fill); border: 1px solid var(--boundary) } .field.bare { border: 0 }"),
    ).toEqual([".field.bare removes its --boundary (border: 0)"]);
    expect(
      fillBoundaryViolations(".field { background: var(--fill); border: 1px solid var(--boundary) } .form .field { border: none }"),
    ).toEqual([".form .field removes its --boundary (border: none)"]);
    // A state that keeps another drawn edge, a pseudo-element and another element are not the field losing its edge.
    expect(
      fillBoundaryViolations(
        ".field { background: var(--fill); border: 1px solid var(--boundary) } .field:hover { border: none; box-shadow: inset 0 0 0 1px var(--boundary) }",
      ),
    ).toEqual([]);
    expect(
      fillBoundaryViolations(
        ".field { background: var(--fill); border: 1px solid var(--boundary) } .field::placeholder { border: none } .field__icon { border: none }",
      ),
    ).toEqual([]);
  });

  it("removalsAreJudgedPerEdgeAndBySpecificity", () => {
    // An underlined field keeps only its bottom edge: removing that edge in a state leaves the wash alone.
    const underlined = ".field { background: var(--fill); border-style: solid; border-color: var(--boundary); border-width: 0 0 1px }";
    expect(fillBoundaryViolations(underlined)).toEqual([]);
    expect(fillBoundaryViolations(`${underlined} .field:focus { border-bottom: none }`)).toEqual([
      ".field:focus removes its --boundary (border-bottom: none)",
    ]);
    // Removing an edge it never drew is not removing its boundary.
    expect(fillBoundaryViolations(`${underlined} .field:focus { border-top: none }`)).toEqual([]);
    // A reset less specific than the field's own rule does not win against it.
    expect(fillBoundaryViolations("* { border: 0 } .field { background: var(--fill); border: 1px solid var(--boundary) }")).toEqual([]);
  });

  it("aFillOrABoundaryUnderAnotherNameIsTheToken", () => {
    expect(fillBoundaryViolations(".seg { --track: var(--fill) } .seg__track { background: var(--track) }")).toEqual([
      ".seg__track paints --fill (via --track) and carries no --boundary",
    ]);
    expect(
      fillBoundaryViolations(".seg { --edge: var(--boundary) } .seg__track { background: var(--fill); border: 1px solid var(--edge) }"),
    ).toEqual([]);
  });

  it("aBoundaryThatIsRemovedOrOnlyConditionalIsReported", () => {
    // A later rule that takes the border away leaves the wash alone again.
    expect(fillBoundaryViolations(".field { background: var(--fill); border: 1px solid var(--boundary) } .field { border: none }")).toEqual([
      ".field removes its --boundary (border: none)",
    ]);
    expect(
      fillBoundaryViolations(
        ".field { background: var(--fill); border: 1px solid var(--boundary) } @media (min-width: 900px) { .field { border-width: 0 } }",
      ),
    ).toEqual([".field removes its --boundary (border-width: 0)"]);
    // A boundary only under a media query is not there below it.
    expect(
      fillBoundaryViolations(".field { background: var(--fill) } @media (min-width: 900px) { .field { border: 1px solid var(--boundary) } }"),
    ).toEqual([".field paints --fill and carries no --boundary"]);
    // Inside the same media query it is there wherever the fill is.
    expect(
      fillBoundaryViolations("@media (min-width: 900px) { .field { background: var(--fill); border: 1px solid var(--boundary) } }"),
    ).toEqual([]);
  });
});

// Q1 (A) is about inputs and segmented tracks, not about one variable name: a field painted bg3 with a sep border, or
// a transparent one, has no 3:1 edge either. A selector whose subject is a control carries --boundary whatever its
// background. Both directions are tested; built/now-usage.built.test.ts runs it on the build.
describe("controlBoundaryViolations", () => {
  it("aControlWithoutTheBoundaryIsReportedWhateverItsBackground", () => {
    expect(controlBoundaryViolations("input { background: var(--bg3); border: 1px solid var(--sep) }")).toEqual([
      "input is a control and carries no --boundary",
    ]);
    expect(controlBoundaryViolations(".phone-field__input { background: none; border: 0 }")).toEqual([
      ".phone-field__input is a control and carries no --boundary",
    ]);
    expect(controlBoundaryViolations("textarea, select { font: inherit }")).toEqual([
      "textarea is a control and carries no --boundary",
      "select is a control and carries no --boundary",
    ]);
    expect(controlBoundaryViolations('[role="radiogroup"] { display: flex; background: var(--bg2) }')).toEqual([
      '[role="radiogroup"] is a control and carries no --boundary',
    ]);
    expect(controlBoundaryViolations(".segmented { display: flex } .otp-input { letter-spacing: 0.5em }")).toEqual([
      ".segmented is a control and carries no --boundary",
      ".otp-input is a control and carries no --boundary",
    ]);
    // A boundary only in a state is not there at rest; a colour with no width is not drawn.
    expect(controlBoundaryViolations("input { font: inherit } input:focus { border: 1px solid var(--boundary) }")).toEqual([
      "input is a control and carries no --boundary",
    ]);
    expect(controlBoundaryViolations(".segmented__track { border-color: var(--boundary) }")).toEqual([
      ".segmented__track is a control and carries no --boundary",
    ]);
    // A state that takes it away is reported as the fill check reports it.
    expect(controlBoundaryViolations("input { border: 1px solid var(--boundary) } input:focus { border: none }")).toEqual([
      "input:focus removes its --boundary (border: none)",
    ]);
  });

  it("aBoundedControlPasses", () => {
    expect(controlBoundaryViolations("input { background: var(--fill); border: 1px solid var(--boundary) }")).toEqual([]);
    expect(
      controlBoundaryViolations(
        ".phone-field__input { border: 1px solid var(--boundary) } .phone-field__input:focus-visible { outline: 2px solid var(--focus) }",
      ),
    ).toEqual([]);
    expect(controlBoundaryViolations(".segmented__track { box-shadow: inset 0 0 0 1px var(--boundary) }")).toEqual([]);
    // A descendant selector of a bounded control is that control.
    expect(controlBoundaryViolations("input { border: 1px solid var(--boundary) } .form input { margin: 0 }")).toEqual([]);
    // The parts around a control are not controls: its label, hint and error, its placeholder, a modifier class (the
    // element also has the block class), a checkbox or radio (the platform draws its edge), and a button.
    expect(
      controlBoundaryViolations(
        ".phone-field { display: grid } .phone-field__label, .phone-field__hint { color: var(--label2) } .phone-field__input::placeholder { color: var(--label2) } .otp-input--error { outline-offset: 2px } input[type=\"checkbox\"] { accent-color: var(--tint) } button { font: inherit }",
      ),
    ).toEqual([]);
  });

  it("theSelectorsJudgedAreListed", () => {
    // What the built test prints, so an empty list is visible as empty.
    const css =
      ".field { background: var(--fill) } .seg { --track: var(--fill) } .seg__track { background: var(--track) } .card { background: var(--bg3) }";
    expect(fillPainters(css)).toEqual([".field", ".seg__track (via --track)"]);
    expect(controlSelectors("input, .phone-field__input:focus { font: inherit } .phone-field__label { color: var(--label2) }")).toEqual([
      "input",
      ".phone-field__input:focus",
    ]);
    expect(fillPainters(".card { background: var(--bg3) }")).toEqual([]);
  });
});

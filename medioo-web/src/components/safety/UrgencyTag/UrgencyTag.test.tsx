import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { t, type Lang } from "@/copy";
import { UrgencyTag } from "./UrgencyTag";

afterEach(cleanup);

/*
 * A paper's stored urgency on a row (plan W8: the papers list and the attention strip): Java's `chip` or `level` as the
 * shape and the short word WhatsApp's bundle holds, for NOW and THIS_WEEK only. Anything else, routine and unknown
 * values included, is nothing at all (D27), never a guess.
 */

const LANGS: readonly Lang[] = ["bn", "en"];

describe("UrgencyTag", () => {
  it("nowAndThisWeekAreShapeAndWord", () => {
    for (const lang of LANGS) {
      const now = render(<UrgencyTag level="now" lang={lang} />).container.querySelector("[data-urgency]")!;
      expect(now.getAttribute("data-urgency")).toBe("now");
      expect(now.querySelector("svg[data-shape='octagon']"), "the NOW octagon").not.toBeNull();
      expect(now.textContent).toBe(t(lang, "urgency.now.short"));
      cleanup();
      const week = render(<UrgencyTag level="this_week" lang={lang} />).container.querySelector("[data-urgency]")!;
      expect(week.getAttribute("data-urgency")).toBe("this_week");
      expect(week.querySelector("svg[data-shape='calendar-week']"), "the this-week calendar").not.toBeNull();
      expect(week.textContent).toBe(t(lang, "urgency.this_week.short"));
      cleanup();
    }
  });

  it("anythingElseIsNothing", () => {
    for (const level of [null, "routine", "NOW", "", "critical"]) {
      expect(render(<UrgencyTag level={level} lang="bn" />).container.innerHTML, String(level)).toBe("");
      cleanup();
    }
  });
});

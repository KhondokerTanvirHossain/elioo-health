import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { FollowUpItemJson } from "@/api/types";
import { formatDate, t, type Lang } from "@/copy";
import none from "../../../fixtures/api/follow-ups/none.json";
import some from "../../../fixtures/api/follow-ups/some.json";
import { DueCard, DueCards } from "./DueCard";

afterEach(cleanup);

/*
 * The doctor's dates on the papers page (plan W8), over B3's generated GET /app/api/patients/{id}/follow-ups bodies:
 * each date is the doctor's words exactly as Java sent them, beside their own crop, with the day Java sent and Java's
 * `overdue` said as a shape and a word, never a colour alone and never a date compared here.
 */

const LANGS: readonly Lang[] = ["bn", "en"];
const ITEMS: FollowUpItemJson[] = some.body.items;

describe("DueCard", () => {
  it("theFixtureHoldsBothKindsOfDate", () => {
    // Guard: the tests below judge an overdue date and one that is not, or they could pass over one kind only.
    expect(ITEMS.filter((i) => i.overdue).length, "overdue items").toBeGreaterThan(0);
    expect(ITEMS.filter((i) => !i.overdue).length, "items not overdue").toBeGreaterThan(0);
  });

  it("theInstructionIsAsWrittenBesideItsCrop", () => {
    for (const lang of LANGS) {
      for (const item of ITEMS) {
        const root = render(<DueCard item={item} lang={lang} />).container;
        const card = root.querySelector("[data-value-row]");
        expect(card, `${lang} ${item.instruction}: the card`).not.toBeNull();
        // Strict equality on the field's own element: not a substring of the card, not trimmed, not re-digited.
        expect(card!.querySelector(".due-card__instruction")?.textContent, `${lang}: the instruction`).toBe(item.instruction);
        const images = [...card!.querySelectorAll("img")];
        expect(images.map((i) => i.getAttribute("src")), `${lang} ${item.instruction}: its crop, and only its crop`).toEqual([item.crop.src]);
        // The day is Java's, through the bundle.
        expect(card!.querySelector(".due-card__date")?.textContent).toBe(t(lang, "due.date", formatDate(lang, item.due_date)));
        cleanup();
      }
    }
  });

  it("overdueIsShapeAndWord", () => {
    for (const lang of LANGS) {
      for (const item of ITEMS) {
        const card = render(<DueCard item={item} lang={lang} />).container.querySelector("[data-value-row]")!;
        const mark = card.querySelector(".due-card__overdue");
        if (item.overdue) {
          expect(mark, `${lang} ${item.due_date}: the overdue mark`).not.toBeNull();
          expect(mark!.querySelector("svg[data-shape='overdue']"), "its shape").not.toBeNull();
          expect(mark!.textContent, "its word").toBe(t(lang, "due.overdue"));
          expect(card.getAttribute("data-overdue")).toBe("true");
        } else {
          expect(mark, `${lang} ${item.due_date}: no overdue mark on a date Java did not call overdue`).toBeNull();
          expect(card.textContent).not.toContain(t(lang, "due.overdue"));
        }
        cleanup();
      }
    }
  });

  it("theDatesSitUnderTheirHeadingAndNoneIsNothing", () => {
    for (const lang of LANGS) {
      const root = render(<DueCards items={ITEMS} lang={lang} />).container;
      expect(root.querySelector("h2")?.textContent).toBe(t(lang, "due.title"));
      expect(root.querySelectorAll("[data-value-row]")).toHaveLength(ITEMS.length);
      cleanup();
      // No date at all: no heading over nothing.
      expect(render(<DueCards items={none.body.items} lang={lang} />).container.innerHTML).toBe("");
      cleanup();
    }
  });
});

import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { MedicinesJson } from "@/api/types";
import { formatDate, t, type CopyKey, type Lang } from "@/copy";
import { href } from "@/routing/paths";
import medicinesEmptyLatest from "../../../fixtures/api/health/medicines-empty-latest.json";
import medicinesLatest from "../../../fixtures/api/health/medicines-latest.json";
import medicinesNewerUnread from "../../../fixtures/api/health/medicines-newer-unread.json";
import medicinesNone from "../../../fixtures/api/health/medicines-none.json";
import { FORBIDDEN_WORDS } from "./forbidden-words";
import { MedicinesAsWritten } from "./MedicinesAsWritten";

// The crop is a client island that asks Java about the session only after a failed retry; nothing here fails.
vi.mock("@/api/browser", () => ({ apiGet: vi.fn() }));

afterEach(cleanup);

const LANGS: readonly Lang[] = ["bn", "en"];
const LATEST = medicinesLatest.body as MedicinesJson;
const EMPTY = medicinesEmptyLatest.body as MedicinesJson;
const NEWER = medicinesNewerUnread.body as MedicinesJson;
const NONE = medicinesNone.body as MedicinesJson;
const ALL: Array<[string, MedicinesJson]> = [
  ["medicines-latest", LATEST],
  ["medicines-empty-latest", EMPTY],
  ["medicines-newer-unread", NEWER],
  ["medicines-none", NONE],
];

/** The fields a line may carry, in PaperDisplay.MEDICINE_FIELDS order. */
const FIELDS = ["name", "dose_text", "frequency_text", "timing_text", "duration_text"] as const;

/** The prescription's date as written here from the copy (with the two marks), not from the component. */
function dated(lang: Lang, m: MedicinesJson): string {
  const day = formatDate(lang, m.date!);
  if (m.date_is_upload_day) return t(lang, "trend.date.upload", day);
  if (m.date_uncertain) return t(lang, "trend.date.uncertain", day);
  return day;
}

const rows = (el: HTMLElement) => [...el.querySelectorAll<HTMLElement>("[data-value-row]")];

describe("MedicinesAsWritten", () => {
  it("theHeadingIsJavasKeyWithTheDate", () => {
    let elements = 0;
    for (const lang of LANGS) {
      const { container } = render(<MedicinesAsWritten medicines={LATEST} lang={lang} preview={false} />);
      expect(container.querySelector(".medicines__heading")?.textContent).toBe(t(lang, LATEST.heading_key as CopyKey));
      // medicines-latest's date is one the model marked uncertain: the mark is said.
      expect(LATEST.date_uncertain).toBe(true);
      expect(container.querySelector(".medicines__date")?.textContent).toBe(dated(lang, LATEST));
      cleanup();
      for (const [name, m] of ALL) {
        const { container: c } = render(<MedicinesAsWritten medicines={m} lang={lang} preview={false} />);
        for (const el of c.querySelectorAll("*")) {
          const text = (el.textContent ?? "").toLowerCase();
          for (const word of FORBIDDEN_WORDS) expect(text.includes(word), `${name} ${lang}: <${el.tagName}> says "${word}"`).toBe(false);
          elements++;
        }
        cleanup();
      }
    }
    expect(elements, "elements judged").toBeGreaterThan(0);
  });

  it("anEmptyLatestPrescriptionIsItsDateAndNothingElse", () => {
    // The same instance, the newest prescription read again with nothing on it: never the older list left in place.
    const { container, rerender } = render(<MedicinesAsWritten medicines={LATEST} lang="en" preview={false} />);
    expect(rows(container), "the latest's lines first").toHaveLength(LATEST.medicines.length);
    rerender(<MedicinesAsWritten medicines={EMPTY} lang="en" preview={false} />);
    expect(container.querySelector(".medicines__date")?.textContent).toBe(dated("en", EMPTY));
    expect(rows(container), "no medicine row remains").toHaveLength(0);
    for (const m of LATEST.medicines) expect(container.textContent, `${m.name} is gone`).not.toContain(m.name!);
    expect(container.querySelector("img"), "no crop of the older lines").toBeNull();
  });

  it("aNewerUnreadPrescriptionIsSaidAndLinked", () => {
    for (const preview of [false, true]) {
      for (const lang of LANGS) {
        const { container } = render(<MedicinesAsWritten medicines={NEWER} lang={lang} preview={preview} />);
        const link = container.querySelector<HTMLAnchorElement>(".medicines__newer a");
        expect(link?.textContent).toBe(t(lang, "health.medicines.newer_unread"));
        expect(link?.getAttribute("href")).toBe(href(`/app/documents/${NEWER.newer_document_id}`, preview));
        // The read one is still shown, as what it is.
        expect(rows(container)).toHaveLength(NEWER.medicines.length);
        cleanup();
      }
    }
    for (const [name, m] of ALL.filter(([, m]) => !m.newer_prescription_not_read)) {
      const { container } = render(<MedicinesAsWritten medicines={m} lang="en" preview={false} />);
      expect(container.querySelector(".medicines__newer"), name).toBeNull();
      cleanup();
    }
  });

  it("onlyTheWrittenFieldsShowVerbatim", () => {
    let fields = 0;
    for (const [name, m] of ALL) {
      for (const lang of LANGS) {
        const { container } = render(<MedicinesAsWritten medicines={m} lang={lang} preview={false} />);
        const shown = rows(container);
        expect(shown, `${name} ${lang}`).toHaveLength(m.medicines.length);
        shown.forEach((row, i) => {
          const line = m.medicines[i]!;
          const written = FIELDS.filter((f) => line[f] !== undefined);
          const els = [...row.querySelectorAll<HTMLElement>("[data-field]")];
          expect(
            els.map((e) => e.dataset.field),
            `${name} ${lang} line ${i}: the fields it was written with`,
          ).toEqual(written);
          els.forEach((e) => {
            const field = e.dataset.field as (typeof FIELDS)[number];
            expect(e.textContent, `${name} ${lang} line ${i} ${field}`).toBe(line[field]);
            fields++;
          });
          // Beside its own crop, exactly one.
          const images = [...row.querySelectorAll("img")];
          expect(images.map((img) => img.getAttribute("src")), `${name} line ${i}'s crop`).toEqual([line.crop.src]);
        });
        cleanup();
      }
    }
    expect(fields, "fields judged").toBeGreaterThanOrEqual(8);
  });

  it("noPrescriptionYetIsSaid", () => {
    for (const lang of LANGS) {
      const { container } = render(<MedicinesAsWritten medicines={NONE} lang={lang} preview={false} />);
      expect(container.querySelector(".medicines__none")?.textContent).toBe(t(lang, "health.medicines.none"));
      expect(container.querySelector(".medicines__date"), "no date without a prescription").toBeNull();
      expect(rows(container)).toHaveLength(0);
      cleanup();
    }
  });
});

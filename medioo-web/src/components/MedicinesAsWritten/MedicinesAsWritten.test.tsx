import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { MedicinesJson } from "@/api/types";
import { formatDate, t, type CopyKey, type Lang } from "@/copy";
import medicinesEmptyLatest from "../../../fixtures/api/health/medicines-empty-latest.json";
import medicinesLatest from "../../../fixtures/api/health/medicines-latest.json";
import medicinesNewerUnread from "../../../fixtures/api/health/medicines-newer-unread.json";
import medicinesNoneNewerUnread from "../../../fixtures/api/health/medicines-none-newer-unread.json";
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
const NONE_NEWER = medicinesNoneNewerUnread.body as MedicinesJson;
const ALL: Array<[string, MedicinesJson]> = [
  ["medicines-latest", LATEST],
  ["medicines-empty-latest", EMPTY],
  ["medicines-newer-unread", NEWER],
  ["medicines-none", NONE],
  ["medicines-none-newer-unread", NONE_NEWER],
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
    // Java's paper page today (/app/documents/{id} is not this build's), so a preview page links it as it is.
    const paper = `/app/documents/${NEWER.newer_document_id}`;
    expect(paper).toBe("/app/documents/b6b6b6b6-0000-4000-8000-0000000000d5");
    for (const preview of [false, true]) {
      for (const lang of LANGS) {
        const { container } = render(<MedicinesAsWritten medicines={NEWER} lang={lang} preview={preview} />);
        const link = container.querySelector<HTMLAnchorElement>(".medicines__newer a");
        expect(link?.textContent).toBe(t(lang, "health.medicines.newer_unread"));
        expect(link?.getAttribute("href"), `preview ${preview}`).toBe("/app/documents/b6b6b6b6-0000-4000-8000-0000000000d5");
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

  it("aPreviewPageLinksThePaperInsideThePreviewOnceThisBuildServesIt", async () => {
    // Once this build serves /app/documents/{id} (W6), a preview page keeps the family inside the preview, and a
    // plain page still links the plain path. The lists are the real pathsFor over NEXT_PAGES plus the paper page.
    vi.resetModules();
    vi.doMock("@/routing/paths", async (importOriginal) => {
      const real = await importOriginal<typeof import("@/routing/paths")>();
      const { FLIPPED: flipped } = await import("@/routing/flipped");
      const next = [...real.NEXT_PAGES, "/app/documents/{id}"];
      return { ...real, NEXT_PAGES: next, ...real.pathsFor({ next, java: real.JAVA_PAGES, flipped }) };
    });
    try {
      const { MedicinesAsWritten: Later } = await import("./MedicinesAsWritten");
      const linkOf = (m: MedicinesJson, preview: boolean, selector: string) => {
        const { container } = render(<Later medicines={m} lang="en" preview={preview} />);
        const value = container.querySelector(selector)?.getAttribute("href");
        cleanup();
        return value;
      };
      expect(linkOf(NEWER, true, ".medicines__newer a")).toBe("/app/preview/documents/b6b6b6b6-0000-4000-8000-0000000000d5");
      expect(linkOf(NEWER, false, ".medicines__newer a")).toBe("/app/documents/b6b6b6b6-0000-4000-8000-0000000000d5");
      expect(linkOf(NONE_NEWER, true, ".medicines__first a")).toBe("/app/preview/documents/b6b6b6b6-0000-4000-8000-0000000000d5");
      expect(linkOf(LATEST, true, ".medicines__open a")).toBe("/app/preview/documents/b6b6b6b6-0000-4000-8000-0000000000d4");
      expect(linkOf(LATEST, false, ".medicines__open a")).toBe("/app/documents/b6b6b6b6-0000-4000-8000-0000000000d4");
    } finally {
      vi.doUnmock("@/routing/paths");
      vi.resetModules();
    }
  });

  it("aFirstPrescriptionStillBeingReadIsSaidAndLinked", () => {
    // Nothing read yet and one being read: Java's LatestPrescription.none(Optional.of(id)), a new family's usual state.
    expect(NONE_NEWER.document_id).toBeNull();
    expect(NONE_NEWER.newer_prescription_not_read).toBe(true);
    for (const lang of LANGS) {
      const { container } = render(<MedicinesAsWritten medicines={NONE_NEWER} lang={lang} preview={false} />);
      const link = container.querySelector<HTMLAnchorElement>(".medicines__first a");
      expect(link?.textContent, lang).toBe(t(lang, "health.medicines.first_unread"));
      expect(link?.getAttribute("href")).toBe("/app/documents/b6b6b6b6-0000-4000-8000-0000000000d5");
      // "Newer" than nothing is not said, nor "no prescription" beside the one being read.
      expect(container.textContent, lang).not.toContain(t(lang, "health.medicines.newer_unread"));
      expect(container.textContent, lang).not.toContain(t(lang, "health.medicines.none"));
      expect(rows(container)).toHaveLength(0);
      cleanup();
    }
  });

  it("theLinesComeWithThePharmacyRuleAndTheirPrescription", () => {
    for (const [name, m] of ALL) {
      for (const lang of LANGS) {
        const { container } = render(<MedicinesAsWritten medicines={m} lang={lang} preview={false} />);
        const typed = m.document_id !== null && m.medicines.length > 0;
        const pharmacy = container.querySelector(".medicines__pharmacy");
        const doctor = container.querySelector(".medicines__doctor");
        const open = container.querySelector<HTMLAnchorElement>(".medicines__open a");
        if (typed) {
          expect(pharmacy?.textContent, `${name} ${lang}`).toBe(t(lang, "med.pharmacy"));
          expect(doctor?.textContent, `${name} ${lang}`).toBe(t(lang, "health.medicines.doctor_only"));
          expect(open?.textContent, `${name} ${lang}`).toBe(t(lang, "health.medicines.open"));
          expect(open?.getAttribute("href"), name).toBe(`/app/documents/${m.document_id}`);
        } else {
          expect([pharmacy, doctor, open], `${name} ${lang}: no typed line, so no rule about one`).toEqual([null, null, null]);
        }
        cleanup();
      }
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
          // The crop's text says what the picture is, never what was read off it (the claim the crop lets them check).
          expect(images[0]!.getAttribute("alt"), `${name} ${lang} line ${i}'s alt`).toBe(t(lang, "health.crop.alt"));
          for (const f of written) expect(images[0]!.getAttribute("alt"), `${name} ${lang} line ${i}: alt is not ${f}`).not.toBe(line[f]);
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

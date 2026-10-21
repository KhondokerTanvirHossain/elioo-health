import { describe, expect, it } from "vitest";
import vectors from "./generated/bangla-vectors.json";
import { banglaDigits, formatDate, formatDayMonth, formatMonthYear, formatPrinted, formatTime } from "./bangla";
import type { Lang } from "./index";

// Every expected value here was written by the Java method the HTML pages call (BanglaGoldenVectorsTest). A failure is
// a real divergence from Java: fix the TypeScript, never the vector.
const withLang = <T extends { lang: string }>(rows: T[]) => rows.map((v) => ({ ...v, lang: v.lang as Lang }));

describe("Bangla golden vectors", () => {
  it("every vector array is non-empty", () => {
    for (const name of ["digits", "counts", "dates", "day_months", "month_years", "raw_dates", "times"] as const) {
      expect(vectors[name].length, name).toBeGreaterThan(0);
    }
  });

  it.each(vectors.digits)("banglaDigits($in)", (v) => {
    expect(banglaDigits(v.in)).toBe(v.out);
  });

  it.each(withLang(vectors.dates))("formatDate($lang, $iso)", ({ lang, iso, out }) => {
    expect(formatDate(lang, iso)).toBe(out);
  });

  it.each(withLang(vectors.day_months))("formatDayMonth($lang, $iso)", ({ lang, iso, out }) => {
    expect(formatDayMonth(lang, iso)).toBe(out);
  });

  it.each(withLang(vectors.month_years))("formatMonthYear($lang, $iso)", ({ lang, iso, out }) => {
    expect(formatMonthYear(lang, iso)).toBe(out);
  });

  it.each(withLang(vectors.raw_dates))("formatPrinted($lang, $text)", ({ lang, text, out }) => {
    expect(formatPrinted(lang, text)).toBe(out);
  });

  it.each(withLang(vectors.times))("formatTime($lang) at $dhaka Dhaka ($instant)", ({ lang, instant, out }) => {
    expect(formatTime(lang, instant)).toBe(out);
  });
});

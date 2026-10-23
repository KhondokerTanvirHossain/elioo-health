/*
 * The words the medicines heading must never use (decision 9, DR-45 item 7): the list is what the latest prescription
 * says, never "current" medicines. Bangla is built from code points, as src/lint/bangla-literal.test.ts builds its own,
 * because no Bangla literal lives outside the generated copy. A test helper: MedicinesAsWritten.test.tsx matches each
 * rendered element's own text against these, lower-cased.
 */
export const FORBIDDEN_WORDS: readonly string[] = [
  // "current, present" (bortoman)
  String.fromCodePoint(0x09ac, 0x09b0, 0x09cd, 0x09a4, 0x09ae, 0x09be, 0x09a8),
  // "ongoing" (cholman)
  String.fromCodePoint(0x099a, 0x09b2, 0x09ae, 0x09be, 0x09a8),
  "current",
];

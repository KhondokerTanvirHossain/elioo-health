/*
 * Files the family dropped on the desktop layout before the add sheet was on the page (DropAnywhere, desktop.html
 * `#drop`). The drop opens the sheet by a soft navigation, which keeps this module's memory, and the sheet takes them as
 * its first pages, once. They live only in this tab's memory: never stored, never in an address.
 */
let held: File[] = [];

/** Holds the dropped files for the sheet about to open (replacing any not yet taken). */
export function holdFiles(files: File[]): void {
  held = [...files];
}

/** The files held for the sheet, handed over once: a second call returns none. */
export function takeHeldFiles(): File[] {
  const files = held;
  held = [];
  return files;
}

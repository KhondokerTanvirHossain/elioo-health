/**
 * A line with named slots (`{n}`, `{total}`, `{max}`), filled with numbers written in `digits` (the page's digit set,
 * 0 to 9, which the server resolved with the words). The tray's and the progress's lines reach the browser with their
 * slots unfilled, as the HTML sheet hands them to upload.js, because their numbers change as the family adds pages.
 */
export function fillSlots(line: string, values: Record<string, number>, digits: string): string {
  return line.replace(/\{(\w+)\}/g, (slot, name: string) =>
    name in values ? String(values[name]).replace(/\d/g, (d) => digits[Number(d)] ?? d) : slot,
  );
}

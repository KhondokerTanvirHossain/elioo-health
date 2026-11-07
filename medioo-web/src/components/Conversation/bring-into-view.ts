/**
 * Brings a paper's item into the conversation column (`column`, which scrolls itself), so that its card's top, where
 * the band and Java's sentence are, is always in sight:
 * - the whole item (the photo, then the card) where it fits: centred on opening, the shortest way when following;
 * - else the card alone, where it fits, the same way;
 * - else (a NOW card with its crops, taller than the column) the card at the column's top.
 * Room is what the sticky header and composer leave; the items' scroll margins (Conversation.css) stop them there.
 * `motion`: "instant" on opening; "smooth" or "auto" (reduced motion) when following.
 */
export function bringIntoView(item: HTMLElement, column: HTMLElement, how: "open" | "follow", motion: ScrollBehavior): void {
  // The room between the column's sticky header and its composer, which lie over the thread as it scrolls.
  const over = (sel: string) => column.querySelector<HTMLElement>(sel)?.getBoundingClientRect().height ?? 0;
  const room = column.clientHeight - over(".conversation__head") - over(":scope > .composer");
  const card = item.querySelector<HTMLElement>(".verdict-card") ?? item;
  const fits = (el: HTMLElement) => el.getBoundingClientRect().height <= room;
  const block: ScrollLogicalPosition = how === "open" ? "center" : "nearest";
  if (fits(item)) item.scrollIntoView({ block, behavior: motion });
  else if (fits(card)) card.scrollIntoView({ block, behavior: motion });
  else card.scrollIntoView({ block: "start", behavior: motion });
}

import type { ThreadAskJson, ThreadItemJson } from "@/api/types";

/** Java's tie order on one instant (ThreadItem.order, newest first): a paper, then a message, then an ask. */
const RANK: Record<string, number> = { paper: 0, message: 1, ask: 2 };

/**
 * The chat page's thread (review of PR 166, findings 1, 7, 13): the first page of papers and messages (`plain`, read
 * without asks) and the asks of the first page read with them (`withAsks`), newest first. Two reads, so asks never
 * push a paper card or a delivered message off the page, and a patient's first-run state is still read from papers and
 * messages alone. Only Java's items, none re-worded; an ask in `plain` (which Java never sends) and any other kind in
 * `withAsks` are left to the other read. Sorted by `at`, newest first, ties in Java's order; stable otherwise.
 */
export function mergeAsks(plain: readonly ThreadItemJson[], withAsks: readonly ThreadItemJson[]): ThreadItemJson[] {
  const asks = withAsks.filter((i): i is ThreadAskJson => i.kind === "ask");
  const rest = plain.filter((i) => i.kind !== "ask");
  return [...rest, ...asks]
    .map((item, k) => ({ item, k }))
    .sort((a, b) => {
      const at = Date.parse(b.item.at) - Date.parse(a.item.at);
      if (at !== 0) return at;
      const rank = (RANK[a.item.kind] ?? 3) - (RANK[b.item.kind] ?? 3);
      return rank !== 0 ? rank : a.k - b.k;
    })
    .map(({ item }) => item);
}

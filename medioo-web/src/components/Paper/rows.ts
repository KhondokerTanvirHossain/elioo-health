import type { PaperResultJson, PaperValueJson } from "@/api/types";

/*
 * Which value rows a paper page draws, and in what order (plan W6): the groups DocumentPages draws, over the kinds Java
 * sent (`display_kind`) and the rows it marked for the NOW block (`in_now_block`), decided nowhere else. Pure and
 * import-free, so the e2e crop check (paper.spec.ts) pairs each image with the row the page itself drew there, and
 * Paper.test.tsx holds these lists equal to the rendered DOM.
 */

/** The kinds a value is grouped by on the page: outside (critical first, in Java's order), unknown, inside. */
export function valueGroups(values: readonly PaperValueJson[]) {
  return {
    outside: values.filter((v) => v.display_kind === "critical").concat(values.filter((v) => v.display_kind === "outside")),
    unknown: values.filter((v) => v.display_kind === "unknown"),
    inside: values.filter((v) => v.display_kind === "inside"),
  };
}

/** Whether the summary draws rows inside its NOW block: a NOW paper with a value-critical reason (DocumentPages.reasonBlock). */
export function drawsRowsAbove(paper: PaperResultJson): boolean {
  return paper.now !== undefined && paper.reasons.includes("value_critical");
}

/**
 * The value rows the summary draws, in the order it draws them (DocumentPages.valuesSummary): the NOW block's rows
 * first (Java's `in_now_block`, in Java's order), then outside, unknown, and the first inside row; the other inside rows
 * are behind "see all". A row drawn in the NOW block is not drawn again below it.
 */
export function summaryRows(paper: PaperResultJson): PaperValueJson[] {
  const above = drawsRowsAbove(paper) ? paper.values.filter((v) => v.in_now_block) : [];
  const g = valueGroups(paper.values.filter((v) => !above.includes(v)));
  return [...above, ...g.outside, ...g.unknown, ...g.inside.slice(0, 1)];
}

/** Every value of the paper in the order `/all` draws them: outside (critical first), unknown, inside; none collapsed. */
export function allRows(paper: PaperResultJson): PaperValueJson[] {
  const g = valueGroups(paper.values);
  return [...g.outside, ...g.unknown, ...g.inside];
}

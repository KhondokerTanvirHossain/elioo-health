import { href } from "@/routing/paths";

/** Where a value sheet was opened from, so its close goes back to the same row (ValueRows.FROM_SUMMARY / FROM_ALL). */
export type From = "summary" | "all";

/**
 * The value sheet's address for one observation of this paper, inside the preview on a preview page. No copy, so a
 * value row's client view builds it too (ValueRowsView), from the paper's id sent once rather than once per row.
 */
export function sheetHref(documentId: string, observationId: string, from: From, preview: boolean): string {
  return href(`/app/documents/${documentId}/values/${observationId}`, preview) + `?from=${from}`;
}

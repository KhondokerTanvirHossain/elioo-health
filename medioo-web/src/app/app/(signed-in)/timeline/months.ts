/**
 * The month `by` months from Java's `YYYY-MM`, for the calendar's two links: calendar arithmetic on Java's own month
 * key (UTC, so no time zone moves it), never a reading of today. The page then shows the month Java answers.
 */
export function shift(month: string, by: number): string {
  const [y, m] = month.split("-").map(Number);
  const d = new Date(Date.UTC(y!, m! - 1 + by, 1));
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
}

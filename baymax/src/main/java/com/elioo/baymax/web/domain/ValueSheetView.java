package com.elioo.baymax.web.domain;

/**
 * One value's sheet (s13) as both routes read it, the HTML page ({@code /app/documents/{id}/values/{obs}}) and the JSON
 * ({@code /app/api/documents/{id}/values/{obs}}): the paper as its result page shows it, always DONE, and what Medioo knows
 * of the value's history. Built only by {@code ResultUseCase.valueSheet}, which refuses a paper that is not DONE before the
 * result is read (plan W6-fix, ruling R1), so neither route can open a sheet the other would refuse.
 *
 * @param result  the paper, read once (its one DOCUMENT_VIEW)
 * @param history the value's earlier readings, and whether the marker was read before in another unit
 */
public record ValueSheetView(ResultView result, EarlierHistory history) {
}

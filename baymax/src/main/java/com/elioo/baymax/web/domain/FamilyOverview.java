package com.elioo.baymax.web.domain;

import com.elioo.baymax.healthrecord.domain.FamilyAccount;

import java.util.List;

/** What {@code GET /me} returns: the family and every patient it may see. */
public record FamilyOverview(FamilyAccount family, List<PatientSummary> patients) {
}

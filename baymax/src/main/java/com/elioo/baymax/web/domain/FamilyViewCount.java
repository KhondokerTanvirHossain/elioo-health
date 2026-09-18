package com.elioo.baymax.web.domain;

import java.util.UUID;

/** Timeline views by one family in a window, for the weekly export. */
public record FamilyViewCount(UUID familyId, long timelineViews, long documentViews) {
}

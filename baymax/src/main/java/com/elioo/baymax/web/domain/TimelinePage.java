package com.elioo.baymax.web.domain;

import java.util.List;

/** A page of the timeline, newest first. {@code nextCursor} is null on the last page. */
public record TimelinePage(List<TimelineEntry> entries, String nextCursor) {
}

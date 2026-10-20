package com.elioo.baymax.web.domain;

import com.elioo.baymax.common.error.BaymaxException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** §3: (timeline date, arrival, id), newest first, as an opaque token a client cannot edit into a date. */
class TimelineCursorTest {

    private static final TimelineCursor C = new TimelineCursor(LocalDate.parse("2026-10-12"),
            Instant.parse("2026-10-14T08:00:00.123456Z"), UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4000001"));

    @Test
    void aCursorRoundTripsExactlyAndIsOpaque() {
        assertThat(TimelineCursor.parse(C.encode())).isEqualTo(C);
        assertThat(C.encode()).startsWith("v1.").doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}").doesNotContain("|");
    }

    @Test
    void noCursorIsTheFirstPage() {
        assertThat(TimelineCursor.parse(null)).isEqualTo(TimelineCursor.FIRST);
        assertThat(TimelineCursor.parse(" ")).isEqualTo(TimelineCursor.FIRST);
    }

    /** A "আরও দেখুন" link saved before this deploy carries a bare instant: it opens the first page, not an error. */
    @Test
    void aPreDeployInstantCursorOpensTheFirstPage() {
        assertThat(TimelineCursor.parse("2026-10-14T08:00:00Z")).isEqualTo(TimelineCursor.FIRST);
    }

    /** The other direction: a payload without the v1 prefix, or a v1 that does not decode, is a 400 (as before). */
    @Test
    void anythingElseIsABadRequest() {
        String unprefixed = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "2026-10-12|2026-10-14T08:00:00Z|5e9edf6d-0a2a-4913-8675-9d07a4000001".getBytes(StandardCharsets.UTF_8));
        String twoParts = "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                "2026-10-12|2026-10-14T08:00:00Z".getBytes(StandardCharsets.UTF_8));
        String notADate = "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                "12 Oct|2026-10-14T08:00:00Z|5e9edf6d-0a2a-4913-8675-9d07a4000001".getBytes(StandardCharsets.UTF_8));
        for (String bad : new String[]{unprefixed, "v1.!!!", "abc", "v2." + unprefixed, twoParts, notADate}) {
            assertThatThrownBy(() -> TimelineCursor.parse(bad)).as(bad).isInstanceOf(BaymaxException.class)
                    .satisfies(e -> assertThat(((BaymaxException) e).status().value()).isEqualTo(400));
        }
    }
}

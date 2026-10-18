package com.elioo.baymax.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The phase-1 allowlist, once, for every outbound path: intake replies, explanations, and login codes. */
class WaAllowlistTest {

    @Test
    void aListedNumberIsPermittedAndAnUnlistedOneIsNot() {
        BaymaxProperties.Wa wa = new BaymaxProperties().getWa();
        wa.setAllowlist(List.of(" +8801700000000 "));          // env values arrive with stray spaces
        assertThat(wa.permits("+8801700000000")).isTrue();
        assertThat(wa.permits("+8801700000001")).isFalse();
        assertThat(wa.permits("8801700000000")).as("only E.164 matches; callers normalise first").isFalse();
        assertThat(wa.permits(null)).isFalse();
    }

    /** Today's documented meaning, kept: an empty list is "no phase-1 restriction" (phase 5 runs without it). */
    @Test
    void anEmptyListPermitsEveryone() {
        assertThat(new BaymaxProperties().getWa().permits("+8801700000000")).isTrue();
    }
}

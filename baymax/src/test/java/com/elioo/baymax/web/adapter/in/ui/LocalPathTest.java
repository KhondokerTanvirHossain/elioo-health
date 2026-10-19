package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** "back" is a page of this app or the landing, never another origin: browsers read /\host and /%5Chost as //host. */
class LocalPathTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "/app/timeline?patient=5e9edf6d-0a2a-4913-8675-9d07a4100002|/app/timeline?patient=5e9edf6d-0a2a-4913-8675-9d07a4100002",
            "/app/home|/app/home",
            "/app/|/app/",
            "/|/",
            "//evil.example/x|/app/home",
            "/\\evil.example|/app/home",
            "/\\\\evil.example|/app/home",
            "/%5Cevil.example|/app/home",
            "/%5cevil.example|/app/home",
            "/%2F%2Fevil.example|/app/home",
            "https://evil.example|/app/home",
            "/api/v1/baymax/me|/app/home",
            "/application|/app/home",
            "app/home|/app/home"})
    void onlyALocalAppPathIsKept(String raw, String expected) {
        assertThat(LocalPath.or(raw, "/app/home")).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void nothingAskedIsTheFallback() {
        assertThat(LocalPath.or(null, "/")).isEqualTo("/");
        assertThat(LocalPath.or("", "/")).isEqualTo("/");
    }
}

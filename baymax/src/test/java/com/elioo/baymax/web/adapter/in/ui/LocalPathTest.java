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

    /**
     * A browser resolves "." and ".." segments (and their %2e spellings) before it requests, so "/app/../api" is a page
     * outside /app. The other direction: dots that are not a whole segment, and dots in the query, are kept.
     */
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "/app/../api/v1/baymax/me|/app/home",
            "/app/..|/app/home",
            "/app/./timeline|/app/home",
            "/app/timeline/..|/app/home",
            "/app/%2e%2e/api/v1/baymax/me|/app/home",
            "/app/%2E%2E/api/v1/baymax/me|/app/home",
            "/app/.%2e/api|/app/home",
            "/app/%2e/timeline|/app/home",
            "/app/timeline.old|/app/timeline.old",
            "/app/..timeline|/app/..timeline",
            "/app/timeline?patient=x..y|/app/timeline?patient=x..y",
            "/app/timeline?back=../x|/app/timeline?back=../x"})
    void aDotSegmentNeverLeavesApp(String raw, String expected) {
        assertThat(LocalPath.or(raw, "/app/home")).isEqualTo(expected);
    }

    /**
     * What only the decoded re-check sees: a malformed escape (decoding throws; the language switch answered 500 on it)
     * and an escaped backslash inside /app, which the raw pattern accepts because '%' and '5C' are plain characters. The
     * other direction: an escape that decodes to something safe is kept as it was sent.
     */
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "/app/x%ZZ|/app/home",
            "/app/x%|/app/home",
            "/app/%5Cx|/app/home",
            "/app/%5c%5cevil.example|/app/home",
            "/app/timeline?patient=%5C|/app/home",
            "/app/timeline?patient=%35e9|/app/timeline?patient=%35e9"})
    void theDecodedFormIsCheckedToo(String raw, String expected) {
        assertThat(LocalPath.or(raw, "/app/home")).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void nothingAskedIsTheFallback() {
        assertThat(LocalPath.or(null, "/")).isEqualTo("/");
        assertThat(LocalPath.or("", "/")).isEqualTo("/");
    }
}

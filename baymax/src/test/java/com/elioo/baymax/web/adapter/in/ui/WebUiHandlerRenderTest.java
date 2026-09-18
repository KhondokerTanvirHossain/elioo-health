package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** What the document page shows and, more importantly, what it never shows. */
class WebUiHandlerRenderTest {

    private final WebUiHandler ui = new WebUiHandler(mock(WebAuthUseCase.class), mock(TimelineUseCase.class),
            new SessionAuthFilter(mock(WebAuthUseCase.class), new BaymaxProperties()));
    private final UUID id = UUID.randomUUID();

    /** Acceptance: given unverified items, the count is shown and the items themselves never appear. */
    @Test
    void unverifiedCountIsShownAndNothingElseAboutThem() {
        DocumentView view = new DocumentView(id.toString(), "DONE", null, "prescription", "2026-09-01", null, 0.9, "m", 1,
                List.of(Map.of("name", "BP", "value", "130", "unit", "mmHg", "crop_key", "f/p/d/crop-v1.jpg")),
                List.of(), List.of(), null, Map.of("values", 1, "medicines", 2, "follow_up", 0, "total", 3));
        String html = ui.render(view, id);
        assertThat(html).contains("3 টি অংশ মূল ছবিতে খুঁজে পাওয়া যায়নি");
        assertThat(html).contains("130").contains("mmHg");
        assertThat(html).doesNotContain("unverified_values").doesNotContain("medicines\":");
    }

    /** Acceptance: given a NEEDS_RETAKE document, no extracted values are rendered anywhere. */
    @Test
    void aRetakeRendersTheReasonAndThePageImageOnly() {
        DocumentView view = new DocumentView(id.toString(), "NEEDS_RETAKE", "the document was not read confidently enough (0.75)",
                null, null, null, null, null, 1, null, null, null, null, null);
        String html = ui.render(view, id);
        assertThat(html).contains("আবার ছবি তুলুন").contains("not read confidently").contains("/documents/" + id + "/page/1");
        assertThat(html).doesNotContain("<h2>মান</h2>").doesNotContain("<h2>ওষুধ</h2>").doesNotContain("crop-");
    }

    /** Acceptance: images are served through an endpoint that signs on load, never as a signed URL in the page. */
    @Test
    void cropsPointAtTheReSigningEndpointNotAtStorage() {
        DocumentView view = new DocumentView(id.toString(), "DONE", null, "prescription", "2026-09-01", "Clinic", 0.9, "m", 1,
                List.of(), List.of(Map.of("name", "Tab. Napa", "dose_text", "500 mg", "frequency_text", "১+০+১", "crop_key", "f/p/d/crop-m1.jpg")),
                List.of(), null, null);
        String html = ui.render(view, id);
        assertThat(html).contains("/baymax/documents/" + id + "/image?key=f%2Fp%2Fd%2Fcrop-m1.jpg");
        assertThat(html).doesNotContain("X-Amz-").doesNotContain("amazonaws").doesNotContain("localhost:9000");
        assertThat(html).contains("১+০+১");   // verbatim, Bangla numerals untouched
    }

    /** Safety constraint: no explanation, urgency or advice anywhere in the UI. */
    @Test
    void nothingInterpretiveIsRenderedForAFullDocument() {
        DocumentView view = new DocumentView(id.toString(), "DONE", null, "lab_report", "2026-09-01", null, 0.9, "m", 1,
                List.of(Map.of("name", "HbA1c", "value", "9.8", "unit", "%", "flag", "high", "crop_key", "k")),
                List.of(), List.of(), Map.of("diagnosis", List.of(Map.of("text", "T2DM", "crop_key", "k2"))), null);
        String html = Html.page("t", ui.render(view, id)).toLowerCase();
        for (String forbidden : List.of("urgent", "জরুরি", "advice", "পরামর্শ", "high risk", "ঝুঁকি", "normal range", "should")) {
            assertThat(html).as(forbidden).doesNotContain(forbidden);
        }
        assertThat(html).contains("9.8");   // the value itself, as extracted, is shown
    }

    @Test
    void everythingFromADocumentIsEscaped() {
        DocumentView view = new DocumentView(id.toString(), "DONE", null, "<script>", "2026-09-01", "<img src=x onerror=alert(1)>", 0.9, "m", 1,
                List.of(Map.of("name", "<b>x</b>", "value", "1", "unit", "\"", "crop_key", "k")), List.of(), List.of(), null, null);
        String html = ui.render(view, id);
        assertThat(html).doesNotContain("<script>").doesNotContain("<img src=x").contains("&lt;img src=x onerror=alert(1)&gt;").contains("&lt;b&gt;x&lt;/b&gt;");
    }

    /**
     * The doctor's own advice lines are shown as written, in the page's order, each beside its crop, under
     * "as written on the prescription" — never composed, never reordered (PO ruling 2026-09-18).
     */
    @Test
    void thePagesAdviceIsShownAsWrittenInOrderAndSectionKeysAreLabelledInBangla() {
        java.util.LinkedHashMap<String, Object> ctx = new java.util.LinkedHashMap<>();
        ctx.put("advice", List.of(Map.of("text", "Salt restriction", "crop_key", "k1"),
                Map.of("text", "Daily walk 30 min", "crop_key", "k2")));
        ctx.put("diagnosis", List.of(Map.of("text", "Hypertension", "crop_key", "k3")));
        DocumentView view = new DocumentView(id.toString(), "DONE", null, "prescription", "2026-09-01", null, 0.9, "m", 1,
                List.of(), List.of(), List.of(), ctx, null);
        String html = ui.render(view, id);
        assertThat(html).contains("প্রেসক্রিপশনে যা লেখা আছে").contains("Salt restriction").contains("Daily walk 30 min");
        assertThat(html.indexOf("Salt restriction")).isLessThan(html.indexOf("Daily walk 30 min"));
        assertThat(html).contains("image?key=k1").contains("image?key=k2");
        assertThat(html).doesNotContain(">advice<").doesNotContain(">diagnosis<").contains("রোগ নির্ণয়");
    }

    /** The released BMX-6 message is the only interpretive text on the page, shown verbatim above the extraction. */
    @Test
    void theReleasedMessageIsShownVerbatimAndOnlyWhenPresent() {
        DocumentView view = new DocumentView(id.toString(), "DONE", null, "prescription", "2026-09-01", null, 0.9, "m", 1,
                List.of(), List.of(), List.of(), null, null);
        String with = ui.render(view, id, java.util.Optional.of("রিপোর্টটি পেয়েছি।\nএ সপ্তাহের মধ্যে একজন ডাক্তার দেখান।"));
        assertThat(with).contains("সংক্ষেপে").contains("এ সপ্তাহের মধ্যে একজন ডাক্তার দেখান");
        assertThat(ui.render(view, id, java.util.Optional.empty())).doesNotContain("সংক্ষেপে");
    }
}

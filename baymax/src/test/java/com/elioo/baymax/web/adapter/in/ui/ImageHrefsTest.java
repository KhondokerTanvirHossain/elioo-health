package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.testsupport.PaperFixtures;
import com.elioo.baymax.web.adapter.in.ImageRefs;
import com.elioo.baymax.web.adapter.in.ImageRefsSentinel;
import com.elioo.baymax.web.domain.EarlierReading;
import com.elioo.baymax.web.domain.GateState;
import com.elioo.baymax.web.domain.ResultView;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rebuild plan B2, the render guard for {@link ImageRefs}: every {@code <img src>}, and every {@code <a href>} that opens
 * an image ({@code /page/} or {@code /image}), on the result (with its NOW blocks and the generic block's whole pages),
 * the detail ({@code /all}), the retake page and the value sheet (with an earlier reading on another paper) equals an
 * {@link ImageRefs#crop} of one of the stored rows it shows or an {@link ImageRefs#page} of one of its pages.
 *
 * <p>Rendered under {@link ImageRefsSentinel}: ImageRefs builds every href with a sentinel base, so an href built by
 * hand beside it — the same string as ImageRefs' output today — no longer passes for one of its own and is named here
 * now, not only after D4a changes the route. The list of images is asserted non-empty for each page, and the four
 * places a whole page is drawn each draw one.
 */
class ImageHrefsTest {

    private static final UUID EARLIER_DOC = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100009");
    private static final String EARLIER_CROP = "f/p/d2/crop-Potassium.jpg";

    private final WebUiTestKit kit = new WebUiTestKit();

    @Test
    void everyImageHrefOnTheResultDetailRetakeAndSheetPagesGoesThroughImageRefs() {
        try (ImageRefsSentinel sentinel = ImageRefsSentinel.install()) {
            ResultView now = PaperFixtures.nowPaper(PaperFixtures.BD);
            // a stored reason the enum does not know draws the generic block, whose proof is the whole pages
            ResultView legacy = PaperFixtures.result(now.document(), Urgency.NOW, List.of("legacy_thing"), GateState.NONE, null, PaperFixtures.BD);
            ResultView retake = PaperFixtures.retake("low_overall", 1, true);
            Map<String, Object> potassium = PaperFixtures.potassiumCritical();
            UUID observation = (UUID) potassium.get("id");
            List<EarlierReading> earlier = List.of(new EarlierReading(EARLIER_DOC, UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100010"),
                    "6.1", "mmol/L", EARLIER_CROP, LocalDate.parse("2026-09-01"), false));

            Set<String> nowRefs = refsOf(now.document());
            Set<String> sheetRefs = new LinkedHashSet<>(nowRefs);
            sheetRefs.add(ImageRefs.crop(EARLIER_DOC, EARLIER_CROP));

            record Rendered(String html, Set<String> allowed, UUID document, boolean drawsWholePage) {
            }
            Map<String, Rendered> pages = new LinkedHashMap<>();
            pages.put("result", new Rendered(kit.documents.summaryBody(Lang.BN, now), nowRefs, PaperFixtures.DOC, true));
            pages.put("result, generic NOW block", new Rendered(kit.documents.summaryBody(Lang.BN, legacy), nowRefs, PaperFixtures.DOC, true));
            pages.put("detail", new Rendered(kit.documents.detail(Lang.BN, now), nowRefs, PaperFixtures.DOC, true));
            pages.put("retake", new Rendered(kit.documents.retakeBody(Lang.BN, retake), refsOf(retake.document()), PaperFixtures.DOC, true));
            pages.put("value sheet", new Rendered(kit.documents.valueSheet(Lang.BN, now, observation, earlier, ValueRows.FROM_SUMMARY),
                    sheetRefs, PaperFixtures.DOC, false));

            List<String> offRefs = new ArrayList<>();
            List<String> noPage = new ArrayList<>();
            Map<String, String> seen = new LinkedHashMap<>();
            pages.forEach((name, page) -> {
                Dom dom = Dom.parse(page.html());
                List<String> srcs = dom.all.stream().filter(e -> e.tag().equals("img")).map(e -> e.attrs().get("src")).toList();
                List<String> links = dom.all.stream().filter(e -> e.tag().equals("a")).map(e -> e.attrs().get("href"))
                        .filter(h -> h != null && (h.contains("/page/") || h.contains("/image"))).toList();
                seen.put(name, srcs.size() + " img, " + links.size() + " a");
                assertThat(srcs).as(name + ": images on the page").isNotEmpty();
                srcs.stream().filter(src -> src == null || !src.startsWith(ImageRefsSentinel.BASE) || !page.allowed().contains(src))
                        .forEach(src -> offRefs.add(name + ": <img src=\"" + src + "\">"));
                links.stream().filter(href -> !href.startsWith(ImageRefsSentinel.BASE) || !page.allowed().contains(href))
                        .forEach(href -> offRefs.add(name + ": <a href=\"" + href + "\">"));
                if (page.drawsWholePage() && !srcs.contains(ImageRefs.page(page.document(), 1))) {
                    noPage.add(name);
                }
            });
            assertThat(seen.get("value sheet")).as("the value sheet draws its crop and the earlier reading's").startsWith("2 img");
            assertThat(offRefs).as("image hrefs not made by ImageRefs, by page (" + seen + ")").isEmpty();
            assertThat(noPage).as("pages that drew no whole page through ImageRefs").isEmpty();
        }
    }

    /** Every href ImageRefs gives this paper: a crop of each stored row that has one, and each of its pages. */
    private static Set<String> refsOf(DocumentView v) {
        UUID id = UUID.fromString(v.documentId());
        Set<String> refs = new LinkedHashSet<>();
        int pageCount = v.pageCount() == null ? 1 : Math.max(1, v.pageCount());
        for (int n = 1; n <= pageCount; n++) {
            refs.add(ImageRefs.page(id, n));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<Map<String, Object>> list : java.util.Arrays.asList(v.values(), v.medicines(), v.followUp())) {
            if (list != null) {
                rows.addAll(list);
            }
        }
        if (v.clinicalContext() != null) {
            v.clinicalContext().values().forEach(section -> {
                if (section instanceof List<?> lines) {
                    lines.forEach(line -> {
                        if (line instanceof Map<?, ?> m && m.get("crop_key") != null) {
                            rows.add(Map.of("crop_key", m.get("crop_key")));
                        }
                    });
                }
            });
        }
        rows.stream().map(r -> r.get("crop_key")).filter(k -> k != null && !String.valueOf(k).isBlank())
                .forEach(k -> refs.add(ImageRefs.crop(id, String.valueOf(k))));
        return refs;
    }
}

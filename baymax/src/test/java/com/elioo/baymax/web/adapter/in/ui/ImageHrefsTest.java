package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.testsupport.PaperFixtures;
import com.elioo.baymax.web.adapter.in.ImageRefs;
import com.elioo.baymax.web.domain.GateState;
import com.elioo.baymax.web.domain.ResultView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rebuild plan B2, the render guard for {@link ImageRefs}: every {@code <img src>} on the result (with its NOW blocks and
 * the generic block's whole pages), the detail ({@code /all}) and the retake page equals an {@link ImageRefs#crop} of one
 * of that paper's stored rows or an {@link ImageRefs#page} of one of its pages. An href built by hand beside it would
 * survive D4a's change of route and keep every page image on the 302 to S3; this test names it. The list of images is
 * asserted non-empty first, and the page images of every one of the four places a page is drawn are among them.
 */
class ImageHrefsTest {

    private final WebUiTestKit kit = new WebUiTestKit();

    @Test
    void everyImgOnTheResultDetailAndRetakePagesGoesThroughImageRefs() {
        ResultView now = PaperFixtures.nowPaper(PaperFixtures.BD);
        // a stored reason the enum does not know draws the generic block, whose proof is the whole pages
        ResultView legacy = PaperFixtures.result(now.document(), Urgency.NOW, List.of("legacy_thing"), GateState.NONE, null, PaperFixtures.BD);
        ResultView retake = PaperFixtures.retake("low_overall", 1, true);

        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("result", kit.documents.summaryBody(Lang.BN, now));
        pages.put("result, generic NOW block", kit.documents.summaryBody(Lang.BN, legacy));
        pages.put("detail", kit.documents.detail(Lang.BN, now));
        pages.put("retake", kit.documents.retakeBody(Lang.BN, retake));

        List<String> offRefs = new ArrayList<>();
        List<String> noPage = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        pages.forEach((name, html) -> {
            ResultView view = name.equals("retake") ? retake : now;
            Set<String> allowed = refsOf(view.document());
            Dom dom = Dom.parse(html);
            List<String> srcs = dom.all.stream().filter(e -> e.tag().equals("img")).map(e -> e.attrs().get("src")).toList();
            seen.put(name, srcs.size());
            assertThat(srcs).as(name + ": images on the page").isNotEmpty();
            srcs.stream().filter(src -> !allowed.contains(src)).forEach(src -> offRefs.add(name + ": " + src));
            if (!srcs.contains(ImageRefs.page(UUID.fromString(view.document().documentId()), 1))) {
                noPage.add(name);
            }
        });
        assertThat(offRefs).as("<img src> not made by ImageRefs, by page (" + seen + ")").isEmpty();
        assertThat(noPage).as("pages that drew no whole page through ImageRefs").isEmpty();
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

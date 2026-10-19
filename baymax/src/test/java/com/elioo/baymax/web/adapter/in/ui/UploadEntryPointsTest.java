package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One way to add a paper (Global Constraints): every multipart form posts to /app/add (or the legacy route), and every
 * camera or "send a paper" link opens /app/add. Derived over every page the router serves; camera links are found by
 * the camera icon's own path data, so a new camera button anywhere is held to this.
 */
class UploadEntryPointsTest {

    private static final Pattern MULTIPART = Pattern.compile("<form[^>]*enctype=\"multipart/form-data\"[^>]*>");
    private static final Pattern ACTION = Pattern.compile("action=\"([^\"]+)\"");
    private static final Pattern LINK = Pattern.compile("<a\\b([^>]*)>(.*?)</a>", Pattern.DOTALL);
    private static final String CAMERA = Icons.svg("camera", 24).replaceAll(".*?(<path d=\"[^\"]+\"/>).*", "$1");

    @Test
    void everyUploadFormAndEveryCameraLinkGoesToTheOneEntry() {
        Map<String, String> pages = RenderedPages.all();
        List<String> wrong = new ArrayList<>();
        int forms = 0, cameras = 0;
        for (Map.Entry<String, String> page : pages.entrySet()) {
            for (Matcher f = MULTIPART.matcher(page.getValue()); f.find(); ) {
                forms++;
                Matcher a = ACTION.matcher(f.group());
                String action = a.find() ? a.group(1) : "";
                if (!action.equals("/app/add") && !action.matches("/app/patients/[0-9a-f-]{36}/upload")) {
                    wrong.add(page.getKey() + " form → " + action);
                }
            }
            for (Matcher l = LINK.matcher(page.getValue()); l.find(); ) {
                if (l.group(2).contains(CAMERA)) {
                    cameras++;
                    Matcher href = Pattern.compile("href=\"([^\"]+)\"").matcher(l.group(1));
                    if (!href.find() || !href.group(1).startsWith("/app/add")) {
                        wrong.add(page.getKey() + " camera link → " + (href.find(0) ? href.group(1) : "(none)"));
                    }
                }
            }
        }
        // each direction on its own: a sweep that found forms but no camera link would hold the links to nothing
        assertThat(forms).as("multipart forms found; zero would pass vacuously").isPositive();
        assertThat(cameras).as("camera links found; zero would pass vacuously").isPositive();
        assertThat(wrong).isEmpty();
    }
}

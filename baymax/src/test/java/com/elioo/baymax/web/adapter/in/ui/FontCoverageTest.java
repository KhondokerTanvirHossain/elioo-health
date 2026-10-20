package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every code point a Bangla page can show falls in the unicode-range of a self-hosted Bengali face, and every face draws
 * what its range claims: each copy code point inside a face's range is in the cmap of the file that face serves, read
 * from the served bytes. Derived from the bundles and the served stylesheet, never from a list.
 */
class FontCoverageTest {

    private static final Pattern FACE = Pattern.compile("@font-face\\s*\\{([^}]*)}");
    private static final Pattern RANGE = Pattern.compile("U\\+([0-9A-Fa-f]{4})(?:-([0-9A-Fa-f]{4}))?");
    private static final Pattern FAMILY = Pattern.compile("font-family:\\s*\"([^\"]+)\"");
    private static final Pattern WEIGHT = Pattern.compile("font-weight:\\s*(\\d+)");
    private static final Pattern URL = Pattern.compile("url\\(\\s*(/assets/[^)\\s]+\\.woff2)\\s*\\)");

    record Face(String family, int weight, String url, List<int[]> ranges) {
        boolean covers(int c) {
            return ranges.stream().anyMatch(r -> c >= r[0] && c <= r[1]);
        }
    }

    static List<Face> faces(String css) {
        List<Face> faces = new ArrayList<>();
        for (Matcher f = FACE.matcher(css); f.find(); ) {
            String body = f.group(1);
            List<int[]> ranges = new ArrayList<>();
            int at = body.indexOf("unicode-range");
            if (at >= 0) {
                for (Matcher r = RANGE.matcher(body.substring(at)); r.find(); ) {
                    int lo = Integer.parseInt(r.group(1), 16);
                    ranges.add(new int[]{lo, r.group(2) == null ? lo : Integer.parseInt(r.group(2), 16)});
                }
            }
            Matcher family = FAMILY.matcher(body);
            Matcher weight = WEIGHT.matcher(body);
            Matcher url = URL.matcher(body);
            assertThat(family.find() && weight.find() && url.find()).as("a face names its family, weight and woff2: " + body).isTrue();
            faces.add(new Face(family.group(1), Integer.parseInt(weight.group(1)), url.group(1), ranges));
        }
        return faces;
    }

    /** Every code point of the given bundles, after Properties has resolved escapes (as a page sees it). */
    private static Set<Integer> copy(List<Lang> langs) throws Exception {
        Set<Integer> out = new TreeSet<>();
        for (Lang lang : langs) {
            for (String part : UiCopy.PARTS) {
                BundleFiles.values(part, lang).values().forEach(v -> v.codePoints().forEach(out::add));
            }
            BundleFiles.messages(lang).values().forEach(v -> v.codePoints().forEach(out::add));
        }
        return out;
    }

    @Test
    void everyBanglaCodePointInTheCopyIsInABengaliFacesRange() throws Exception {
        String css = new WebUiTestKit().servedText("/assets/baymax.css");
        List<Face> bengali = faces(css).stream().filter(f -> f.family().equals("Noto Sans Bengali")).toList();
        assertThat(bengali).as("Bengali faces in the served CSS").isNotEmpty();
        assertThat(bengali).allSatisfy(f -> assertThat(f.ranges()).as(f.weight() + "'s unicode-range").isNotEmpty());
        TreeSet<Integer> need = new TreeSet<>();
        copy(List.of(Lang.BN)).stream().filter(c -> c >= 0x0964 && c <= 0x09FF).forEach(need::add);
        assertThat(need).as("Bangla code points in the copy").hasSizeGreaterThan(50);
        for (Face face : bengali) {
            List<String> outside = need.stream().filter(c -> !face.covers(c)).map(c -> String.format("U+%04X", c)).toList();
            assertThat(outside).as("Bangla copy outside the weight-" + face.weight() + " Bengali face's unicode-range").isEmpty();
        }
    }

    /** A range is a promise the file must keep: a code point claimed by a face but not in its cmap draws as a box. */
    @Test
    void everyCodePointAFaceClaimsForTheCopyIsInTheServedFontsCmap() throws Exception {
        WebUiTestKit kit = new WebUiTestKit();
        List<Face> faces = faces(kit.servedText("/assets/baymax.css"));
        assertThat(faces).as("self-hosted faces in the served CSS").hasSizeGreaterThanOrEqualTo(2);
        Set<Integer> copy = copy(List.of(Lang.BN, Lang.EN));
        assertThat(copy).as("code points in the copy").hasSizeGreaterThan(100);
        int checked = 0;
        for (Face face : faces) {
            Set<Integer> cmap = Woff2.read(kit.served(face.url())).codePoints();
            List<Integer> claimed = copy.stream().filter(face::covers).toList();
            assertThat(claimed).as(face.family() + " " + face.weight() + " covers some of the copy").isNotEmpty();
            List<String> missing = claimed.stream().filter(c -> !cmap.contains(c)).map(c -> String.format("U+%04X", c)).toList();
            assertThat(missing).as(face.url() + " lacks code points its face claims and the copy uses").isEmpty();
            checked += claimed.size();
        }
        assertThat(checked).isGreaterThan(100);
    }

    /** Bold headings are bold: each face's weight is inside the file's real weight axis, and Bangla shaping is there. */
    @Test
    void eachFacesWeightIsOnTheFontsAxisAndTheBengaliFileShapesBangla() {
        WebUiTestKit kit = new WebUiTestKit();
        List<Face> faces = faces(kit.servedText("/assets/baymax.css"));
        assertThat(faces).extracting(Face::weight).contains(400, 600, 700);
        for (Face face : faces) {
            double[] axis = Woff2.read(kit.served(face.url())).weightAxis();
            assertThat(face.weight()).as(face.url() + " wght axis " + axis[0] + "–" + axis[1])
                    .isBetween((int) axis[0], (int) axis[1]);
        }
        String bengali = faces.stream().filter(f -> f.family().equals("Noto Sans Bengali")).findFirst().orElseThrow().url();
        assertThat(Woff2.read(kit.served(bengali)).substitutionScripts()).as("GSUB scripts: conjuncts need bng2").contains("bng2");
    }
}

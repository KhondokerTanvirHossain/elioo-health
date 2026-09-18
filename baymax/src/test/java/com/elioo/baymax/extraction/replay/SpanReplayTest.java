package com.elioo.baymax.extraction.replay;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.service.CropCutter;
import com.elioo.baymax.extraction.application.service.PageRenderer;
import com.elioo.baymax.extraction.application.service.SpanLocator;
import com.elioo.baymax.extraction.domain.ExtractionResult;
import com.elioo.baymax.extraction.domain.PageOcr;
import com.elioo.healthcare.gcp.common.config.GcpCommonAutoConfiguration;
import com.elioo.healthcare.gcp.vision.api.VisionService;
import com.elioo.healthcare.gcp.vision.config.VisionAutoConfiguration;
import com.elioo.healthcare.gcp.vision.model.VisionOcrRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BMX-5b replay: stored extraction_json against freshly derived OCR geometry, no model calls. For every
 * item it reports how the pre-fix resolution fared (offsets only) and how the anchored resolution fares,
 * and names the failure mode of anything still dropped. Corpus documents print counts and modes only;
 * the synthetic document (label starting "synthetic") may print its text.
 *
 * <p>Gated: {@code RUN_SPAN_REPLAY=true SPAN_REPLAY_MANIFEST=<path>} with GCP credentials in the env.
 */
@EnabledIfEnvironmentVariable(named = "RUN_SPAN_REPLAY", matches = "true")
class SpanReplayTest {

    record Item(String section, String anchor, ExtractionResult.SourceSpan span, String name, String value) {
        Item(String section, String anchor, ExtractionResult.SourceSpan span) {
            this(section, anchor, span, null, null);
        }
    }

    @Test
    void replayStoredSpansAgainstFreshGeometry() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode manifest = mapper.readTree(Files.readString(Path.of(System.getenv("SPAN_REPLAY_MANIFEST"))));
        PageRenderer renderer = new PageRenderer();
        BaymaxProperties properties = new BaymaxProperties();
        CropCutter cutter = new CropCutter(properties);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GcpCommonAutoConfiguration.class, VisionAutoConfiguration.class))
                .withPropertyValues("gcp.enabled=true", "gcp.vision.enabled=true",
                        "gcp.project-id=" + System.getenv().getOrDefault("GCP_PROJECT_ID", "replay"),
                        "gcp.credentials-path=" + System.getenv("GOOGLE_APPLICATION_CREDENTIALS"))
                .run(context -> {
                    VisionService vision = context.getBean(VisionService.class);
                    Map<String, Integer> beforeTotal = new TreeMap<>(), afterTotal = new TreeMap<>();
                    Map<String, Map<String, Integer>> beforeBySection = new TreeMap<>(), afterBySection = new TreeMap<>();
                    Map<CropCutter.Outcome, Integer> remaining = new EnumMap<>(CropCutter.Outcome.class);
                    int wrongTextBefore = 0, items = 0;
                    List<String> remainingNamed = new ArrayList<>();

                    for (JsonNode doc : manifest) {
                        String label = doc.get("label").asText();
                        boolean synthetic = label.startsWith("synthetic");
                        byte[] bytes = Files.readAllBytes(Path.of(doc.get("image").asText()));
                        List<byte[]> pages = renderer.toJpegPages(bytes, doc.get("image").asText(), 10);
                        Map<Integer, PageOcr> byPage = new HashMap<>();
                        for (int i = 0; i < pages.size(); i++) {
                            String b64 = Base64.getEncoder().encodeToString(pages.get(i));
                            var response = vision.detectDocumentText(VisionOcrRequest.withLanguages(b64, List.of("bn", "en"))).block();
                            byPage.put(i + 1, PageOcr.from(i + 1, b64, response));
                        }
                        // the stored JSON is the pipeline's re-serialisation (it carries the derived `usable`
                        // flag the schema forbids), so parse leniently rather than through the validator
                        ExtractionResult result = mapper.copy()
                                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                                .readValue(doc.get("extraction_json").asText(), ExtractionResult.class);
                        List<Item> list = items(result);
                        int docBefore = 0, docAfter = 0, docWrong = 0;
                        Map<CropCutter.Outcome, Integer> docModes = new EnumMap<>(CropCutter.Outcome.class);
                        for (Item it : list) {
                            items++;
                            // before: offsets only, as the pipeline resolved them until now
                            @SuppressWarnings("deprecation")
                            Optional<byte[]> old = cutter.cut(it.span(), byPage);
                            boolean oldResolved = old.isPresent();
                            boolean oldCorrect = false;
                            if (oldResolved) {
                                PageOcr page = byPage.get(it.span().page());
                                List<PageOcr.PositionedWord> hits = page.words().stream()
                                        .filter(w -> w.left() >= 0 && w.overlaps(it.span().start(), it.span().end())).toList();
                                oldCorrect = SpanLocator.contains(hits, it.anchor());
                                if (!oldCorrect) {
                                    docWrong++;
                                    wrongTextBefore++;
                                }
                            }
                            // after: anchored
                            CropCutter.Cut cut = it.value() != null ? cutter.cutValue(it.span(), it.name(), it.value(), byPage)
                                    : cutter.cut(it.span(), it.anchor(), byPage);
                            boolean newResolved = cut.bytes().isPresent();
                            docBefore += oldResolved ? 1 : 0;
                            docAfter += newResolved ? 1 : 0;
                            beforeBySection.computeIfAbsent(it.section(), k -> new TreeMap<>()).merge(oldResolved ? "resolved" : "dropped", 1, Integer::sum);
                            afterBySection.computeIfAbsent(it.section(), k -> new TreeMap<>()).merge(newResolved ? "resolved" : "dropped", 1, Integer::sum);
                            if (!newResolved) {
                                remaining.merge(cut.outcome(), 1, Integer::sum);
                                remainingNamed.add(label + " " + it.section() + " " + cut.outcome()
                                        + (synthetic ? " '" + it.anchor() + "'" : " (anchor " + it.anchor().length() + " chars, span "
                                        + it.span().start() + "-" + it.span().end() + ")"));
                            } else {
                                docModes.merge(cut.outcome(), 1, Integer::sum);
                            }
                            if (synthetic) {
                                PageOcr page = byPage.get(it.span().page());
                                String at = page == null ? "(no page)" : page.text().substring(
                                        Math.min(it.span().start(), page.text().length()), Math.min(it.span().end(), page.text().length())).replace('\n', '⏎');
                                Optional<SpanLocator.Range> where = page == null ? Optional.empty() : SpanLocator.locate(page, it.span(), it.anchor());
                                System.out.printf("  %-11s anchor=%-34s claimed=[%d,%d) textAtClaim='%s' located=%s drift=%s -> before=%s after=%s%n",
                                        it.section(), "'" + it.anchor() + "'", it.span().start(), it.span().end(), at,
                                        where.map(r -> "[" + r.start() + "," + r.end() + ")").orElse("none"),
                                        where.map(r -> String.valueOf(r.start() - it.span().start())).orElse("-"),
                                        oldResolved ? (oldCorrect ? "ok" : "WRONG-TEXT") : "dropped", cut.outcome());
                            }
                        }
                        PageOcr p1 = byPage.get(1);
                        System.out.printf("doc %-10s pageTextChars=%d words=%d boxed=%d items=%d before=%d (wrong-text %d) after=%d modes=%s%n",
                                label, p1 == null ? 0 : p1.text().length(), p1 == null ? 0 : p1.words().size(),
                                p1 == null ? 0 : (int) p1.words().stream().filter(w -> w.left() >= 0).count(),
                                list.size(), docBefore, docWrong, docAfter, docModes);
                        beforeTotal.merge("resolved", docBefore, Integer::sum);
                        afterTotal.merge("resolved", docAfter, Integer::sum);
                    }
                    System.out.println();
                    System.out.println("TOTAL items=" + items + " before=" + beforeTotal + " wrongTextBefore=" + wrongTextBefore + " after=" + afterTotal);
                    System.out.println("per section before=" + beforeBySection);
                    System.out.println("per section after =" + afterBySection);
                    System.out.println("remaining dropped by mode=" + remaining);
                    remainingNamed.forEach(r -> System.out.println("  still dropped: " + r));
                    assertThat(afterTotal.getOrDefault("resolved", 0)).isGreaterThanOrEqualTo(beforeTotal.getOrDefault("resolved", 0) - wrongTextBefore);
                });
    }

    /** Every item with a span, with the text its crop must contain. */
    static List<Item> items(ExtractionResult r) {
        List<Item> out = new ArrayList<>();
        for (var v : r.valuesOrEmpty()) {
            out.add(new Item("values", (v.name() == null ? "" : v.name() + " ") + (v.value() == null ? "" : v.value()), v.sourceSpan(), v.name(), v.value()));
        }
        for (var m : r.medicinesOrEmpty()) {
            out.add(new Item("medicines", m.name(), m.sourceSpan()));
        }
        for (var f : r.followUpOrEmpty()) {
            out.add(new Item("follow_up", f.instruction(), f.sourceSpan()));
        }
        for (var c : com.elioo.baymax.extraction.application.service.DocumentExtractionService.contextItems(r)) {
            out.add(new Item("context", c.text(), c.span()));
        }
        return out;
    }
}

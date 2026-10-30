package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.domain.TextNormaliser;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.outbound.domain.EmergencyPhrases;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The চ্যাট's emergency pre-check (DR-50 envelope 1): decides, in code and before any model call, whether a typed question
 * describes an emergency. Two sources, in this order:
 * <ol>
 *   <li>the D11 red-flag categories of {@value #RESOURCE}, each phrase normalised at load as the question is
 *       ({@link TextNormaliser}) and matched from the start of a word, so an inflected Bangla form ("শ্বাসকষ্টে",
 *       "খিঁচুনিতে") or an English plural fires; a single Latin word of {@value #SHORT_WORD} letters or fewer ("fits",
 *       "hypo") fires only as a whole word, so "benefits" and "hypothyroid" do not. A {@code proposed} row never fires;</li>
 *   <li>the document emergency words ({@code baymax.outbound.emergency-phrases}), whole-token, exactly as
 *       {@code UrgencyService} matches them on a paper ({@link EmergencyPhrases}); category {@value #DOCUMENT_WORD}.</li>
 * </ol>
 * Zero model calls and no I/O after construction. The matched category is a machine code, never logged with the question.
 */
@Component
public class RedFlagMatcher {

    public static final String RESOURCE = "chat-red-flags.properties";
    public static final String DOCUMENT_WORD = "document_word";
    static final int SHORT_WORD = 4;

    private static final Pattern PHRASE_KEY = Pattern.compile("^([a-z0-9_]+)\\.(bn|banglish|en)\\.(\\d+)$");
    private static final Pattern SHORT_LATIN = Pattern.compile("^[a-z]{1," + SHORT_WORD + "}$");

    /** One phrase as written in the file, and the script its key declares. */
    public record Phrase(String script, String text) {
    }

    /** One category's rows. */
    public record Category(String name, boolean active, String source, List<Phrase> phrases) {
    }

    /** One phrase in the question's normal form, and whether it must also end at a word break. */
    private record Needle(String category, String normalised, boolean wholeWord) {
    }

    private final List<Needle> needles;
    private final Map<String, List<String>> activePhrases;
    private final List<String> documentWords;

    @Autowired
    public RedFlagMatcher(BaymaxProperties properties) {
        this(load(RESOURCE), properties.getOutbound().getEmergencyPhrases());
    }

    public RedFlagMatcher(Properties rows, List<String> documentWords) {
        Map<String, List<String>> active = new LinkedHashMap<>();
        List<Needle> all = new ArrayList<>();
        for (Category c : categories(rows).values()) {
            if (!c.active()) {
                continue;
            }
            List<String> texts = new ArrayList<>();
            for (Phrase p : c.phrases()) {
                String n = TextNormaliser.normalise(p.text());
                if (n.isEmpty()) {
                    continue;
                }
                texts.add(p.text());
                all.add(new Needle(c.name(), n, SHORT_LATIN.matcher(n).matches()));
            }
            active.put(c.name(), Collections.unmodifiableList(texts));
        }
        // the longest phrase first, so a category whose phrase contains another category's shorter one wins with its own
        all.sort(Comparator.comparingInt((Needle n) -> n.normalised().length()).reversed());
        this.needles = List.copyOf(all);
        this.activePhrases = Collections.unmodifiableMap(active);
        this.documentWords = documentWords == null ? List.of() : List.copyOf(documentWords);
    }

    /** The emergency category the question falls in, or empty when it describes none. */
    public Optional<String> match(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        String hay = " " + TextNormaliser.normalise(question) + " ";
        for (Needle n : needles) {
            String start = " " + n.normalised();
            int at = hay.indexOf(start);
            while (at >= 0) {
                if (!n.wholeWord() || hay.charAt(at + start.length()) == ' ') {
                    return Optional.of(n.category());
                }
                at = hay.indexOf(start, at + 1);
            }
        }
        return EmergencyPhrases.contains(question, documentWords) ? Optional.of(DOCUMENT_WORD) : Optional.empty();
    }

    /** The active categories and their phrases as written, in file order: what {@link #match} fires on. */
    public Map<String, List<String>> activePhrases() {
        return activePhrases;
    }

    /** Every category of the rows, by name in file order of first key; a category with no status is proposed. */
    public static Map<String, Category> categories(Properties rows) {
        Map<String, TreeMap<String, Phrase>> phrases = new TreeMap<>();
        for (String key : rows.stringPropertyNames()) {
            Matcher m = PHRASE_KEY.matcher(key);
            if (m.matches()) {
                String order = m.group(2) + "." + String.format("%04d", Integer.parseInt(m.group(3)));
                phrases.computeIfAbsent(m.group(1), k -> new TreeMap<>()).put(order, new Phrase(m.group(2), rows.getProperty(key).strip()));
            }
        }
        Map<String, Category> out = new LinkedHashMap<>();
        for (Map.Entry<String, TreeMap<String, Phrase>> e : phrases.entrySet()) {
            String name = e.getKey();
            boolean active = "active".equals(rows.getProperty(name + ".status", "proposed").strip());
            out.put(name, new Category(name, active, rows.getProperty(name + ".source"), List.copyOf(e.getValue().values())));
        }
        return out;
    }

    /** The rows of a classpath properties file, read as UTF-8; a missing file is a startup failure. */
    public static Properties load(String resource) {
        Properties p = new Properties();
        try (InputStream in = RedFlagMatcher.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing " + resource + ": the chat's emergency pre-check has no phrases");
            }
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + resource, e);
        }
        return p;
    }
}

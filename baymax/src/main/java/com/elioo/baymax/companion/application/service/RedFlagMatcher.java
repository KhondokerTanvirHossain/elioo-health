package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.domain.TextNormaliser;
import com.elioo.baymax.config.BaymaxProperties;
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
 * describes an emergency, from the D11 red-flag categories of {@value #RESOURCE}. Two kinds of row:
 * <ul>
 *   <li>a <b>phrase</b>, normalised at load as the question is ({@link TextNormaliser}) and matched from the start of a
 *       word, so an inflected Bangla form ("শ্বাসকষ্টে", "খিঁচুনিতে") or an English plural fires; a single Latin word of
 *       {@value #SHORT_WORD} letters or fewer ("fits", "hypo") fires only as a whole word, so "hypothyroid" does not;</li>
 *   <li>a <b>near</b> row, parts joined by {@code " + "} ("বুক + ব্যথা", "chest + pain"): every part, each matched as a
 *       phrase is, starts within {@value #NEAR_WINDOW} words of the others, in any order, so "বুকে খুব ব্যথা হচ্ছে" and
 *       "pain in my chest" fire where a contiguous phrase misses (review of #164, finding 10).</li>
 * </ul>
 * A {@code proposed} row never fires. The document emergency words ({@code baymax.outbound.emergency-phrases}: urgent,
 * admission, জরুরি, ভর্তি…) are deliberately NOT used: they were chosen for a doctor's paper, and in a family's question
 * ("is this report urgent?", "ভর্তির তারিখ") they are routine words (review of #164, finding 13). Zero model calls and no
 * I/O after construction. The matched category is a machine code, never logged with the question.
 */
@Component
public class RedFlagMatcher {

    public static final String RESOURCE = "chat-red-flags.properties";
    static final int SHORT_WORD = 4;
    /** The most words a near row's parts may start apart. */
    static final int NEAR_WINDOW = 8;

    private static final Pattern PHRASE_KEY = Pattern.compile("^([a-z0-9_]+)\\.(bn|banglish|en)\\.(\\d+)$");
    private static final Pattern SHORT_LATIN = Pattern.compile("^[a-z]{1," + SHORT_WORD + "}$");
    private static final Pattern NEAR_JOIN = Pattern.compile("\\s\\+\\s");
    /** A word of a row that stands for any number in the question. */
    static final String ANY_NUMBER = "anynumber";
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** One phrase as written in the file, and the script its key declares. */
    public record Phrase(String script, String text) {
    }

    /** One category's rows. */
    public record Category(String name, boolean active, String source, List<Phrase> phrases) {
    }

    /** One part in the question's normal form, as words, and whether its last word must also end at a word break. */
    private record Part(List<String> words, boolean wholeWord) {

        static Part of(String normalised) {
            return new Part(List.of(normalised.split(" ")), SHORT_LATIN.matcher(normalised).matches());
        }

        /**
         * The word positions in {@code tokens} where this part starts: every word but the last exact, the last a stem; a
         * word written {@value #ANY_NUMBER} is any number ("took anynumber tablet" is "took 10 tablets").
         */
        List<Integer> startsIn(String[] tokens) {
            List<Integer> at = new ArrayList<>();
            int last = words.size() - 1;
            for (int i = 0; i + last < tokens.length; i++) {
                boolean same = true;
                for (int k = 0; k < last && same; k++) {
                    same = ANY_NUMBER.equals(words.get(k)) ? DIGITS.matcher(tokens[i + k]).matches() : tokens[i + k].equals(words.get(k));
                }
                String end = tokens[i + last];
                if (same && (wholeWord ? end.equals(words.get(last)) : end.startsWith(words.get(last)))) {
                    at.add(i);
                }
            }
            return at;
        }
    }

    /** One row: a phrase is one part, a near row two or more; its normalised length orders the search. */
    private record Needle(String category, List<Part> parts, int length) {

        boolean firesIn(String[] tokens) {
            List<List<Integer>> starts = new ArrayList<>();
            for (Part p : parts) {
                List<Integer> at = p.startsIn(tokens);
                if (at.isEmpty()) {
                    return false;
                }
                starts.add(at);
            }
            return parts.size() == 1 || within(starts, 0, Integer.MAX_VALUE, Integer.MIN_VALUE);
        }

        /** Whether one start per part can be chosen with all of them inside the window. */
        private static boolean within(List<List<Integer>> starts, int k, int min, int max) {
            if (k == starts.size()) {
                return max - min <= NEAR_WINDOW;
            }
            for (int at : starts.get(k)) {
                if (within(starts, k + 1, Math.min(min, at), Math.max(max, at))) {
                    return true;
                }
            }
            return false;
        }
    }

    private final List<Needle> needles;
    private final Map<String, List<String>> activePhrases;

    @Autowired
    public RedFlagMatcher(BaymaxProperties properties) {
        this(load(RESOURCE));
    }

    public RedFlagMatcher(Properties rows) {
        Map<String, List<String>> active = new LinkedHashMap<>();
        List<Needle> all = new ArrayList<>();
        for (Category c : categories(rows).values()) {
            if (!c.active()) {
                continue;
            }
            List<String> texts = new ArrayList<>();
            for (Phrase p : c.phrases()) {
                List<Part> parts = new ArrayList<>();
                int length = 0;
                for (String piece : NEAR_JOIN.split(p.text())) {
                    String n = TextNormaliser.normalise(piece);
                    if (!n.isEmpty()) {
                        parts.add(Part.of(n));
                        length += n.length();
                    }
                }
                if (parts.isEmpty()) {
                    continue;
                }
                texts.add(p.text());
                all.add(new Needle(c.name(), List.copyOf(parts), length));
            }
            active.put(c.name(), Collections.unmodifiableList(texts));
        }
        // the longest row first, so a category whose phrase contains another category's shorter one wins with its own
        all.sort(Comparator.comparingInt(Needle::length).reversed());
        this.needles = List.copyOf(all);
        this.activePhrases = Collections.unmodifiableMap(active);
    }

    /** The emergency category the question falls in, or empty when it describes none. */
    public Optional<String> match(String question) {
        String normal = TextNormaliser.normalise(question);
        if (normal.isEmpty()) {
            return Optional.empty();
        }
        String[] tokens = normal.split(" ");
        for (Needle n : needles) {
            if (n.firesIn(tokens)) {
                return Optional.of(n.category());
            }
        }
        return Optional.empty();
    }

    /** The active categories and their rows as written, in file order: what {@link #match} fires on. */
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

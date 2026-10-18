package com.elioo.baymax.web.adapter.in.ui;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The bundle files as written, line by line: what a family's string actually is before Properties merges it. */
final class BundleFiles {

    private static final Pattern KEY = Pattern.compile("^\\s*([^#!\\s][^=:\\s]*)\\s*[=:]");

    private BundleFiles() {
    }

    static List<String> lines(String part, Lang lang) throws IOException {
        return Files.readAllLines(Path.of("src/main/resources/baymax/" + part + "_" + lang.code + ".properties"), StandardCharsets.UTF_8);
    }

    /** key → 1-based line of its first definition; continuation lines (after a trailing backslash) are skipped. */
    static Map<String, Integer> keys(String part, Lang lang) throws IOException {
        Map<String, Integer> out = new LinkedHashMap<>();
        List<String> lines = lines(part, lang);
        boolean continued = false;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (!continued) {
                Matcher m = KEY.matcher(l);
                if (m.find()) {
                    out.putIfAbsent(m.group(1), i + 1);
                }
            }
            continued = l.endsWith("\\") && !l.endsWith("\\\\");
        }
        return out;
    }

    static List<String> duplicatesWithin(String part, Lang lang) throws IOException {
        Map<String, Integer> seen = new HashMap<>();
        List<String> twice = new ArrayList<>();
        boolean continued = false;
        for (String l : lines(part, lang)) {
            if (!continued) {
                Matcher m = KEY.matcher(l);
                if (m.find() && seen.merge(m.group(1), 1, Integer::sum) == 2) {
                    twice.add(m.group(1));
                }
            }
            continued = l.endsWith("\\") && !l.endsWith("\\\\");
        }
        return twice;
    }

    /** key → value of one UI bundle part, loaded with Properties in UTF-8 (escapes resolved, as a page sees it). */
    static Map<String, String> values(String part, Lang lang) throws IOException {
        return load(Path.of("src/main/resources/baymax/" + part + "_" + lang.code + ".properties"));
    }

    /** key → value of the WhatsApp message bundle {@code messages_<lang>.properties}. */
    static Map<String, String> messages(Lang lang) throws IOException {
        return load(Path.of("src/main/resources/baymax/messages_" + lang.code + ".properties"));
    }

    private static Map<String, String> load(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        Map<String, String> out = new TreeMap<>();
        p.stringPropertyNames().forEach(k -> out.put(k, p.getProperty(k)));
        return out;
    }
}

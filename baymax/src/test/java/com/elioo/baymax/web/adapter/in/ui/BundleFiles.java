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

    /** A key is the first token of a line that is not a comment: Properties ends it at '=', ':' or whitespace. */
    private static final Pattern KEY = Pattern.compile("^\\s*([^#!\\s][^=:\\s]*)");

    /** The file name of a UI bundle part: {@code ui_bn.properties}, {@code ui-shell_en.properties}, … */
    static final Pattern PART_FILE = Pattern.compile("ui(-[a-z0-9]+)*_(bn|en)\\.properties");

    private BundleFiles() {
    }

    static List<String> lines(String part, Lang lang) throws IOException {
        return Files.readAllLines(Path.of("src/main/resources/baymax/" + part + "_" + lang.code + ".properties"), StandardCharsets.UTF_8);
    }

    /** key → 1-based line of its first definition; continuation lines (after a trailing backslash) are skipped. */
    static Map<String, Integer> keys(String part, Lang lang) throws IOException {
        return keys(lines(part, lang));
    }

    static List<String> duplicatesWithin(String part, Lang lang) throws IOException {
        return duplicatesWithin(lines(part, lang));
    }

    /** The key each line defines, null for a comment, a blank or a continuation line. */
    private static List<String> keyPerLine(List<String> lines) {
        List<String> out = new ArrayList<>();
        boolean continued = false;
        for (String l : lines) {
            Matcher m = KEY.matcher(l);
            out.add(!continued && m.find() ? m.group(1) : null);
            continued = l.endsWith("\\") && !l.endsWith("\\\\");
        }
        return out;
    }

    static Map<String, Integer> keys(List<String> lines) {
        Map<String, Integer> out = new LinkedHashMap<>();
        List<String> keys = keyPerLine(lines);
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i) != null) {
                out.putIfAbsent(keys.get(i), i + 1);
            }
        }
        return out;
    }

    static List<String> duplicatesWithin(List<String> lines) {
        Map<String, Integer> seen = new HashMap<>();
        List<String> twice = new ArrayList<>();
        for (String key : keyPerLine(lines)) {
            if (key != null && seen.merge(key, 1, Integer::sum) == 2) {
                twice.add(key);
            }
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

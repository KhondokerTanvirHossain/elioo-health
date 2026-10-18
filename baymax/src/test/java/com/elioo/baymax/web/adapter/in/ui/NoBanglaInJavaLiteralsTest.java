package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** A Bengali code point inside a Java string literal is copy the English page and the copy owner never see. */
class NoBanglaInJavaLiteralsTest {

    private static final Path WEB = Path.of("src/main/java/com/elioo/baymax/web/adapter/in");
    /** file → literal fragment, each with why it is not copy. A ratchet: an unused allowance fails. */
    private static final Map<String, String> ALLOWED = Map.of(
            "SignInPages.java#[0-9০-৯+", "the number field's pattern accepts Bangla digits (input, not copy)",
            "SignInPages.java#[0-9০-৯]{6}", "the code field's pattern accepts Bangla digits (input, not copy)",
            "SignInPages.java#\"কোড\"", "the WhatsApp keyword WaTextRouter matches; the link pre-fills it",
            "AuthHandler.java#কোড", "the JSON API's machine message names the WhatsApp keyword");

    /**
     * Every string literal and text block of a Java source, quotes included, in order; comments and char literals are
     * skipped. A lexer rather than a regex over comment-stripped text: stripping {@code //…} also cut a line after a URL
     * literal ("https://…") and so hid any literal behind it, and a regex for one-line literals never sees a text block.
     */
    static List<String> literals(String src) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int end = src.indexOf('\n', i);
                i = end < 0 ? n : end;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '\'') {
                i = skipQuoted(src, i, '\'');
            } else if (src.startsWith("\"\"\"", i)) {
                int end = i + 3;
                while (end < n && !(src.startsWith("\"\"\"", end) && src.charAt(end - 1) != '\\')) {
                    end++;
                }
                end = Math.min(n, end + 3);
                out.add(src.substring(i, end));
                i = end;
            } else if (c == '"') {
                int end = skipQuoted(src, i, '"');
                out.add(src.substring(i, end));
                i = end;
            } else {
                i++;
            }
        }
        return out;
    }

    /** The index just past the closing quote of the one-line literal opening at {@code start}. */
    private static int skipQuoted(String src, int start, char quote) {
        int i = start + 1;
        while (i < src.length() && src.charAt(i) != quote && src.charAt(i) != '\n') {
            i += src.charAt(i) == '\\' ? 2 : 1;
        }
        return Math.min(src.length(), i + 1);
    }

    /** The lexer itself, on the shapes that defeated the regex: a URL before a literal, a text block, a comment. */
    @Test
    void theLexerFindsLiteralsAfterAUrlAndInsideTextBlocksButNotInComments() {
        String src = "String a = \"https://wa.me/\" + \"এক\"; // \"দুই\"\n/* \"তিন\" */ char q = '\"';\n"
                + "String b = \"\"\"\n  <p>চার</p>\n  \"\"\";\nString c = \"x\\\"y\";";
        assertThat(literals(src)).containsExactly("\"https://wa.me/\"", "\"এক\"", "\"\"\"\n  <p>চার</p>\n  \"\"\"", "\"x\\\"y\"");
    }

    @Test
    void noBanglaInAnyStringLiteralOfTheWebAdapters() throws IOException {
        List<String> found = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(WEB)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(files).as("sources read").hasSizeGreaterThan(8);
        for (Path p : files) {
            for (String literal : literals(Files.readString(p))) {
                if (literal.codePoints().anyMatch(c -> c >= 0x0980 && c <= 0x09FF)) {
                    String hit = p.getFileName() + "#" + literal;
                    if (ALLOWED.keySet().stream().noneMatch(a -> hit.startsWith(a.substring(0, a.indexOf('#') + 1)) && hit.contains(a.substring(a.indexOf('#') + 1)))) {
                        found.add(hit);
                    }
                }
            }
        }
        assertThat(found).isEmpty();
        for (String a : ALLOWED.keySet()) {
            Path f = files.stream().filter(p -> p.getFileName().toString().equals(a.substring(0, a.indexOf('#')))).findFirst().orElseThrow();
            assertThat(Files.readString(f)).as("allowance " + a + " still needed").contains(a.substring(a.indexOf('#') + 1));
        }
    }
}

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
    /**
     * file → literal fragment, each with why it is not copy. The fragment is removed from a literal before the literal is
     * read, so Bangla left beside it is still a hit; a fragment is the whole of what is allowed, never a word that copy
     * could also contain. A ratchet: an allowance no literal of its file carries any more fails.
     */
    private static final Map<String, String> ALLOWED = Map.of(
            "SignInPages.java#[0-9০-৯+ \\\\-]{8,24}", "the number field's pattern accepts Bangla digits (input, not copy)",
            "SignInPages.java#[0-9০-৯]{6}", "the code field's pattern accepts Bangla digits (input, not copy)",
            "WaLink.java#\"কোড\"", "the WhatsApp keyword WaTextRouter matches; the link pre-fills it",
            "AuthHandler.java#send the word কোড", "the JSON API's machine message names the WhatsApp keyword");

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

    /** Copy written into the same literal as an allowed fragment is copy: the allowance covers the fragment only. */
    @Test
    void banglaCopyAppendedToTheAllowedMessageIsStillCaught() {
        String auth = "Map.of(\"message\", \"sign-in codes arrive on WhatsApp: if you do not have one, send the word কোড"
                + " to Medioo on WhatsApp — কোড পেতে মেডিওকে লিখুন\");";
        assertThat(violations("AuthHandler.java", auth)).as("copy after the keyword").hasSize(1);
        assertThat(violations("AuthHandler.java", auth.replace(" — কোড পেতে মেডিওকে লিখুন", ""))).as("the keyword alone").isEmpty();
    }

    @Test
    void banglaCopyInsideTheAllowedPatternLiteralIsStillCaught() {
        String signIn = "+ \" pattern=\\\"[0-9০-৯+ \\\\-]{8,24}\\\" placeholder=\\\"আপনার মোবাইল নম্বর\\\" value=\\\"\"";
        assertThat(violations("SignInPages.java", signIn)).as("a placeholder beside the pattern").hasSize(1);
        assertThat(violations("SignInPages.java", signIn.replace(" placeholder=\\\"আপনার মোবাইল নম্বর\\\"", ""))).as("the pattern alone").isEmpty();
    }

    /** An allowance is still needed only while a literal carries its fragment; a comment that mentions it does not count. */
    @Test
    void anAllowanceWhoseLiteralIsGoneIsUnusedEvenWhileACommentStillSaysIt() {
        Map<String, String> commentOnly = Map.of("AuthHandler.java",
                "// the reply tells them to send the word কোড to Medioo on WhatsApp\nString m = \"sign-in codes arrive on WhatsApp\";");
        Map<String, String> literal = Map.of("AuthHandler.java",
                "String m = \"if you do not have one, send the word কোড to Medioo on WhatsApp\";");
        assertThat(unused(commentOnly)).as("only a comment says it").anyMatch(a -> a.startsWith("AuthHandler.java#"));
        assertThat(unused(literal)).as("a literal carries it").noneMatch(a -> a.startsWith("AuthHandler.java#"));
    }

    @Test
    void noBanglaInAnyStringLiteralOfTheWebAdapters() throws IOException {
        Map<String, String> sources = new java.util.TreeMap<>();
        try (Stream<Path> walk = Files.walk(WEB)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                sources.put(p.getFileName().toString(), Files.readString(p));
            }
        }
        assertThat(sources).as("sources read").hasSizeGreaterThan(8);
        List<String> found = new ArrayList<>();
        sources.forEach((name, src) -> found.addAll(violations(name, src)));
        assertThat(found).isEmpty();
        assertThat(unused(sources)).as("allowances no literal needs any more (the ratchet only shrinks)").isEmpty();
    }

    private static boolean bangla(String s) {
        return s.codePoints().anyMatch(c -> c >= 0x0980 && c <= 0x09FF);
    }

    private static String file(String allowance) {
        return allowance.substring(0, allowance.indexOf('#'));
    }

    private static String fragment(String allowance) {
        return allowance.substring(allowance.indexOf('#') + 1);
    }

    /** {@code file#literal} for every literal of one source that still carries Bangla once its file's allowed fragments are removed. */
    static List<String> violations(String fileName, String src) {
        List<String> mine = ALLOWED.keySet().stream().filter(a -> file(a).equals(fileName)).map(NoBanglaInJavaLiteralsTest::fragment).toList();
        List<String> out = new ArrayList<>();
        for (String literal : literals(src)) {
            String rest = literal;
            for (String fragment : mine) {
                rest = rest.replace(fragment, "");
            }
            if (bangla(rest)) {
                out.add(fileName + "#" + literal);
            }
        }
        return out;
    }

    /** The allowances that no string literal of their file carries any more (comments do not count). */
    static List<String> unused(Map<String, String> sources) {
        return ALLOWED.keySet().stream()
                .filter(a -> literals(sources.getOrDefault(file(a), "")).stream().noneMatch(l -> l.contains(fragment(a))))
                .sorted().toList();
    }
}

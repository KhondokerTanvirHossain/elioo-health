package com.elioo.baymax.web.adapter.in.ui;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The colour tokens of the served stylesheet, per theme. No hex is written in Java (DesignTokensTest). */
final class Tokens {

    private static final Pattern LIGHT = Pattern.compile(":root\\s*\\{([^}]*)}");
    private static final Pattern DARK_MEDIA = Pattern.compile(
            "@media\\s*\\(prefers-color-scheme:\\s*dark\\)\\s*\\{\\s*:root:not\\(\\[data-theme=light]\\)\\s*\\{([^}]*)}\\s*}");
    private static final Pattern DARK_ATTR = Pattern.compile(":root\\[data-theme=dark]\\s*\\{([^}]*)}");
    private static final Pattern DECL = Pattern.compile("(--[a-z0-9-]+)\\s*:\\s*([^;]+);");

    static final String CSS = new String(AssetHandler.stylesheet(), StandardCharsets.UTF_8);
    static final String LIGHT_BG = light(CSS).get("--c-bg");
    static final String DARK_BG = dark(CSS).get("--c-bg");

    private Tokens() {
    }

    static String withoutComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    static Map<String, String> light(String css) {
        return block(LIGHT, css);
    }

    static Map<String, String> dark(String css) {
        return block(DARK_MEDIA, css);
    }

    static Map<String, String> darkAttribute(String css) {
        return block(DARK_ATTR, css);
    }

    /** The stylesheet with the three token blocks and every @font-face removed: the rules that style pages. */
    static String rules(String css) {
        return withoutComments(css).replaceAll(":root\\s*\\{[^}]*}", "")
                .replaceAll("@media\\s*\\(prefers-color-scheme:\\s*dark\\)\\s*\\{\\s*:root:not\\(\\[data-theme=light]\\)\\s*\\{[^}]*}\\s*}", "")
                .replaceAll(":root\\[data-theme=dark]\\s*\\{[^}]*}", "")
                .replaceAll("@font-face\\s*\\{[^}]*}", "");
    }

    private static Map<String, String> block(Pattern p, String css) {
        Matcher m = p.matcher(withoutComments(css));
        Map<String, String> out = new LinkedHashMap<>();
        if (m.find()) {
            Matcher d = DECL.matcher(m.group(1));
            while (d.find()) {
                out.put(d.group(1), d.group(2).trim());
            }
        }
        return out;
    }
}

package com.elioo.baymax.web.adapter.in.ui;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Every visible string of the landing page and the app, per language, from {@code baymax/ui_bn.properties}
 * and {@code baymax/ui_en.properties}. The two files carry the same keys (asserted in a test), so the toggle
 * can never leave a string behind in the other language. Placeholders are {@code {0}}, {@code {1}}… and are
 * replaced verbatim — no MessageFormat, so an apostrophe in copy is just an apostrophe.
 */
@Component
public class UiCopy {

    private final Map<Lang, Properties> bundles = new EnumMap<>(Lang.class);

    public UiCopy() {
        for (Lang lang : Lang.values()) {
            bundles.put(lang, load("/baymax/ui_" + lang.code + ".properties"));
        }
    }

    private static Properties load(String path) {
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(UiCopy.class.getResourceAsStream(path), StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException | NullPointerException e) {
            throw new UncheckedIOException("missing UI copy " + path, e instanceof IOException io ? io : new IOException(e));
        }
        return p;
    }

    /** The string, HTML-escaped, with {@code {n}} replaced by the escaped arguments. A missing key is loud. */
    public String t(Lang lang, String key, Object... args) {
        String raw = bundles.get(lang).getProperty(key);
        if (raw == null) {
            throw new IllegalArgumentException("no UI copy for key " + key);
        }
        String out = Html.esc(raw);
        for (int i = 0; i < args.length; i++) {
            out = out.replace("{" + i + "}", Html.esc(args[i]));
        }
        return out;
    }

    /** The string if the key exists, else null — for labels keyed by a value from a document. */
    public String maybe(Lang lang, String key) {
        String raw = bundles.get(lang).getProperty(key);
        return raw == null ? null : Html.esc(raw);
    }

    public Set<String> keys(Lang lang) {
        return bundles.get(lang).stringPropertyNames();
    }
}

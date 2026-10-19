package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.outbound.application.service.BanglaDate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Every visible string of the landing page and the app, per language, from the bundle parts {@link #PARTS}
 * ({@code baymax/ui_bn.properties}, {@code baymax/ui-shell_bn.properties}… and their {@code _en} twins). Each area
 * owns one part; a key lives in exactly one part (startup refuses a second definition) and in both languages
 * (asserted in a test), so the toggle can never leave a string behind in the other language. Placeholders are
 * {@code {0}}, {@code {1}}… and are replaced verbatim — no MessageFormat, so an apostrophe in copy is just an apostrophe.
 */
@Component
public class UiCopy {

    /** Bundle parts, loaded in this order; a key may live in one part only (startup refuses a second definition). */
    static final List<String> PARTS = List.of("ui", "ui-shell", "ui-family", "ui-papers", "ui-chat", "ui-result", "ui-upload");

    private final Map<Lang, Properties> bundles = new EnumMap<>(Lang.class);

    public UiCopy() {
        for (Lang lang : Lang.values()) {
            Properties all = new Properties();
            for (String part : PARTS) {
                Properties p = load("/baymax/" + part + "_" + lang.code + ".properties");
                for (String key : p.stringPropertyNames()) {
                    if (all.setProperty(key, p.getProperty(key)) != null) {
                        throw new IllegalStateException("copy key " + key + " is defined in two bundle parts");
                    }
                }
            }
            bundles.put(lang, all);
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

    /**
     * The string, HTML-escaped, with {@code {n}} replaced by the escaped arguments. A missing key is loud.
     *
     * <p>On a Bangla page a {@link Number} argument (an age, a count) is written in Bangla digits (spec rule 13).
     * A string argument is never converted: a value from the paper reaches the page as text and keeps the digits
     * printed on it. Every count on every page passes through here, so the rule lives here once.
     */
    public String t(Lang lang, String key, Object... args) {
        String raw = bundles.get(lang).getProperty(key);
        if (raw == null) {
            throw new IllegalArgumentException("no UI copy for key " + key);
        }
        String out = Html.esc(raw);
        for (int i = 0; i < args.length; i++) {
            Object arg = lang == Lang.BN && args[i] instanceof Number n ? BanglaDate.digits(n.toString()) : args[i];
            out = out.replace("{" + i + "}", Html.esc(arg));
        }
        return out;
    }

    /**
     * As {@link #t}, but not HTML-escaped: for a JSON body only, never for HTML. A {@link Number} argument is written in
     * Bangla digits on a Bangla page, exactly as {@link #t} writes it.
     */
    public String plain(Lang lang, String key, Object... args) {
        String out = raw(lang, key);
        for (int i = 0; i < args.length; i++) {
            Object arg = lang == Lang.BN && args[i] instanceof Number n ? BanglaDate.digits(n.toString()) : args[i];
            out = out.replace("{" + i + "}", String.valueOf(arg));
        }
        return out;
    }

    /**
     * A date as a page shows it, HTML-escaped: on a Bangla page "১৮ অক্টোবর ২০২৬" (day, full month name, year;
     * the web shows the year always, it has no "today" to omit it against), on an English page ISO as before.
     */
    public String date(Lang lang, LocalDate date) {
        return Html.esc(dateText(lang, date));
    }

    /**
     * As {@link #date(Lang, LocalDate)}, not escaped: for passing as an argument to {@link #t}, which escapes its
     * arguments itself, so the date is escaped exactly once.
     */
    public String dateText(Lang lang, LocalDate date) {
        if (date == null) {
            return "";
        }
        return lang == Lang.BN ? BanglaDate.format(date, null) : date.toString();
    }

    /** As {@link #date(Lang, LocalDate)} for a date stored as text; text that is not an ISO date is shown as written. */
    public String date(Lang lang, String isoDate) {
        if (isoDate == null) {
            return "";
        }
        return Html.esc(lang == Lang.BN ? BanglaDate.format(isoDate, null) : isoDate);
    }

    /** The bundle text as written, unescaped, no arguments: for tests and for URL parameters only, never for HTML. */
    String raw(Lang lang, String key) {
        String raw = bundles.get(lang).getProperty(key);
        if (raw == null) {
            throw new IllegalArgumentException("no UI copy for key " + key);
        }
        return raw;
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

package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.time.PatientCalendar;
import com.elioo.baymax.outbound.application.service.BanglaDate;
import com.elioo.baymax.outbound.domain.Urgency;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
    /** The WhatsApp message bundles, read-only: the source of the urgency lines, so both channels word a level alike. */
    private final Map<Lang, Properties> messages = new EnumMap<>(Lang.class);

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
            messages.put(lang, load("/baymax/messages_" + lang.code + ".properties"));
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
        return raw(lang, key, args);
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

    /**
     * A time of day on the patient's calendar (Dhaka), HTML-escaped: on a Bangla page the part of the day and a 12-hour
     * clock in Bangla digits ("সকাল ১০:১২"), on an English page {@code HH:mm}. The parts of the day are copy keys.
     */
    public String time(Lang lang, Instant at) {
        LocalTime t = LocalTime.ofInstant(at, PatientCalendar.ZONE);
        if (lang != Lang.BN) {
            return Html.esc(String.format(java.util.Locale.ROOT, "%02d:%02d", t.getHour(), t.getMinute()));
        }
        int h = t.getHour();
        String part = h >= 4 && h < 6 ? "time.dawn" : h >= 6 && h < 12 ? "time.morning" : h >= 12 && h < 15 ? "time.noon"
                : h >= 15 && h < 17 ? "time.afternoon" : h >= 17 && h < 19 ? "time.evening" : "time.night";
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return t(lang, part) + " " + Html.esc(BanglaDate.digits(String.format(java.util.Locale.ROOT, "%d:%02d", h12, t.getMinute())));
    }

    /**
     * The fixed urgency line for a stored level, from messages_{bn,en}.properties: the same bytes WhatsApp sends, so the
     * two channels never word a level differently (principle 3). HTML-escaped. {@code shortForm} reads the chip form
     * (urgency.*.short, C2). ROUTINE has no line, no chip and no banner (D27): asking for one throws.
     */
    public String urgency(Lang lang, Urgency level, boolean shortForm) {
        if (level == null || level == Urgency.ROUTINE) {
            throw new IllegalArgumentException("ROUTINE has no urgency line (D27)");
        }
        String key = "urgency." + level.dbValue() + (shortForm ? ".short" : "");
        String raw = messages.get(lang).getProperty(key);
        if (raw == null) {
            throw new IllegalArgumentException("no urgency copy for key " + key);
        }
        return Html.esc(raw);
    }

    /**
     * The bundle text as written, unescaped, with {@code {n}} replaced by the arguments as given (a {@link Number} in
     * Bangla digits on a Bangla page, as {@link #t} writes it): for a URL parameter (the caller encodes it), a JSON body
     * ({@link #plain}) or a test — never for HTML.
     */
    public String raw(Lang lang, String key, Object... args) {
        String out = bundles.get(lang).getProperty(key);
        if (out == null) {
            throw new IllegalArgumentException("no UI copy for key " + key);
        }
        for (int i = 0; i < args.length; i++) {
            Object arg = lang == Lang.BN && args[i] instanceof Number n ? BanglaDate.digits(n.toString()) : args[i];
            out = out.replace("{" + i + "}", String.valueOf(arg));
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

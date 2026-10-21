package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.outbound.application.service.BanglaDate;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bangla digits, dates and times as the Java pages write them, written by the Java methods themselves into
 * {@code medioo-web/src/copy/generated/bangla-vectors.json}, which Vitest reads. React's formatters are then held to
 * Java's output by value, never to a second hand-written expectation: a vector that fails in Vitest is a real
 * divergence from the HTML pages.
 */
class BanglaGoldenVectorsTest {

    private final UiCopy copy = new UiCopy();

    @Test
    void theWebFormatsNumbersAndDatesAsTheJavaPagesDo() {
        Map<String, Object> file = new TreeMap<>();

        List<Map<String, Object>> digits = new ArrayList<>();
        for (String in : List.of("0123456789", "12.5", "-3", "10^3/µL", "2026-10-21")) {
            digits.add(entry("in", in, "out", BanglaDate.digits(in)));
        }
        file.put("digits", digits);

        // A Number argument becomes Bangla digits on a Bangla page; a String (a value read off the paper) keeps its own.
        List<Map<String, Object>> counts = new ArrayList<>();
        for (Lang lang : Lang.values()) {
            counts.add(entry("lang", lang.code, "args", List.of(12, 3), "out", copy.raw(lang, "result.counts", 12, 3)));
            counts.add(entry("lang", lang.code, "args", List.of("12", "3"), "out", copy.raw(lang, "result.counts", "12", "3")));
        }
        file.put("counts", counts);

        List<LocalDate> days = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            days.add(LocalDate.of(2026, month, 1));
        }
        days.addAll(List.of(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1), LocalDate.of(2000, 2, 29)));
        List<Map<String, Object>> dates = new ArrayList<>();
        List<Map<String, Object>> dayMonths = new ArrayList<>();
        List<Map<String, Object>> monthYears = new ArrayList<>();
        for (Lang lang : Lang.values()) {
            for (LocalDate d : days) {
                dates.add(entry("lang", lang.code, "iso", d.toString(), "out", copy.dateText(lang, d)));
                dayMonths.add(entry("lang", lang.code, "iso", d.toString(), "out", copy.dayMonthText(lang, d)));
                monthYears.add(entry("lang", lang.code, "iso", d.toString(),
                        "out", HtmlUtils.htmlUnescape(copy.monthYear(lang, d))));
            }
        }
        file.put("dates", dates);
        file.put("day_months", dayMonths);
        file.put("month_years", monthYears);

        // Text that is not an ISO date is shown as printed, never re-dated.
        List<Map<String, Object>> rawDates = new ArrayList<>();
        for (Lang lang : Lang.values()) {
            for (String text : List.of("2026-10-12", "12/10/2026")) {
                rawDates.add(entry("lang", lang.code, "text", text, "out", HtmlUtils.htmlUnescape(copy.date(lang, text))));
            }
        }
        file.put("raw_dates", rawDates);

        // Each part-of-day boundary on both sides, at Dhaka wall-clock times, each instant written in UTC.
        List<Map<String, Object>> times = new ArrayList<>();
        List<String> dhaka = List.of("00:00", "03:59", "04:00", "05:59", "06:00", "11:59", "12:00", "14:59", "15:00",
                "16:59", "17:00", "18:59", "19:00", "23:59");
        for (Lang lang : Lang.values()) {
            for (String hm : dhaka) {
                Instant at = LocalDateTime.parse("2026-10-21T" + hm).toInstant(ZoneOffset.ofHours(6));
                times.add(entry("lang", lang.code, "dhaka", hm, "instant", at.toString(),
                        "out", HtmlUtils.htmlUnescape(copy.time(lang, at))));
            }
        }
        file.put("times", times);

        int total = 0;
        for (Map.Entry<String, Object> array : file.entrySet()) {
            List<?> vectors = (List<?>) array.getValue();
            assertThat(vectors).as(array.getKey() + " vectors").isNotEmpty();
            total += vectors.size();
        }
        assertThat(total).as("golden vectors in all").isGreaterThan(60);

        file.put("_meta", WebCopyExportTest.meta(getClass(),
                "BanglaDate.digits, UiCopy.raw, dateText, dayMonthText, monthYear, date(Lang, String) and time"));
        WebCopyExportTest.matches("bangla-vectors.json", WebCopyExportTest.json(file));
    }

    private static Map<String, Object> entry(Object... kv) {
        Map<String, Object> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}

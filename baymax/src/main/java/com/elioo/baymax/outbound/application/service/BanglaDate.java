package com.elioo.baymax.outbound.application.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Dates a family reads (PO ruling 2026-09-19, standing for every outbound message): "১ জুন", never 2026-06-01.
 * The year is added only when the date is not in the current year, because a family reading about last week's
 * report does not need to be told the year. Anything that does not parse is returned unchanged — a date we did
 * not understand is never invented.
 */
public final class BanglaDate {

    private static final String[] MONTHS = {
            "জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন",
            "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর"};

    private BanglaDate() {
    }

    /** ISO text to Bangla day-and-month; unparseable text unchanged. */
    public static String format(String isoDate, LocalDate today) {
        if (isoDate == null || isoDate.isBlank()) {
            return "";
        }
        try {
            return format(LocalDate.parse(isoDate.trim()), today);
        } catch (DateTimeParseException e) {
            return isoDate;
        }
    }

    public static String format(LocalDate date, LocalDate today) {
        if (date == null) {
            return "";
        }
        String text = digits(String.valueOf(date.getDayOfMonth())) + " " + MONTHS[date.getMonthValue() - 1];
        return today != null && date.getYear() == today.getYear() ? text : text + " " + digits(String.valueOf(date.getYear()));
    }

    /** ASCII digits to Bangla numerals. */
    public static String digits(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(c >= '0' && c <= '9' ? (char) ('০' + (c - '0')) : c);
        }
        return sb.toString();
    }
}

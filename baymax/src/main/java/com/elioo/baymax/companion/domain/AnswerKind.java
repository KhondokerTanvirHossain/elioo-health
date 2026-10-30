package com.elioo.baymax.companion.domain;

import java.util.Locale;

/**
 * What a চ্যাট turn showed the family (DR-50), stored lower-case in {@code chat_turn.kind}:
 * <ul>
 *   <li>{@code ANSWER}: a model answer that passed every code check;</li>
 *   <li>{@code EMERGENCY}: the emergency lines, decided in code before any model call;</li>
 *   <li>{@code LIMIT}: the day's question cap was reached;</li>
 *   <li>{@code FALLBACK}: the model failed, or its answer failed the checks twice; a fixed reply;</li>
 *   <li>{@code DECLINED}: answers are switched off, or no chat model is configured; the old fixed reply.</li>
 * </ul>
 */
public enum AnswerKind {
    ANSWER, EMERGENCY, LIMIT, FALLBACK, DECLINED;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AnswerKind fromDbValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}

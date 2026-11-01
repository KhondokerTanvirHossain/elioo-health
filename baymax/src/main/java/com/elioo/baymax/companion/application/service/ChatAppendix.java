package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.outbound.domain.Urgency;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The fixed lines code appends to every চ্যাট answer (DR-50 envelope 3), declared in one place. They are exempt from the
 * content checks (they are Medioo's own copy, not the model's), and the length cap runs on the body with them.
 *
 * <p>After the body, in this order: {@code chat.dose.ask_doctor} when the question asks for a change
 * ({@link DoseChangeGuard#asksForChange}); the urgency line of the patient's highest open stored level ({@code urgency.now}
 * or {@code urgency.this_week}; ROUTINE has none, D27); {@code disclaimer}; {@code safety.red_flag_line}. The emergency
 * reply is {@link #emergency}: it leads with the red-flag line and {@code now.notdoctor}, and ends, like every answer, with
 * the disclaimer and the red-flag line.
 */
public final class ChatAppendix {

    /** Every key this class reads, and nothing else. */
    public static final List<String> KEYS = List.of("chat.dose.ask_doctor", "urgency.now", "urgency.this_week", "disclaimer",
            "safety.red_flag_line", "now.notdoctor");

    static final String BEFORE = "\n\n";
    static final String BETWEEN = "\n";

    private ChatAppendix() {
    }

    /** The body and its appendix, as the family is shown it. */
    public static String append(String body, boolean doseQuestion, Optional<Urgency> open, Function<String, String> line) {
        return body + tail(doseQuestion, open, line);
    }

    /** Only what {@link #append} adds after the body, its separator included: what the body's room is computed against. */
    public static String tail(boolean doseQuestion, Optional<Urgency> open, Function<String, String> line) {
        List<String> lines = new ArrayList<>();
        if (doseQuestion) {
            lines.add(line.apply("chat.dose.ask_doctor"));
        }
        open.filter(u -> u == Urgency.NOW || u == Urgency.THIS_WEEK)
                .ifPresent(u -> lines.add(line.apply(u == Urgency.NOW ? "urgency.now" : "urgency.this_week")));
        lines.add(line.apply("disclaimer"));
        lines.add(line.apply("safety.red_flag_line"));
        return BEFORE + String.join(BETWEEN, lines);
    }

    /** The emergency reply: zero model text, the existing lines only. */
    public static String emergency(Function<String, String> line) {
        String redFlag = line.apply("safety.red_flag_line");
        return redFlag + BETWEEN + line.apply("now.notdoctor") + BEFORE + line.apply("disclaimer") + BETWEEN + redFlag;
    }
}

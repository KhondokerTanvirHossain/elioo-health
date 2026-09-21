package com.elioo.baymax.outbound.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Assembles a family message and, where it will not fit, splits it rather than losing anything (DR-26).
 *
 * <p>The 600-character cap exists for single-bubble readability; WhatsApp itself allows 4,096. It must never be
 * the reason a family loses their medicine instructions, so a body that overflows becomes a second message:
 * the summary, urgency line and diagnosis first, then the verbatim medicine lines. A medicine line is never
 * truncated and never dropped — the split falls on a line boundary or not at all.
 *
 * <p>Order matters. The diagnosis travels with the summary because it answers the family's first question —
 * whether anything is wrong — and the doctor has already written the answer.
 */
final class MessageSplit {

    private MessageSplit() {
    }

    /**
     * @param summary   the model's phrased text plus any urgency line, already checked
     * @param diagnosis the verbatim diagnosis block, or empty
     * @param header    the medicine block's header
     * @param medicines the stored medicines, in document order
     * @param cap       characters allowed per message
     * @return one message where everything fits, otherwise several, each within {@code cap}
     */
    static List<String> split(String summary, String diagnosis, String header,
                              List<Map<String, Object>> medicines, int cap) {
        String head = join(summary, diagnosis);
        List<String> lines = MedicineTranscription.lines(medicines);
        if (lines.isEmpty()) {
            return List.of(head);
        }

        String whole = join(head, header + "\n" + String.join("\n", lines));
        if (whole.length() <= cap) {
            return List.of(whole);
        }

        // it does not fit: the summary and diagnosis go alone, then the medicines fill messages of their own
        List<String> parts = new ArrayList<>();
        if (!head.isBlank()) {
            parts.add(head);
        }
        parts.addAll(medicineMessages(header, lines, cap));
        return parts;
    }

    /**
     * Medicine lines packed into messages, each carrying the header so a family reading the second bubble alone
     * still knows what it is. A line that cannot fit even in an empty message is still sent whole — the cap is
     * a readability preference and a dosing instruction is not ours to shorten.
     */
    private static List<String> medicineMessages(String header, List<String> lines, int cap) {
        List<String> messages = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);
        for (String line : lines) {
            if (current.length() + 1 + line.length() > cap && current.length() > header.length()) {
                messages.add(current.toString());
                current = new StringBuilder(header);
            }
            current.append('\n').append(line);
        }
        if (current.length() > header.length()) {
            messages.add(current.toString());
        }
        return messages;
    }

    private static String join(String first, String second) {
        if (first == null || first.isBlank()) {
            return second == null ? "" : second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + "\n\n" + second;
    }
}

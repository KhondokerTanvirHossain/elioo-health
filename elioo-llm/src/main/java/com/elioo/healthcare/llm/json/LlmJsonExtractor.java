package com.elioo.healthcare.llm.json;

import com.elioo.healthcare.llm.exception.LlmException;

/** Pulls the JSON document out of a model reply that may contain fences or prose. */
public final class LlmJsonExtractor {

    private LlmJsonExtractor() {}

    public static String extract(String content) {
        if (content == null || content.isBlank()) {
            throw new LlmException("No JSON in empty model response");
        }
        String text = content.strip();
        // 1. Markdown fences: ```json ... ``` or ``` ... ```
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int closing = text.lastIndexOf("```");
            if (firstNewline > 0 && closing > firstNewline) {
                text = text.substring(firstNewline + 1, closing).strip();
            }
        }
        // 2. First balanced { } or [ ] block, respecting string literals
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') {
                start = i;
                break;
            }
        }
        if (start < 0) {
            throw new LlmException("No JSON object or array found in model response");
        }
        char open = text.charAt(start);
        char close = open == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        throw new LlmException("No JSON object or array found in model response (unbalanced)");
    }
}

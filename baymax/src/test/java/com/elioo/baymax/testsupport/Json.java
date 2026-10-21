package com.elioo.baymax.testsupport;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * A walk over a parsed JSON tree: every field (by its path, with the field's value) and every scalar inside an array
 * (by its path), so a test asserts on fields and values, never on a substring of the document (CLAUDE.md: random ids).
 * Paths read {@code $.values[0].crop.src}.
 */
public final class Json {

    private Json() {
    }

    /** Visits every field path with its node, and every array element path with its node, depth first. */
    public static void walk(JsonNode node, BiConsumer<String, JsonNode> visit) {
        walk("$", node, visit);
    }

    private static void walk(String path, JsonNode node, BiConsumer<String, JsonNode> visit) {
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> f = it.next();
                String at = path + "." + f.getKey();
                visit.accept(at, f.getValue());
                walk(at, f.getValue(), visit);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String at = path + "[" + i + "]";
                if (!node.get(i).isContainerNode()) {
                    visit.accept(at, node.get(i));
                }
                walk(at, node.get(i), visit);
            }
        }
    }

    /** The last field name of a path ({@code $.values[0].pin} is {@code pin}), or null for an array element. */
    public static String fieldName(String path) {
        if (path.endsWith("]")) {
            return null;
        }
        return path.substring(path.lastIndexOf('.') + 1);
    }
}

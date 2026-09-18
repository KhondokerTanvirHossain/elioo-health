package com.elioo.baymax.outbound.application.service;

import org.springframework.stereotype.Component;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

/**
 * Every user-facing string, from {@code baymax/messages_bn.properties} (Bangla) with an English mirror for
 * logs. Editable without touching code: the file is read as UTF-8 and {@code {placeholders}} are the only
 * contract. A missing key is a loud failure, not an English fallback slipping to a family.
 */
@Component
public class Copy {

    private final Properties bn = load("baymax/messages_bn.properties");
    private final Properties en = load("baymax/messages_en.properties");

    public String bn(String key, Map<String, String> vars) {
        return fill(require(bn, key), vars);
    }

    public String en(String key, Map<String, String> vars) {
        return fill(require(en, key), vars);
    }

    public String documentType(String type) {
        String key = "document_type." + (type == null ? "other" : type);
        return bn.containsKey(key) ? bn.getProperty(key) : bn.getProperty("document_type.other");
    }

    private static String require(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null) {
            throw new IllegalStateException("messages: missing key " + key);
        }
        return v;
    }

    static String fill(String template, Map<String, String> vars) {
        String out = template.replace("\\n", "\n");
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    private static Properties load(String path) {
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(Copy.class.getClassLoader().getResourceAsStream(path), StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load " + path, e);
        }
        return p;
    }
}

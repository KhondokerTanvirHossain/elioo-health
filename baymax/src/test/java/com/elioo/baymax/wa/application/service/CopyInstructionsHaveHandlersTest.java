package com.elioo.baymax.wa.application.service;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every instruction the copy gives a family must have a handler behind it.
 *
 * <p>The explanation invited বিস্তারিত and nothing answered — a family typed the word we asked for and got
 * silence. The same principle was already written down in September, when the third-retake human offer was
 * removed with the note that "a family in difficulty would be replying into silence, which is worse than no
 * offer"; this test is that note made executable.
 *
 * <p>It parses the words the copy tells a family to type, then asserts the inbound handler recognises each.
 * Derived from the properties file rather than a list here, so a new invitation cannot be added without either
 * a handler or a deliberate change to this test.
 */
class CopyInstructionsHaveHandlersTest {

    /** লিখুন "X" / reply "X" — the quoted word is what the family is told to type. */
    private static final Pattern INVITED_WORD = Pattern.compile("[\"“]([^\"”]{1,30})[\"”]");

    /**
     * What each invited word must reach. "Not the fallback" was too weak: "বিস্তারিত" routed to THANKS would answer
     * a family asking for the detail with "ঠিক আছে" and stay green. A new invitation fails until it is added here
     * with the route it promises.
     */
    private static final java.util.Map<String, WaTextRouter.Route> PROMISED = java.util.Map.of(
            "বিস্তারিত", WaTextRouter.Route.DETAIL,
            "detail", WaTextRouter.Route.DETAIL);

    private static List<String> broken(List<String> invited) {
        return invited.stream()
                .filter(word -> !PROMISED.containsKey(word) || WaTextRouter.route(word) != PROMISED.get(word))
                .map(word -> word + " -> " + WaTextRouter.route(word) + " (promised " + PROMISED.get(word) + ")")
                .toList();
    }

    private static Properties load(String path) throws Exception {
        Properties p = new Properties();
        try (var in = CopyInstructionsHaveHandlersTest.class.getClassLoader().getResourceAsStream(path)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void everyWordTheCopyAsksAFamilyToTypeIsRecognisedByTheInboundHandler() throws Exception {
        Properties bn = load("baymax/messages_bn.properties");
        List<String> invited = new ArrayList<>();
        for (String key : bn.stringPropertyNames()) {
            String value = bn.getProperty(key);
            if (!value.contains("লিখুন")) {
                continue;
            }
            Matcher m = INVITED_WORD.matcher(value);
            while (m.find()) {
                invited.add(m.group(1).trim());
            }
        }

        assertThat(invited)
                .as("the copy must still be inviting at least one typed reply, or this test is vacuous")
                .isNotEmpty();

        assertThat(broken(invited))
                .as("every word the copy tells a family to type must reach the route it promises — a family "
                        + "following our own instruction and getting silence, or the wrong answer, is the bug this guards")
                .isEmpty();
    }

    /** The English mirror invites typed replies too ('reply "detail"', 'type "detail"'); each must route. */
    @Test
    void everyWordTheEnglishCopyAsksAFamilyToTypeIsRecognisedToo() throws Exception {
        Properties en = load("baymax/messages_en.properties");
        List<String> invited = new ArrayList<>();
        for (String key : en.stringPropertyNames()) {
            String value = en.getProperty(key);
            String lower = value.toLowerCase(java.util.Locale.ROOT);
            if (!lower.contains("reply") && !lower.contains("type")) {
                continue;
            }
            Matcher m = INVITED_WORD.matcher(value);
            while (m.find()) {
                invited.add(m.group(1).trim());
            }
        }
        assertThat(invited).as("vacuous if the English copy invites nothing").isNotEmpty();
        assertThat(broken(invited)).as("every English invitation reaches the route it promises").isEmpty();
    }
}

package com.elioo.baymax.web.adapter.in.api;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The web drops every whole line of a WhatsApp text that holds a link ({@link ThreadJson#webBody}; the desktop applies the
 * same rule to a card's released wording, medioo-web's webText). That is safe only while no template puts a safety
 * sentence on the same line as its link: a template joining "see a doctor this week" and the timeline link on one line
 * would silently lose the sentence on the desktop (plan desktop-parity Task 7, review finding). So every template of
 * both message bundles is filled in, its links as real URLs, and every safety sentence it carries, and its
 * {@code {standout}} (the values that made it urgent), must still be there after webBody. The safety sentences are
 * derived from the bundle (every {@code urgency.*} and {@code safety.*} key), never typed here.
 */
class WebBodyKeepsSafetyLinesTest {

    private static final String URL = "https://medioo.eliooo.org/app/documents/00000000-0000-0000-0000-000000000000";
    private static final String STANDOUT = "<<standout>>";

    private static Properties load(String path) throws Exception {
        Properties p = new Properties();
        try (var in = WebBodyKeepsSafetyLinesTest.class.getClassLoader().getResourceAsStream(path)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    /** A template as sent: the links real URLs, {standout} a marker, every other placeholder its own name. */
    private static String filled(String template) {
        return template
                .replace("{link}", URL)
                .replace("{optout_link}", URL + "/optout")
                .replace("{standout}", STANDOUT)
                .replaceAll("\\{([a-z_0-9]+)}", "$1");
    }

    @Test
    void noTemplateLosesASafetySentenceWithItsLinkLine() throws Exception {
        int withLinkAndSafety = 0;
        for (String lang : List.of("bn", "en")) {
            Properties bundle = load("baymax/messages_" + lang + ".properties");
            List<String> safety = new ArrayList<>();
            for (String key : bundle.stringPropertyNames()) {
                if (key.startsWith("urgency.") || key.startsWith("safety.")) {
                    safety.add(bundle.getProperty(key));
                }
            }
            assertThat(safety).as("%s: the safety sentences, derived from the bundle", lang).hasSizeGreaterThanOrEqualTo(4);

            for (String key : bundle.stringPropertyNames()) {
                String sent = filled(bundle.getProperty(key));
                if (!sent.contains(URL)) {
                    continue;
                }
                String web = ThreadJson.webBody(sent);
                boolean carried = false;
                for (String sentence : safety) {
                    if (sent.contains(sentence)) {
                        carried = true;
                        assertThat(web)
                                .as("%s %s: the web drops this template's link line and with it the safety sentence \"%s\"",
                                        lang, key, sentence)
                                .contains(sentence);
                    }
                }
                if (sent.contains(STANDOUT)) {
                    carried = true;
                    assertThat(web).as("%s %s: the web drops this template's link line and with it {standout}", lang, key)
                            .contains(STANDOUT);
                }
                if (carried) {
                    withLinkAndSafety++;
                }
            }
        }
        // Not vacuous: templates that hold both a link and a safety sentence exist (explanation.this_week, both languages).
        assertThat(withLinkAndSafety).as("templates with a link and a safety sentence").isGreaterThanOrEqualTo(2);
    }
}

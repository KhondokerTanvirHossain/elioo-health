package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.web.domain.Grouping;
import com.elioo.baymax.web.domain.UploadOptions;
import org.junit.jupiter.api.Test;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * upload.js as served: within the JS budget, parsed by an ES5 engine, carrying no copy, and every string it reads is on
 * the sheet in both languages with the same named slots. The keys are derived from the script itself, so a key added to
 * the script without its {@code data-*} attribute (a family would see "undefined", or nothing) fails here. The weight of
 * {@code /app/add} with the script is FirstLoadBudgetTest's, which renders every route.
 */
class UploadScriptTest {

    private static final WebUiTestKit KIT = new WebUiTestKit();
    private static final String SCRIPT = KIT.servedText("/assets/upload.js");
    private static final Pattern SLOT = Pattern.compile("\\{[a-z]+}");

    @Test
    void theScriptFitsTheJsBudget() {
        assertThat(KIT.served("/assets/upload.js").length).as("spec §1 rule 14: JS ≤ 40 KB, only on enhanced pages")
                .isPositive().isLessThanOrEqualTo(FirstLoadBudgetTest.SCRIPT_BUDGET);
    }

    /** A 2019 Android WebView parses ES5: the served bytes load in an ES5 engine, and with no sheet on the page do nothing. */
    @Test
    void theScriptParsesAsEs5AndIsInertWithoutTheSheet() throws ScriptException {
        ScriptEngine js = new NashornScriptEngineFactory().getScriptEngine("--language=es5");
        js.eval("var window = this, document = { getElementById: function () { return null; } };");
        js.eval(SCRIPT);
        assertThat(js.eval("typeof MediooUpload.buildRequest")).isEqualTo("function");
    }

    @Test
    void theScriptCarriesNoBanglaCopy() {
        List<String> lines = SCRIPT.lines().toList();
        assertThat(lines).isNotEmpty();
        List<String> bangla = IntStream.range(0, lines.size())
                .filter(i -> lines.get(i).codePoints().anyMatch(c -> c >= 0x0980 && c <= 0x09FF))
                .mapToObj(i -> "upload.js:" + (i + 1) + " " + lines.get(i).trim()).toList();
        assertThat(bangla).as("copy lives in ui-upload_*.properties and reaches the script through #up-copy").isEmpty();
    }

    /** The keys the script reads off #up-copy ({@code copy.trayTitle}, …), from its own source. */
    static Set<String> scriptKeys() {
        Matcher m = Pattern.compile("\\bcopy\\.([a-z][A-Za-z]+)").matcher(SCRIPT);
        Set<String> keys = new TreeSet<>();
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    /** {@code #up-copy}'s opening tag on the sheet as drawn for a free family with papers left. */
    private static String copyBlock(Lang lang) {
        UploadOptions one = new UploadOptions(List.of(AddReportSheetPageTest.patient("আম্মা", 64, true)), List.of(), AddReportSheetPageTest.used(0));
        String html = KIT.upload.sheetBody(lang, one, Optional.empty(), "/app/home", null, Grouping.ONE);
        int at = html.indexOf("<div id=\"up-copy\"");
        assertThat(at).as(lang + ": #up-copy on the sheet").isNotNegative();
        return html.substring(at, html.indexOf('>', at));
    }

    private static String attr(String key) {
        return "data-" + key.replaceAll("([A-Z])", "-$1").toLowerCase(Locale.ROOT);
    }

    private static String valueOf(String block, String key) {
        Matcher m = Pattern.compile(Pattern.quote(attr(key)) + "=\"([^\"]*)\"").matcher(block);
        return m.find() ? m.group(1) : null;
    }

    @Test
    void everyStringTheScriptReadsIsOnTheSheetInBothLanguages() {
        Set<String> keys = scriptKeys();
        assertThat(keys).as("the sweep found the script's keys; an empty set would pass vacuously").hasSizeGreaterThanOrEqualTo(15);
        for (Lang lang : Lang.values()) {
            String block = copyBlock(lang);
            for (String k : keys) {
                assertThat(valueOf(block, k)).as(lang + " " + attr(k)).isNotNull().isNotBlank();
            }
        }
    }

    /** A slot the script fills ({n}, {total}, {max}) is in both languages, or one of them shows the slot itself. */
    @Test
    void bothLanguagesCarryTheSameNamedSlots() {
        String bn = copyBlock(Lang.BN);
        String en = copyBlock(Lang.EN);
        int slotted = 0;
        for (String k : scriptKeys()) {
            Set<String> inBn = slots(valueOf(bn, k));
            assertThat(slots(valueOf(en, k))).as(attr(k)).isEqualTo(inBn);
            slotted += inBn.isEmpty() ? 0 : 1;
        }
        assertThat(slotted).as("keys with a slot the script fills").isGreaterThanOrEqualTo(5);
    }

    private static Set<String> slots(String value) {
        Set<String> out = new TreeSet<>();
        SLOT.matcher(value == null ? "" : value).results().forEach(r -> out.add(r.group()));
        return out;
    }

    @Test
    void theServedScriptIsTheFileInTheJar() {
        assertThat(SCRIPT.getBytes(StandardCharsets.UTF_8)).isEqualTo(KIT.served("/assets/upload.js"));
    }
}

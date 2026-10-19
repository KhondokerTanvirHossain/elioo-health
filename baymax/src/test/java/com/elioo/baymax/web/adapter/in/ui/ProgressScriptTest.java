package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * progress.js as the browser runs it, the served bytes in a JS engine against a fake page: with script on, the
 * {@code <noscript>} refresh never fires, so this poll is the only thing that opens a family's result. Each answer the
 * status endpoint can give is played through one tick, and what the script did — reload, ask again in 5 s, or neither
 * — is asserted, in both directions. {@code fetch} answers with an already-settled thenable, enough of a Promise for the
 * script's then/catch chain; timers are recorded and run by hand, so nothing here waits.
 */
class ProgressScriptTest {

    private static final String URL = "/app/documents/" + WebUiTestKit.DOC_ID + "/status";
    private static final String SCRIPT = new WebUiTestKit().servedText("/assets/progress.js");

    /** The fake page and browser the script runs in. */
    private static final String PAGE = """
            var timers = [], reloads = 0, requests = [], answers = [];
            var slow = { hidden: true };
            var section = { getAttribute: function (n) { return attrs.hasOwnProperty(n) ? attrs[n] : null; } };
            var document = {
              querySelector: function (s) { return s === '[data-status-url]' && attrs ? section : null; },
              getElementById: function (id) { return id === 'slow' && attrs ? slow : null; }
            };
            var location = { reload: function () { reloads++; } };
            function setTimeout(f, ms) { timers.push({ f: f, ms: ms }); }
            function Settled(ok, v) { this.ok = ok; this.v = v; }
            Settled.prototype.then = function (onOk, onErr) {
              var h = this.ok ? onOk : onErr;
              if (!h) { return this; }
              try { var r = h(this.v); return r instanceof Settled ? r : new Settled(true, r); }
              catch (e) { return new Settled(false, e); }
            };
            Settled.prototype['catch'] = function (onErr) { return this.then(null, onErr); };
            function answer(status, type, body) {
              return new Settled(true, { ok: status >= 200 && status < 300, status: status,
                headers: { get: function (n) { return n.toLowerCase() === 'content-type' ? type : null; } },
                json: function () { return type && type.indexOf('application/json') === 0 ? new Settled(true, body)
                                                                                         : new Settled(false, new SyntaxError('not JSON')); } });
            }
            function fetch(url, opts) { requests.push(url + ' ' + opts.credentials + ' ' + opts.cache); return answers.shift(); }
            function run(ms) {
              for (var i = 0; i < timers.length; i++) { if (timers[i].ms === ms) { var t = timers.splice(i, 1)[0]; t.f(); return true; } }
              return false;
            }
            function count(ms) { var n = 0; for (var i = 0; i < timers.length; i++) { if (timers[i].ms === ms) { n++; } } return n; }
            """;

    /** One page load of progress.js on a reading page with these attributes on #progress (null: no such section). */
    private static ScriptEngine load(Map<String, String> attrs) throws ScriptException {
        ScriptEngine js = new NashornScriptEngineFactory().getScriptEngine("--language=es5");
        StringBuilder a = new StringBuilder(attrs == null ? "var attrs = null;" : "var attrs = {");
        if (attrs != null) {
            attrs.forEach((k, v) -> a.append("'").append(k).append("': '").append(v).append("',"));
            a.append("};");
        }
        js.eval(a + PAGE);
        js.eval(SCRIPT);
        return js;
    }

    private static Map<String, String> reading(String status, String slowIn) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("data-status-url", URL);
        m.put("data-status", status);
        if (slowIn != null) {
            m.put("data-slow-in", slowIn);
        }
        return m;
    }

    /** Plays one tick of the poll with the endpoint answering {@code answer} (a JS expression). */
    private static ScriptEngine tick(String status, String answer) throws ScriptException {
        ScriptEngine js = load(reading(status, null));
        assertThat(n(js, "count(5000)")).as("the first poll is scheduled at load").isEqualTo(1);
        js.eval("answers.push(" + answer + ");");
        assertThat((Boolean) js.eval("run(5000)")).isTrue();
        assertThat(n(js, "requests.length")).as("one request per tick").isEqualTo(1);
        return js;
    }

    private static int n(ScriptEngine js, String expr) throws ScriptException {
        return ((Number) js.eval(expr)).intValue();
    }

    @Test
    void aChangedStatusReloadsOnceAndStopsPolling() throws ScriptException {
        ScriptEngine js = tick("PROCESSING", "answer(200, 'application/json', { status: 'DONE' })");
        assertThat(js.eval("requests[0]")).isEqualTo(URL + " same-origin no-store");
        assertThat(n(js, "reloads")).as("reloads").isEqualTo(1);
        assertThat(n(js, "count(5000)")).as("no poll after a reload is under way").isZero();
        assertThat(n(tick("RECEIVED", "answer(200, 'application/json', { status: 'PROCESSING' })"), "reloads"))
                .as("RECEIVED → PROCESSING is a change too: the page redraws its steps").isEqualTo(1);
    }

    @Test
    void anUnchangedStatusKeepsThePageAndAsksAgainInFiveSeconds() throws ScriptException {
        ScriptEngine js = tick("PROCESSING", "answer(200, 'application/json; charset=utf-8', { status: 'PROCESSING' })");
        assertThat(n(js, "reloads")).as("reloads").isZero();
        assertThat(n(js, "count(5000)")).as("the next poll").isEqualTo(1);
        js.eval("answers.push(answer(200, 'application/json', { status: 'DONE' }));");
        js.eval("run(5000)");
        assertThat(n(js, "reloads")).as("the second tick sees DONE").isEqualTo(1);
    }

    /** Signed out (the login page after the 303) or the paper gone (404): the server answers, so the page reloads. */
    @Test
    void anAnswerThatIsNotTheStatusReloadsSoTheServerAnswers() throws ScriptException {
        for (String answer : new String[]{"answer(200, 'text/html; charset=utf-8', '<!doctype html>')",
                "answer(404, 'application/json', { error: 'document_not_found' })", "answer(404, 'text/html', '')"}) {
            ScriptEngine js = tick("PROCESSING", answer);
            assertThat(n(js, "reloads")).as("reloads after " + answer).isEqualTo(1);
            assertThat(n(js, "count(5000)")).as("no poll after " + answer).isZero();
        }
    }

    /**
     * The server down or in trouble (offline, a 5xx): the page stays as it is — a reload would leave an error page with
     * no refresh and no poll — and the poll tries again in 5 s.
     */
    @Test
    void anOfflineOrFailingServerKeepsThePageAndRetries() throws ScriptException {
        for (String answer : new String[]{"new Settled(false, new TypeError('Failed to fetch'))",
                "answer(502, 'text/html', '')", "answer(503, 'application/json', { error: 'x' })",
                "answer(200, 'application/json', undefined)"}) {
            ScriptEngine js = tick("PROCESSING", answer);
            assertThat(n(js, "reloads")).as("reloads after " + answer).isZero();
            assertThat(n(js, "count(5000)")).as("the next poll after " + answer).isEqualTo(1);
        }
    }

    /**
     * The five-minute line on a page that stays open: shown when #progress's seconds run out, whatever the poll meets
     * meanwhile (the server stopped mid-read); never shown early; and no timer when the server already showed it.
     */
    @Test
    void theTakingLongerLineIsShownWhenItsSecondsRunOutEvenWithTheServerDown() throws ScriptException {
        ScriptEngine js = load(reading("PROCESSING", "270"));
        assertThat(n(js, "count(270000)")).as("one timer for the line").isEqualTo(1);
        for (int i = 0; i < 3; i++) {
            js.eval("answers.push(new Settled(false, new TypeError('Failed to fetch')));");
            js.eval("run(5000)");
        }
        assertThat((Boolean) js.eval("slow.hidden")).as("still hidden before its time").isTrue();
        js.eval("run(270000)");
        assertThat((Boolean) js.eval("slow.hidden")).as("shown at five minutes").isFalse();
        assertThat(n(js, "reloads")).isZero();
        assertThat(n(js, "count(5000)")).as("the poll goes on").isEqualTo(1);

        ScriptEngine already = load(reading("PROCESSING", null));
        assertThat(n(already, "timers.length")).as("only the poll when the line is already shown").isEqualTo(1);
    }

    @Test
    void onAPageWithoutTheReadingSectionItDoesNothing() throws ScriptException {
        ScriptEngine js = load(null);
        assertThat(n(js, "timers.length")).isZero();
        assertThat(n(js, "requests.length")).isZero();
    }
}

package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.healthrecord.domain.DocumentAllowance;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.UploadOptions;
import org.junit.jupiter.api.Test;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;
import reactor.core.publisher.Mono;

import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * upload.js's DOM wiring as a browser runs it: the served script, under an ES5 engine (the oldest WebView it must
 * parse in), over the sheet the server actually renders — each element of the page rebuilt in a small fake DOM, so an
 * id the script reads and the markup does not carry fails here. What leaves the phone is read off the fake
 * {@code XMLHttpRequest}: a file the server would refuse and a press the allowance would refuse send nothing; a send is
 * the pages as page-N with the form's own key; a stalled send is retried as the same press. Timers run by hand; the
 * fake {@code Promise} settles at once; the downscale path runs over a fake bitmap and canvas that record what is drawn.
 */
class UploadScriptDomTest {

    private static final String SCRIPT = new WebUiTestKit().servedText("/assets/upload.js");

    /** The fake browser: elements, events, timers, XHR, FormData, object URLs. ES5 only. */
    private static final String BROWSER = """
            var window = this, focused = null, timers = [], nextTimer = 1, xhrs = [], urls = 0, revoked = [], assigned = [], winListeners = {};
            function camel(s) { return s.replace(/-([a-z])/g, function (a, c) { return c.toUpperCase(); }); }
            function Cls(el) { this.el = el; }
            Cls.prototype.list = function () { var c = this.el.attrs['class']; return c ? c.split(/\\s+/) : []; };
            Cls.prototype.contains = function (c) { return this.list().indexOf(c) >= 0; };
            Cls.prototype.add = function (c) { if (!this.contains(c)) { var l = this.list(); l.push(c); this.el.attrs['class'] = l.join(' '); } };
            Cls.prototype.remove = function (c) { var l = this.list(), i = l.indexOf(c); if (i >= 0) { l.splice(i, 1); this.el.attrs['class'] = l.join(' '); } };
            Cls.prototype.toggle = function (c, on) { if (on === undefined) { on = !this.contains(c); } if (on) { this.add(c); } else { this.remove(c); } return on; };
            function El(tag, attrs) {
              this.tag = tag; this.attrs = attrs || {}; this.children = []; this.parentNode = null; this.listeners = {};
              this.classList = new Cls(this); this.dataset = {}; this.textContent = ''; this.files = [];
              this.hidden = 'hidden' in this.attrs; this.disabled = 'disabled' in this.attrs; this.checked = 'checked' in this.attrs;
              this.value = this.attrs.value || ''; this.type = this.attrs.type || ''; this.name = this.attrs.name || '';
              this.id = this.attrs.id || ''; this.action = this.attrs.action || '';
              for (var k in this.attrs) { if (k.indexOf('data-') === 0) { this.dataset[camel(k.substring(5))] = this.attrs[k]; } }
            }
            Object.defineProperty(El.prototype, 'firstChild', { get: function () { return this.children[0] || null; } });
            Object.defineProperty(El.prototype, 'firstElementChild', { get: function () { return this.children[0] || null; } });
            El.prototype.appendChild = function (c) { if (c.parentNode) { c.parentNode.removeChild(c); } c.parentNode = this; this.children.push(c); return c; };
            El.prototype.removeChild = function (c) { var i = this.children.indexOf(c); if (i >= 0) { this.children.splice(i, 1); } c.parentNode = null; return c; };
            El.prototype.insertBefore = function (c, ref) {
              if (c.parentNode) { c.parentNode.removeChild(c); }
              var i = ref === null ? this.children.length : this.children.indexOf(ref);
              if (i < 0) { throw new Error('NotFoundError: insertBefore: the reference is not a child'); }   // as a browser throws
              c.parentNode = this; this.children.splice(i, 0, c);
              return c;
            };
            El.prototype.setAttribute = function (n, v) { this.attrs[n] = String(v); };
            El.prototype.getAttribute = function (n) { return n in this.attrs ? this.attrs[n] : null; };
            El.prototype.addEventListener = function (t, f) { (this.listeners[t] = this.listeners[t] || []).push(f); };
            El.prototype.fire = function (t) {
              var e = { type: t, target: this, defaultPrevented: false, preventDefault: function () { e.defaultPrevented = true; } };
              var l = (this.listeners[t] || []).slice();
              for (var i = 0; i < l.length; i++) { l[i].call(this, e); }
              if (typeof this['on' + t] === 'function') { this['on' + t](e); }
              return e;
            };
            El.prototype.click = function () { if (!this.disabled) { this.fire('click'); } };
            El.prototype.focus = function () { focused = this; };
            El.prototype.cloneNode = function (deep) {
              var a = {}; for (var k in this.attrs) { a[k] = this.attrs[k]; }
              var c = new El(this.tag, a); c.textContent = this.textContent;
              if (deep) { for (var i = 0; i < this.children.length; i++) { c.appendChild(this.children[i].cloneNode(true)); } }
              return c;
            };
            function matches(el, sel) {
              var m = /^([a-z]+)?(?:\\.([a-z-]+))?(?:\\[([a-z-]+)="?([^"\\]]*)"?\\])?(:checked)?$/.exec(sel);
              if (!m) { throw new Error('selector not supported by the fake: ' + sel); }
              if (m[1] && el.tag !== m[1]) { return false; }
              if (m[2] && !el.classList.contains(m[2])) { return false; }
              if (m[3]) {
                var v = m[3] === 'name' ? el.name : m[3] === 'type' ? el.type : m[3].indexOf('data-') === 0 ? el.dataset[camel(m[3].substring(5))] : el.attrs[m[3]];
                if (v !== m[4]) { return false; }
              }
              return !(m[5] && !el.checked);
            }
            El.prototype.querySelectorAll = function (sel) {
              var out = [];
              (function walk(n) { for (var i = 0; i < n.children.length; i++) { var c = n.children[i]; if (matches(c, sel)) { out.push(c); } walk(c); } })(this);
              return out;
            };
            El.prototype.querySelector = function (sel) { return this.querySelectorAll(sel)[0] || null; };
            function byId(n, id) {
              if (n.id === id) { return n; }
              for (var i = 0; i < n.children.length; i++) { var f = byId(n.children[i], id); if (f) { return f; } }
              return null;
            }
            var N = [];
            var document = {
              documentElement: null,
              getElementById: function (id) { return byId(document.documentElement, id); },
              createElement: function (t) { return new El(t, {}); }
            };
            /** One element of the rendered page; a template's child is its content, outside the document. */
            function mk(i, tag, attrs, parent, text) {
              var e = new El(tag, attrs); e.textContent = text; N[i] = e;
              if (parent < 0) { document.documentElement = document.documentElement || e; return; }
              if (N[parent].tag === 'template') { N[parent].content = { firstElementChild: e }; return; }
              N[parent].appendChild(e);
            }
            function FormData() { this.entries = []; }
            FormData.prototype.append = function (k, v, name) { this.entries.push([k, v, name === undefined ? null : name]); };
            function XMLHttpRequest() { this.headers = {}; this.upload = {}; this.status = 0; this.responseText = ''; this.responseURL = ''; this.ctype = ''; this.aborted = false; xhrs.push(this); }
            XMLHttpRequest.prototype.open = function (m, u) { this.method = m; this.url = u; };
            XMLHttpRequest.prototype.setRequestHeader = function (k, v) { this.headers[k] = v; };
            XMLHttpRequest.prototype.send = function (body) { this.body = body; };
            XMLHttpRequest.prototype.abort = function () { this.aborted = true; if (this.onabort) { this.onabort(); } };
            XMLHttpRequest.prototype.getResponseHeader = function (n) { return n.toLowerCase() === 'content-type' ? this.ctype : null; };
            var URL = { createObjectURL: function (f) { urls++; return 'blob:' + urls; }, revokeObjectURL: function (u) { revoked.push(u); } };
            var location = { assign: function (u) { assigned.push(u); } };
            function addEventListener(t, f) { (winListeners[t] = winListeners[t] || []).push(f); }
            function removeEventListener(t, f) { var l = winListeners[t] || [], i = l.indexOf(f); if (i >= 0) { l.splice(i, 1); } }
            function fireWindow(t) { var l = (winListeners[t] || []).slice(), e = { returnValue: undefined, prevented: false, preventDefault: function () { e.prevented = true; } }; for (var i = 0; i < l.length; i++) { l[i](e); } return e; }
            function setTimeout(f, ms) { var id = nextTimer++; timers.push({ id: id, f: f, ms: ms }); return id; }
            function clearTimeout(id) { for (var i = 0; i < timers.length; i++) { if (timers[i].id === id) { timers.splice(i, 1); return; } } }
            function run(ms) { for (var i = 0; i < timers.length; i++) { if (timers[i].ms === ms) { var t = timers.splice(i, 1)[0]; t.f(); return true; } } return false; }
            function Promise(fn) { var self = this; this.done = false; fn(function (v) { self.done = true; self.v = v; }); }
            Promise.resolve = function (v) { return new Promise(function (ok) { ok(v); }); };
            Promise.prototype.then = function (f) { if (!this.done) { throw new Error('fake Promise still pending'); } var r = f(this.v); return r instanceof Promise ? r : Promise.resolve(r); };
            Promise.prototype['catch'] = function () { return this; };
            """;

    /** Test helpers over the fake page, ES5. */
    private static final String HELPERS = """
            var F = document.getElementById('add');
            if (F) { F.elements = {}; F.querySelectorAll('input').forEach(function (i) { if (i.name && !(i.name in F.elements)) { F.elements[i.name] = i; } }); }
            function $(id) { return document.getElementById(id); }
            function file(name, type, size) { return { name: name, type: type, size: size }; }
            function choose(src, files) { var input = F.querySelector('input[data-src="' + src + '"]'); input.files = files; input.fire('change'); return input; }
            function pick(id) {
              var rs = F.querySelectorAll('input[name=patient]');
              rs.forEach(function (r) { r.checked = r.value === id; });
              rs.forEach(function (r) { if (r.checked) { r.fire('change'); } });
            }
            function group(v) { F.querySelectorAll('input[name=group]').forEach(function (r) { r.checked = r.value === v; }); }
            function sent(x) {
              return x.body.entries.map(function (e) { return e[0] + '=' + (e[2] !== null ? e[2] + ':' + e[1].size : e[1]); }).join('&');
            }
            function items() { return $('tray-list').children; }
            function pageLines() { return $('up-pages').children.map(function (li) { return li.children[0].textContent + '|' + li.children[1].textContent; }).join(' / '); }
            """;

    private final WebUiTestKit kit = new WebUiTestKit();
    private final PatientSummary ma = AddReportSheetPageTest.patient("আম্মা", 64, true);
    private final PatientSummary baba = AddReportSheetPageTest.patient("আব্বু", 72, true);

    /** The sheet as the server renders it for these options, in Bangla, rebuilt in the fake page, with the script run. */
    private ScriptEngine load(UploadOptions options) throws ScriptException {
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(options));
        return run(kit.page("/app/add", "bn"), true);
    }

    private ScriptEngine run(String html, boolean withXhr) throws ScriptException {
        return run(html, withXhr ? "" : "XMLHttpRequest = undefined;");
    }

    /** The page with {@code before} run over the fake browser and the page, just before the script. */
    private ScriptEngine run(String html, String before) throws ScriptException {
        ScriptEngine js = new NashornScriptEngineFactory().getScriptEngine("--language=es5");
        js.eval(BROWSER);
        js.eval(tree(Dom.parse(html)));
        js.eval(HELPERS);
        js.eval(before);
        js.eval(SCRIPT);
        return js;
    }

    private UploadOptions one(long used) {
        return new UploadOptions(List.of(ma), List.of(), AddReportSheetPageTest.used(used));
    }

    /** The page's elements as {@code mk(...)} calls, attribute values and text decoded as a browser would. */
    static String tree(Dom dom) {
        Map<Dom.Element, Integer> index = new IdentityHashMap<>();
        StringBuilder b = new StringBuilder();
        for (Dom.Element e : dom.all) {
            int i = index.size();
            index.put(e, i);
            Integer parent = e.parent() == dom.root ? Integer.valueOf(-1) : index.get(e.parent());
            b.append("mk(").append(i).append(",'").append(js(e.tag())).append("',{");
            e.attrs().forEach((k, v) -> b.append("'").append(js(k)).append("':'").append(js(decode(v))).append("',"));
            b.append("},").append(parent).append(",'").append(js(decode(e.ownText()))).append("');\n");
        }
        return b.toString();
    }

    private static String decode(String s) {
        return s.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private static String js(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }

    private static Object v(ScriptEngine js, String expr) throws ScriptException {
        return js.eval(expr);
    }

    private static int n(ScriptEngine js, String expr) throws ScriptException {
        return ((Number) js.eval(expr)).intValue();
    }

    private String t(String key, Object... args) {
        return kit.copy.raw(Lang.BN, key, args);
    }

    // --- enhancement only when it can ---------------------------------------------------------------------------------

    @Test
    void theScriptEnhancesTheFormAndLeavesAPageWithoutItOrWithoutXhrAlone() throws ScriptException {
        ScriptEngine on = load(one(0));
        assertThat((Boolean) v(on, "document.documentElement.classList.contains('js')")).as("the sheet with its form is enhanced").isTrue();

        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(one(3)));
        ScriptEngine cap = run(kit.page("/app/add", "bn"), true);
        assertThat((Boolean) v(cap, "document.documentElement.classList.contains('js')")).as("no form: nothing to enhance").isFalse();

        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(one(0)));
        ScriptEngine old = run(kit.page("/app/add", "bn"), false);
        assertThat((Boolean) v(old, "document.documentElement.classList.contains('js')")).as("a browser with no XHR keeps the plain form").isFalse();

        // send() waits on a Promise: without one the enhanced form would hide its submit and then never send anything
        ScriptEngine noPromise = run(kit.page("/app/add", "bn"), "Promise = undefined;");
        assertThat((Boolean) v(noPromise, "document.documentElement.classList.contains('js')")).as("a browser with no Promise keeps the plain form").isFalse();
    }

    // --- what never leaves the phone ----------------------------------------------------------------------------------

    @Test
    void aFileTheServerWouldRefuseNeverLeavesThePhoneAndAPhotoWaitsInTheTray() throws ScriptException {
        ScriptEngine js = load(one(0));
        js.eval("choose('gallery', [file('Rokeya-Begum-report.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 4000)])");
        String said = (String) v(js, "$('add-alert').textContent");
        boolean trayHidden = (Boolean) v(js, "$('tray').hidden");
        js.eval("F.fire('submit')");   // পাঠান (or Enter) right after
        assertThat(n(js, "xhrs.length")).as("a DOCX never leaves the phone").isZero();
        assertThat(said).as("said at once, in the family's language").isEqualTo(t("upload.err.unsupported"));
        assertThat(trayHidden).as("nothing went into the tray").isTrue();
        assertThat(v(js, "$('add-alert').textContent")).as("send with nothing in the tray").isEqualTo(t("upload.err.no_file"));

        js.eval("choose('camera', [file('IMG_0001.jpg', 'image/jpeg', 230947)])");
        assertThat((Boolean) v(js, "$('add-alert').hidden")).as("the alert clears").isTrue();
        assertThat((Boolean) v(js, "$('tray').hidden")).isFalse();
        assertThat(v(js, "$('tray-title').textContent")).isEqualTo(t("tray.title").replace("{n}", "১"));
        assertThat((Boolean) v(js, "$('group').hidden")).as("one page: no question to answer").isTrue();
        assertThat(n(js, "xhrs.length")).as("a photo waits in the tray until পাঠান").isZero();
        js.eval("$('tray-send').click()");
        assertThat(n(js, "xhrs.length")).isEqualTo(1);
    }

    @Test
    void overTheAllowanceNothingIsSentAndOneReportStillGoes() throws ScriptException {
        ScriptEngine js = load(one(2));   // one free paper left
        js.eval("choose('gallery', [file('a.jpg', 'image/jpeg', 100), file('b.jpg', 'image/jpeg', 200)])");
        assertThat((Boolean) v(js, "$('group').hidden")).as("two pages: the family says what they are").isFalse();
        js.eval("group('separate'); $('tray-send').click()");
        assertThat(n(js, "xhrs.length")).as("two papers when one is left: refused before a byte leaves").isZero();
        assertThat(v(js, "$('add-alert').textContent")).isEqualTo(t("add.cap.separate", 1L));
        js.eval("group('one'); $('tray-send').click()");
        assertThat(n(js, "xhrs.length")).as("one report of two pages fits").isEqualTo(1);
        assertThat(v(js, "sent(xhrs[0])")).asString().contains("group=one&");
    }

    @Test
    void withTwoPatientsTheRowsWaitForAChoiceAndThenSendForThatPatient() throws ScriptException {
        ScriptEngine js = load(new UploadOptions(List.of(ma, baba), List.of(), AddReportSheetPageTest.used(0)));
        assertThat(n(js, "F.querySelectorAll('.src-row').filter(function (r) { return r.classList.contains('off') && r.querySelector('input').disabled; }).length"))
                .as("no patient chosen: every way in waits").isEqualTo(3);
        js.eval("pick('" + baba.patient().id() + "')");
        assertThat(n(js, "F.querySelectorAll('.src-row').filter(function (r) { return r.classList.contains('off') || r.querySelector('input').disabled; }).length"))
                .as("chosen: every way in opens").isZero();
        js.eval("choose('camera', [file('x.jpg', 'image/jpeg', 10)]); $('tray-send').click()");
        assertThat(v(js, "sent(xhrs[0])")).asString().startsWith("patient=" + baba.patient().id() + "&");
    }

    // --- what one press sends -----------------------------------------------------------------------------------------

    @Test
    void onePressSendsThePagesInTrayOrderAsPageNWithTheFormsKey() throws ScriptException {
        ScriptEngine js = load(one(0));
        String key = (String) v(js, "F.elements.upload_key.value");
        assertThat(key).matches("[0-9a-f-]{36}");
        js.eval("choose('gallery', [file('Rokeya-Begum-CBC-1.jpg', 'image/jpeg', 100), file('Rokeya-Begum-CBC-2.png', 'image/png', 200)])");
        js.eval("items()[0].querySelector('.t-down').click()");   // the family puts the second page first
        js.eval("group('separate'); $('tray-send').click()");
        assertThat(n(js, "xhrs.length")).isEqualTo(1);
        assertThat(v(js, "xhrs[0].method + ' ' + xhrs[0].url + ' ' + xhrs[0].headers.Accept")).isEqualTo("POST /app/add application/json");
        assertThat(v(js, "sent(xhrs[0])")).isEqualTo("patient=" + ma.patient().id() + "&group=separate&upload_key=" + key
                + "&file=page-1.png:200&file=page-2.jpg:100");
        // a File appended without a filename goes under its own name (which may name the patient): every file entry
        // carries page-N, and no text field carries the file's name
        assertThat(n(js, "xhrs[0].body.entries.filter(function (e) { return e[0] === 'file' && !/^page-[0-9]+\\.(jpg|png|pdf)$/.test(e[2]); }).length")).isZero();
        assertThat(v(js, "JSON.stringify(xhrs[0].body.entries.filter(function (e) { return e[0] !== 'file'; }))")).asString().doesNotContain("Rokeya");
    }

    @Test
    void aSinglePdfSkipsTheTrayAndGoesAtOnce() throws ScriptException {
        ScriptEngine js = load(one(0));
        js.eval("choose('pdf', [file('lab.pdf', 'application/pdf', 5000)])");
        assertThat(n(js, "xhrs.length")).isEqualTo(1);
        assertThat(v(js, "sent(xhrs[0])")).asString().endsWith("&file=page-1.pdf:5000");
        assertThat((Boolean) v(js, "$('up').hidden")).as("straight to the upload step").isFalse();
    }

    @Test
    void aPressOverTheRequestCapNeverLeavesThePhone() throws ScriptException {
        ScriptEngine js = load(one(0));
        js.eval("choose('gallery', [file('a.jpg', 'image/jpeg', 40000000), file('b.jpg', 'image/jpeg', 30000000)]); $('tray-send').click()");
        assertThat(n(js, "xhrs.length")).isZero();
        assertThat(v(js, "$('add-alert').textContent")).isEqualTo(t("upload.err.too_large"));
        assertThat((Boolean) v(js, "$('tray').hidden")).as("back on the tray, pages kept").isFalse();
        assertThat(n(js, "items().length")).isEqualTo(2);
    }

    // --- progress, the stall and the retry ----------------------------------------------------------------------------

    @Test
    void theUploadStepCountsPagesWhoseBytesHaveLeftInBanglaDigits() throws ScriptException {
        ScriptEngine js = load(one(0));
        js.eval("choose('gallery', [file('a.jpg', 'image/jpeg', 100), file('b.jpg', 'image/jpeg', 100)]); $('tray-send').click()");
        assertThat((Boolean) v(js, "$('tray').hidden")).isTrue();
        assertThat((Boolean) v(js, "$('up').hidden")).isFalse();
        js.eval("xhrs[0].upload.onprogress({ lengthComputable: true, loaded: 120, total: 220 })");
        assertThat(v(js, "$('up-count').textContent")).isEqualTo(t("up.count").replace("{total}", "২").replace("{n}", "১"));
        assertThat(v(js, "$('up-bar').value + '/' + $('up-bar').max")).as("the bar is the bytes, not the pages").isEqualTo("120/220");
        assertThat(v(js, "pageLines()")).isEqualTo(t("tray.page").replace("{n}", "১") + "|" + t("up.sent") + " / "
                + t("tray.page").replace("{n}", "২") + "|");
    }

    @Test
    void aStalledSendIsRetriedAsTheSamePressByHandAndWhenTheNetworkReturns() throws ScriptException {
        ScriptEngine js = load(one(0));
        js.eval("choose('gallery', [file('a.jpg', 'image/jpeg', 100), file('b.jpg', 'image/jpeg', 100)]); $('tray-send').click()");
        String first = (String) v(js, "sent(xhrs[0])");
        assertThat((Boolean) v(js, "run(30000)")).as("a send that goes quiet for 30 s is stopped").isTrue();
        assertThat((Boolean) v(js, "xhrs[0].aborted")).isTrue();
        assertThat((Boolean) v(js, "$('up-slow').hidden")).isFalse();
        assertThat((Boolean) v(js, "$('up-retry').hidden")).isFalse();
        assertThat(v(js, "pageLines()")).asString().contains(t("up.stuck"));
        assertThat((Boolean) v(js, "fireWindow('beforeunload').prevented")).as("leaving now would lose the pages: the browser asks").isTrue();

        js.eval("$('up-retry').click()");
        assertThat(n(js, "xhrs.length")).isEqualTo(2);
        assertThat(v(js, "sent(xhrs[1])")).as("the same key, patient, group and pages: a retry, never a second paper").isEqualTo(first);

        js.eval("xhrs[1].onerror()");   // the network drops again
        js.eval("fireWindow('online')");
        assertThat(n(js, "xhrs.length")).as("the network back: it tries again by itself, once").isEqualTo(3);
        assertThat(v(js, "sent(xhrs[2])")).isEqualTo(first);
        js.eval("fireWindow('online')");
        assertThat(n(js, "xhrs.length")).as("a second online event while sending sends nothing more").isEqualTo(3);

        js.eval("xhrs[2].status = 200; xhrs[2].ctype = 'application/json'; xhrs[2].responseText = JSON.stringify({ next: '/app/documents/"
                + WebUiTestKit.DOC_ID + "' }); xhrs[2].onload()");
        assertThat(v(js, "assigned.join()")).isEqualTo("/app/documents/" + WebUiTestKit.DOC_ID);
        assertThat((Boolean) v(js, "fireWindow('beforeunload').prevented")).as("sent: nothing to lose").isFalse();
    }

    @Test
    void aRefusalOrASignedOutAnswerComesBackToTheTrayWithItsOwnWords() throws ScriptException {
        ScriptEngine js = load(one(0));
        js.eval("choose('camera', [file('a.jpg', 'image/jpeg', 100)]); $('tray-send').click()");
        js.eval("xhrs[0].status = 413; xhrs[0].ctype = 'application/json'; xhrs[0].responseText = JSON.stringify({ reason: 'x', text: 'the server words' }); xhrs[0].onload()");
        assertThat(v(js, "$('add-alert').textContent")).isEqualTo("the server words");
        assertThat((Boolean) v(js, "$('tray').hidden")).isFalse();
        assertThat(n(js, "assigned.length")).isZero();

        // where a lapsed session actually lands, from the router itself: the browser follows the redirect, and the script
        // sees the page it landed on (its URL and type), not the redirect
        java.net.URI landed = signedOutLanding();
        String landedType = String.valueOf(kit.client.get().uri(landed.getPath()).exchange().expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().getContentType());
        js.eval("$('tray-send').click(); xhrs[1].status = 200; xhrs[1].ctype = '" + landedType + "'; xhrs[1].responseURL = '" + landed + "'; xhrs[1].onload()");
        assertThat(v(js, "$('add-alert').textContent")).as("the session lapsed: " + landed + " answered").isEqualTo(t("upload.err.signed_out"));

        js.eval("$('tray-send').click(); xhrs[2].status = 500; xhrs[2].ctype = 'text/html'; xhrs[2].responseURL = 'https://medioo.example/app/add'; xhrs[2].onload()");
        assertThat(v(js, "$('add-alert').textContent")).isEqualTo(t("app.err.generic"));
    }

    /** POST /app/add with no session, as the router answers it: the absolute URL the browser ends up on. */
    private java.net.URI signedOutLanding() {
        java.net.URI location = kit.client.post().uri("/app/add").accept(org.springframework.http.MediaType.APPLICATION_JSON).exchange()
                .expectStatus().is3xxRedirection().returnResult(Void.class).getResponseHeaders().getLocation();
        assertThat(location).as("a POST with no session is redirected").isNotNull();
        return java.net.URI.create("https://medioo.example/app/add").resolve(location);
    }

    @Test
    void theBarMovesWithTheBytesEvenWhenThereIsOnlyOnePage() throws ScriptException {
        ScriptEngine js = load(one(0));
        js.eval("choose('camera', [file('a.jpg', 'image/jpeg', 100)]); $('tray-send').click()");
        js.eval("xhrs[0].upload.onprogress({ lengthComputable: true, loaded: 50, total: 100 })");
        assertThat(v(js, "$('up-bar').value / $('up-bar').max")).as("half the bytes: half the bar").isEqualTo(0.5);
        assertThat(v(js, "$('up-count').textContent")).as("the page counts once the server has it")
                .isEqualTo(t("up.count").replace("{total}", "১").replace("{n}", "০"));
        js.eval("xhrs[0].upload.onprogress({ lengthComputable: true, loaded: 100, total: 100 })");
        assertThat(v(js, "$('up-bar').value / $('up-bar').max")).isEqualTo(1.0);
    }

    @Test
    void aQuietSendIsAStallButEveryByteSentAndTheServerStillWorkingIsNot() throws ScriptException {
        ScriptEngine js = load(one(0));
        js.eval("choose('camera', [file('a.jpg', 'image/jpeg', 100)]); $('tray-send').click()");
        js.eval("xhrs[0].upload.onprogress({ lengthComputable: true, loaded: 40, total: 100 })");
        assertThat((Boolean) v(js, "run(30000)")).as("bytes left to send and nothing moves for 30 s: a stall").isTrue();
        assertThat((Boolean) v(js, "xhrs[0].aborted")).isTrue();

        js.eval("$('up-retry').click(); xhrs[1].upload.onprogress({ lengthComputable: true, loaded: 100, total: 100 })");
        assertThat((Boolean) v(js, "run(30000)")).as("every byte sent: the server is storing the pages, not a stalled network").isFalse();
        assertThat((Boolean) v(js, "xhrs[1].aborted || !$('up-retry').hidden || !$('up-slow').hidden")).isFalse();
        assertThat((Boolean) v(js, "run(120000)")).as("a server that never answers is still given up on").isTrue();
        assertThat((Boolean) v(js, "xhrs[1].aborted")).isTrue();

        js.eval("$('up-retry').click(); xhrs[2].upload.onload()");   // the browser's own word that the body has gone
        assertThat((Boolean) v(js, "run(30000)")).isFalse();
        assertThat((Boolean) v(js, "xhrs[2].aborted")).isFalse();
    }

    @Test
    void aSecondTapOnTheRetryWhileItIsSendingSendsNothingAndAFreshStallStillRetries() throws ScriptException {
        ScriptEngine js = load(one(0));
        js.eval("choose('camera', [file('a.jpg', 'image/jpeg', 100)]); $('tray-send').click(); run(30000)");
        assertThat((Boolean) v(js, "$('up-retry').hidden")).isFalse();
        js.eval("$('up-retry').click(); $('up-retry').click()");
        assertThat(n(js, "xhrs.length")).as("a second tap during the retry").isEqualTo(2);
        js.eval("xhrs[1].onerror()");
        js.eval("$('up-retry').click()");
        assertThat(n(js, "xhrs.length")).as("a fresh stall: the retry works again").isEqualTo(3);
    }

    // --- the patient is named, and a retry stays with that patient ----------------------------------------------------

    @Test
    void theUploadStepNamesThePatientAndARetryGoesForThatPatientWhateverTheChipSays() throws ScriptException {
        ScriptEngine only = load(one(0));
        only.eval("choose('camera', [file('a.jpg', 'image/jpeg', 100)]); $('tray-send').click()");
        assertThat(v(only, "$('up-title').textContent")).as("one patient, preselected: still named")
                .isEqualTo(t("up.title.for", kit.copy.poss(Lang.BN, "আম্মা")));

        ScriptEngine js = load(new UploadOptions(List.of(ma, baba), List.of(), AddReportSheetPageTest.used(0)));
        js.eval("pick('" + baba.patient().id() + "'); choose('camera', [file('a.jpg', 'image/jpeg', 100)]); $('tray-send').click()");
        String title = t("up.title.for", kit.copy.poss(Lang.BN, "আব্বু"));
        assertThat(v(js, "$('up-title').textContent")).isEqualTo(title);
        assertThat(n(js, "F.querySelectorAll('input[name=patient]').filter(function (r) { return !r.disabled; }).length"))
                .as("the picker is locked while the paper is on its way").isZero();
        String first = (String) v(js, "sent(xhrs[0])");
        assertThat(first).startsWith("patient=" + baba.patient().id() + "&");
        js.eval("run(30000)");
        js.eval("pick('" + ma.patient().id() + "')");   // the chip moves anyway (the fake lets a disabled radio change)
        js.eval("$('up-retry').click()");
        assertThat(v(js, "sent(xhrs[1])")).as("the retry is for the patient the paper was sent for").isEqualTo(first);
        assertThat(v(js, "$('up-title').textContent")).isEqualTo(title);

        js.eval("xhrs[1].status = 413; xhrs[1].ctype = 'application/json'; xhrs[1].responseText = JSON.stringify({ reason: 'x', text: 'no' }); xhrs[1].onload()");
        assertThat(n(js, "F.querySelectorAll('input[name=patient]').filter(function (r) { return r.disabled; }).length"))
                .as("refused, back on the tray: the family may choose again").isZero();
    }

    // --- the tray is a screen of its own (s08), and so is the upload step (s09) ---------------------------------------

    @Test
    void theWaysInAndTheTipsGiveWayToTheTrayAndTheUploadStepAndFocusGoesThere() throws ScriptException {
        ScriptEngine js = load(one(0));
        String chrome = "[$('add-src').hidden, $('add-tips').hidden, $('add-quota').hidden].join()";
        assertThat(v(js, chrome)).as("the sheet before a page is chosen").isEqualTo("false,false,false");
        js.eval("choose('gallery', [file('a.jpg', 'image/jpeg', 100), file('b.jpg', 'image/jpeg', 100)])");
        assertThat(v(js, chrome)).as("the tray: its own screen").isEqualTo("true,true,true");
        assertThat((Boolean) v(js, "focused === $('tray-title')")).as("a phone's screen moves to the tray").isTrue();

        js.eval("$('tray-send').click()");
        assertThat(v(js, chrome)).as("the upload step: its own screen").isEqualTo("true,true,true");
        assertThat((Boolean) v(js, "focused === $('up-title')")).isTrue();
        js.eval("choose('camera', [file('c.jpg', 'image/jpeg', 100)])");   // a way in reached anyway while sending
        assertThat((Boolean) v(js, "$('tray').hidden")).as("no tray beside the upload step").isTrue();
        js.eval("xhrs[0].status = 413; xhrs[0].ctype = 'application/json'; xhrs[0].responseText = JSON.stringify({ reason: 'x', text: 'no' }); xhrs[0].onload()");
        assertThat(n(js, "items().length")).as("nothing was added while sending").isEqualTo(2);
        assertThat(v(js, chrome)).as("refused: back on the tray").isEqualTo("true,true,true");

        js.eval("items()[0].querySelector('.t-remove').click(); items()[0].querySelector('.t-remove').click()");
        assertThat((Boolean) v(js, "$('tray').hidden")).isTrue();
        assertThat(v(js, chrome)).as("every page removed: the ways in again").isEqualTo("false,false,false");
    }

    // --- the downscale, when the server turns it on (D5) --------------------------------------------------------------

    /** A fake bitmap and canvas that record what is drawn; {@code next} is the size of the JPEG the canvas makes. */
    private static final String CANVAS = """
            var drawn = [], next = 0;
            F.dataset.downscaleEdge = '2200';
            function createImageBitmap(f, o) { drawn.push('decode:' + o.imageOrientation); return Promise.resolve({ width: f.w, height: f.h, close: function () { drawn.push('close'); } }); }
            var plainElement = document.createElement;
            document.createElement = function (t) {
              var e = plainElement(t);
              if (t === 'canvas') {
                e.getContext = function () { return {
                  fillRect: function (x, y, w, h) { drawn.push('fill:' + this.fillStyle + ':' + w + 'x' + h); },
                  drawImage: function (b, x, y, w, h) { drawn.push('draw:' + w + 'x' + h); } }; };
                e.toBlob = function (cb, type, q) { drawn.push('jpeg:' + type + ':' + q + ':' + e.width + 'x' + e.height); cb({ size: next, type: type }); };
              }
              return e;
            };
            function photo(name, type, size, w, h) { var f = file(name, type, size); f.w = w; f.h = h; return f; }
            """;

    private String[] downscaled(String photo, int canvasBytes) throws ScriptException {
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(one(0)));
        ScriptEngine js = run(kit.page("/app/add", "bn"), CANVAS);
        js.eval("next = " + canvasBytes + "; choose('camera', [" + photo + "]); $('tray-send').click()");
        String sent = (String) v(js, "sent(xhrs[0])");
        return new String[]{sent.substring(sent.indexOf("&file=") + 1), (String) v(js, "drawn.join(' ')")};
    }

    @Test
    void aBigPhotoIsScaledOnThePhoneOverWhiteAndOnlyASmallerResultIsSent() throws ScriptException {
        String[] big = downscaled("photo('IMG_0001.jpg', 'image/jpeg', 5000000, 4000, 3000)", 800000);
        assertThat(big[0]).as("the scaled JPEG goes, as page-1").isEqualTo("file=page-1.jpg:800000");
        assertThat(big[1]).as("upright, white first (a JPEG has no alpha), the bitmap freed, then encoded at the server's quality")
                .isEqualTo("decode:from-image fill:white:2200x1650 draw:2200x1650 close jpeg:image/jpeg:0.92:2200x1650");

        String[] worse = downscaled("photo('Screenshot.png', 'image/png', 900000, 1080, 2400)", 950000);
        assertThat(worse[0]).as("a result bigger than the original: the original goes, as it was").isEqualTo("file=page-1.png:900000");
        assertThat(worse[1]).contains("jpeg:image/jpeg:0.92:990x2200");

        String[] small = downscaled("photo('t1.jpg', 'image/jpeg', 230947, 1240, 1754)", 1);
        assertThat(small[0]).as("a small JPEG goes byte for byte").isEqualTo("file=page-1.jpg:230947");
        assertThat(small[1]).as("and is never re-encoded").doesNotContain("jpeg:").doesNotContain("draw:");
    }

    // --- the tray -----------------------------------------------------------------------------------------------------

    @Test
    void theTrayReordersRemovesAndStopsAtTheLimitWithFocusOnTheMovedPage() throws ScriptException {
        ScriptEngine js = load(new UploadOptions(List.of(ma), List.of(), DocumentAllowance.unlimited(java.time.YearMonth.of(2026, 10))));
        js.eval("choose('gallery', [file('a.jpg', 'image/jpeg', 1), file('b.jpg', 'image/jpeg', 2), file('c.jpg', 'image/jpeg', 3)])");
        assertThat(n(js, "items().length")).isEqualTo(3);
        assertThat((Boolean) v(js, "items()[0].querySelector('.t-up').disabled")).as("▲ on পাতা ১").isTrue();
        assertThat((Boolean) v(js, "items()[2].querySelector('.t-down').disabled")).as("▼ on the last").isTrue();
        assertThat((Boolean) v(js, "items()[1].querySelector('.t-up').disabled || items()[1].querySelector('.t-down').disabled")).isFalse();
        assertThat(v(js, "items()[1].querySelector('.t-up').getAttribute('aria-label')")).isEqualTo(t("tray.up").replace("{n}", "২"));
        assertThat(v(js, "items()[0].querySelector('.t-src').textContent")).isEqualTo(t("tray.src.gallery"));

        js.eval("items()[0].querySelector('.t-down').click()");
        assertThat(v(js, "items().map(function (li) { return li.querySelector('.t-thumb').children[0].attrs.src || li.querySelector('.t-thumb').children[0].src; }).join()"))
                .isEqualTo("blob:2,blob:1,blob:3");
        assertThat((Boolean) v(js, "focused === items()[1].querySelector('.t-down')")).as("focus follows the moved page").isTrue();

        js.eval("items()[1].querySelector('.t-remove').click()");
        assertThat(n(js, "items().length")).isEqualTo(2);
        assertThat(v(js, "revoked.join()")).isEqualTo("blob:1");
        assertThat(v(js, "$('tray-title').textContent")).isEqualTo(t("tray.title").replace("{n}", "২"));

        js.eval("var many = []; for (var i = 0; i < 12; i++) { many.push(file('p' + i + '.jpg', 'image/jpeg', 1)); } choose('gallery', many)");
        assertThat(n(js, "items().length")).as("at most ten pages a paper").isEqualTo(10);
        assertThat(v(js, "$('add-alert').textContent")).isEqualTo(t("tray.limit").replace("{max}", "১০").replace("{n}", "১০"));
        assertThat((Boolean) v(js, "$('tray-more').disabled")).isTrue();
    }
}

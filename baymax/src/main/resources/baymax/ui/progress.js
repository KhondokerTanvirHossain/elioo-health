// Reading page only (spec §10). Polls the stored status every 5 s; when it changes, reloads so the server renders the
// result, retake or failure screen. Shows the "taking longer" line the server drew hidden when its seconds run out.
// No inline script, no HTML from the network, no copy here: CSP script-src/connect-src 'self'. ProgressScriptTest runs it.
(function () {
  var el = document.querySelector('[data-status-url]');
  if (!el) { return; }
  var url = el.getAttribute('data-status-url'), current = el.getAttribute('data-status');
  var slowIn = parseInt(el.getAttribute('data-slow-in'), 10), slow = document.getElementById('slow');
  if (slow && slowIn >= 0) { setTimeout(function () { slow.hidden = false; }, slowIn * 1000); }
  var RELOADING = {};
  function again() { setTimeout(tick, 5000); }
  function tick() {
    fetch(url, { credentials: 'same-origin', headers: { 'Accept': 'application/json' }, cache: 'no-store' })
      .then(function (r) {
        if (r.status >= 500) { return null; }                 // the server in trouble: keep the page, ask again
        var json = (r.headers.get('content-type') || '').indexOf('application/json') === 0;
        if (!r.ok || !json) { location.reload(); return RELOADING; }   // signed out, or the paper gone: the server answers
        return r.json();
      })
      .then(function (j) {
        if (j === RELOADING) { return; }
        if (j && j.status && j.status !== current) { location.reload(); return; }
        again();
      })
      .catch(again);                                           // offline: keep the page, try again
  }
  again();
})();

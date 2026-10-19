// Reading page only (spec §10). Polls the stored status every 5 s; when it changes, reloads so the server renders the
// result, retake or failure screen. No inline script, no HTML from the network: CSP script-src/connect-src 'self'.
(function () {
  var el = document.querySelector('[data-status-url]');
  if (!el) { return; }
  var url = el.getAttribute('data-status-url'), current = el.getAttribute('data-status');
  function tick() {
    fetch(url, { credentials: 'same-origin', headers: { 'Accept': 'application/json' }, cache: 'no-store' })
      .then(function (r) {
        var json = (r.headers.get('content-type') || '').indexOf('application/json') === 0;
        if (!r.ok || !json) { location.reload(); return null; }   // signed out or an error page: let the server answer
        return r.json();
      })
      .then(function (j) { if (j && j.status !== current) { location.reload(); } else if (j) { setTimeout(tick, 5000); } })
      .catch(function () { setTimeout(tick, 5000); });                // offline: keep the page, try again
  }
  setTimeout(tick, 5000);
})();

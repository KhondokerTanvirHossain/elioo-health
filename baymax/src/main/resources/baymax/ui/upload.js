/*
 * Medioo add-report sheet (spec 4.7-4.9, plan area 4). Enhances the server-rendered form; without this file the form
 * posts as it is. Holds no copy: every visible string comes from data-* on #up-copy, in the page's language.
 * Never shows or sends a file's own name (it may name the patient): pages go as page-1.jpg, page-2.pdf, ...
 * ES5 only, so an old Android WebView parses it (UploadScriptTest runs it under an ES5 engine).
 */
(function (root) {
  'use strict';

  /** ASCII digits in the page's digit set ("0123456789" or the Bangla ten). */
  function digits(n, set) {
    return String(n).replace(/[0-9]/g, function (d) { return set.charAt(+d); });
  }
  /** {name} slots filled from values, counts in the page's digits; a slot with no value stays as written. */
  function fill(template, values, set) {
    return String(template || '').replace(/\{([a-z]+)\}/g, function (all, k) {
      return Object.prototype.hasOwnProperty.call(values, k) ? digits(values[k], set) : all;
    });
  }
  /** A copy of the tray with item i moved by delta; past either end, an unchanged copy. Never mutates the tray. */
  function move(list, i, delta) {
    var out = list.slice(), j = i + delta;
    if (i < 0 || i >= out.length || j < 0 || j >= out.length) { return out; }
    var t = out[i]; out[i] = out[j]; out[j] = t;
    return out;
  }
  /** "আলাদা আলাদা" is a paper per page; one report is one paper whatever the pages. */
  function documentsNeeded(count, group) { return group === 'separate' ? count : (count > 0 ? 1 : 0); }
  /** The allowance, before a byte leaves the phone; remaining < 0 is a paying family, with no cap. */
  function allowed(count, group, remaining) { return remaining < 0 || documentsNeeded(count, group) <= remaining; }
  /** null = send the file as it is. Only what is bigger than the target is scaled; a small JPEG is never re-compressed. */
  function plan(width, height, bytes, type, cfg) {
    if (!cfg.edge || !/^image\/(jpeg|png|webp)$/.test(type)) { return null; }
    var longest = Math.max(width, height);
    if (type === 'image/jpeg' && longest <= cfg.edge && bytes <= cfg.passBytes) { return null; }
    var scale = Math.min(1, cfg.edge / longest);
    return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)), quality: cfg.quality };
  }
  /** Pages whose bytes have all left the phone; the last only once the server has answered. */
  function pagesSent(loaded, total, sizes, done) {
    if (done) { return sizes.length; }
    var all = 0, sum = 0, n = 0, i;
    for (i = 0; i < sizes.length; i++) { all += sizes[i]; }
    var reached = total > 0 ? (loaded / total) * all : 0;
    for (i = 0; i < sizes.length - 1; i++) { sum += sizes[i]; if (reached >= sum) { n++; } else { break; } }
    return n;
  }
  /** The only name a page is sent under: never the file's own name, which may name the patient. */
  function pageName(i, ext) { return 'page-' + (i + 1) + '.' + ext; }
  /** One press of "পাঠান" as fields and files: the same pages and key always make the same request (a retry). */
  function buildRequest(pages, group, key, patient) {
    return {
      fields: [['patient', patient], ['group', pages.length < 2 ? 'one' : group], ['upload_key', key]],
      files: pages.map(function (p) { return { blob: p.blob, name: p.name }; })
    };
  }

  var api = { digits: digits, fill: fill, move: move, documentsNeeded: documentsNeeded, allowed: allowed, plan: plan,
    pagesSent: pagesSent, pageName: pageName, buildRequest: buildRequest };
  if (typeof module === 'object' && module.exports) { module.exports = api; return; }
  root.MediooUpload = api;
  /* DOM wiring: Task W3.3 */
})(typeof window !== 'undefined' ? window : this);

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

  // --- the sheet: only when this browser can do all of it; otherwise the form posts as it is ---------------------
  var form = document.getElementById('add');
  var copyEl = document.getElementById('up-copy');
  if (!form || !copyEl || !root.FormData || !root.XMLHttpRequest || !root.URL || !URL.createObjectURL) { return; }
  document.documentElement.classList.add('js');

  var copy = copyEl.dataset;
  var SRC = { camera: copy.traySrcCamera, gallery: copy.traySrcGallery, pdf: copy.traySrcPdf };
  var set = form.dataset.digits || '0123456789';
  var cfg = { edge: +form.dataset.downscaleEdge || 0, quality: +form.dataset.downscaleQuality || 0.92, passBytes: +form.dataset.passBytes || 1500000 };
  var maxPages = +form.dataset.maxPages || 10, maxBytes = +form.dataset.maxRequestBytes || 0;
  var remaining = form.dataset.remaining === undefined ? -1 : +form.dataset.remaining;
  var known = /^(image\/(jpeg|png|webp|heic|heif)|application\/pdf)$/;
  var tray = [], busy = false, lastSent = 0, again = null;
  var $ = function (id) { return document.getElementById(id); };
  var trayEl = $('tray'), listEl = $('tray-list'), upEl = $('up'), alertEl = $('add-alert'), itemTpl = $('t-item');
  trayEl.insertBefore($('group'), $('tray-order'));   // one set of group radios: the no-JS fieldset moves into the tray

  function say(text) { alertEl.textContent = text || ''; alertEl.hidden = !text; }
  function chosen(name) { var el = form.querySelector('input[name="' + name + '"]:checked'); return el ? el.value : null; }
  function rowsFollowPatient() {
    var on = !!chosen('patient');
    Array.prototype.forEach.call(form.querySelectorAll('.src-row'), function (row) {
      row.classList.toggle('off', !on);
      row.querySelector('input').disabled = !on;
    });
  }

  function add(files, src) {
    say('');
    for (var i = 0; i < files.length; i++) {
      var f = files[i];
      if (f.type && !known.test(f.type)) { say(copy.unsupported); continue; }   // a DOCX never leaves the phone
      if (tray.length >= maxPages) { say(fill(copy.trayLimit, { max: maxPages, n: tray.length }, set)); break; }
      tray.push({ file: f, src: src, url: /^image\//.test(f.type) ? URL.createObjectURL(f) : null });
    }
    render();
    if (src === 'pdf' && tray.length === 1) { send(); }   // spec 4.8: a single PDF or screenshot skips the tray
  }

  function render() {
    trayEl.hidden = tray.length === 0;
    $('tray-title').textContent = fill(copy.trayTitle, { n: tray.length }, set);
    $('group').hidden = tray.length < 2;
    $('tray-limit').textContent = fill(copy.trayLimit, { max: maxPages, n: tray.length }, set);
    $('tray-more').disabled = tray.length >= maxPages;
    while (listEl.firstChild) { listEl.removeChild(listEl.firstChild); }
    tray.forEach(function (item, i) {
      var li = itemTpl.content.firstElementChild.cloneNode(true), n = { n: i + 1 };
      li.querySelector('.t-page').textContent = fill(copy.trayPage, n, set);
      li.querySelector('.t-src').textContent = SRC[item.src] || '';
      if (item.url) { var img = document.createElement('img'); img.alt = ''; img.src = item.url; li.querySelector('.t-thumb').appendChild(img); }
      else { li.querySelector('.t-thumb').classList.add('pdf'); }
      wire(li, '.t-up', copy.trayUp, n, i === 0, function () { tray = move(tray, i, -1); render(); focus(i - 1, '.t-up'); });
      wire(li, '.t-down', copy.trayDown, n, i === tray.length - 1, function () { tray = move(tray, i, 1); render(); focus(i + 1, '.t-down'); });
      wire(li, '.t-remove', copy.trayRemove, n, false, function () {
        if (item.url) { URL.revokeObjectURL(item.url); }
        tray.splice(i, 1); render(); focus(Math.min(i, tray.length - 1), '.t-remove');
      });
      listEl.appendChild(li);
    });
  }
  function wire(li, sel, label, n, disabled, onClick) {
    var b = li.querySelector(sel);
    b.setAttribute('aria-label', fill(label, n, set)); b.disabled = disabled; b.addEventListener('click', onClick);
  }
  /** Focus stays with the page the family moved; at an end, or once removed, on a button that is still there. */
  function focus(i, sel) {
    var li = listEl.children[i], b = li && li.querySelector(sel);
    (b && !b.disabled ? b : (li ? li.querySelector('.t-remove') : $('tray-more'))).focus();
  }

  function prepare(item, i) {
    var f = item.file, ext = f.type === 'application/pdf' ? 'pdf' : (f.type === 'image/png' ? 'png' : 'jpg');
    function named(blob, e) { return { blob: blob, name: pageName(i, e) }; }
    if (!cfg.edge || !root.createImageBitmap || !/^image\/(jpeg|png|webp)$/.test(f.type)) { return Promise.resolve(named(f, ext)); }
    return createImageBitmap(f, { imageOrientation: 'from-image' }).then(function (bmp) {
      var p = plan(bmp.width, bmp.height, f.size, f.type, cfg);
      if (!p) { bmp.close(); return named(f, ext); }
      var canvas = document.createElement('canvas'), g = canvas.getContext('2d');
      canvas.width = p.width; canvas.height = p.height;
      g.fillStyle = 'white'; g.fillRect(0, 0, p.width, p.height);   // JPEG has no alpha: a transparent PNG must not turn black
      g.imageSmoothingQuality = 'high'; g.drawImage(bmp, 0, 0, p.width, p.height); bmp.close();
      return new Promise(function (ok) {
        canvas.toBlob(function (blob) { ok(blob && blob.size < f.size ? named(blob, 'jpg') : named(f, ext)); }, 'image/jpeg', p.quality);
      });
    })['catch'](function () { return named(f, ext); });   // a format this browser cannot decode goes as is; the server decides
  }
  function prepareAll(i, out) {
    return i >= tray.length ? Promise.resolve(out) : prepare(tray[i], i).then(function (p) { out.push(p); return prepareAll(i + 1, out); });
  }

  function send() {
    if (busy) { return; }
    var group = tray.length < 2 ? 'one' : (chosen('group') || 'one');
    if (!chosen('patient')) { say(copy.choosePatient); return; }
    if (!tray.length) { say(copy.noFile); return; }
    if (!allowed(tray.length, group, remaining)) { say(copy.overAllowance); return; }   // before a byte leaves the phone
    busy = true; say(''); lastSent = 0; progress(0, tray.length, false);
    prepareAll(0, []).then(function (pages) {
      var total = pages.reduce(function (s, p) { return s + p.blob.size; }, 0);
      if (maxBytes && total > maxBytes) { busy = false; back(); say(copy.tooLarge); return; }
      post(pages, group);
    });
  }

  /** One press on the wire. A retry calls this again with the same pages and the form's same key: never a second paper. */
  function post(pages, group) {
    var fd = new FormData(), xhr = new XMLHttpRequest(), quiet = null;
    var sizes = pages.map(function (p) { return p.blob.size; });
    var req = buildRequest(pages, group, form.elements.upload_key.value, chosen('patient'));
    req.fields.forEach(function (f) { fd.append(f[0], f[1]); });
    req.files.forEach(function (f) { fd.append('file', f.blob, f.name); });
    function stall() { clearTimeout(quiet); quiet = setTimeout(function () { xhr.abort(); }, 30000); }
    xhr.open('POST', form.action);
    xhr.setRequestHeader('Accept', 'application/json');
    xhr.upload.onprogress = function (e) { stall(); if (e.lengthComputable) { progress(pagesSent(e.loaded, e.total, sizes, false), sizes.length, false); } };
    xhr.onload = function () {
      clearTimeout(quiet);
      var body = null;
      if (/json/.test(xhr.getResponseHeader('Content-Type') || '')) { try { body = JSON.parse(xhr.responseText); } catch (ignored) { body = null; } }
      busy = false;
      if (body && xhr.status < 300 && body.next) { progress(sizes.length, sizes.length, false); root.location.assign(body.next); return; }
      back();
      // no JSON: the session lapsed and the sign-in page answered, or the server failed outright
      say(body ? (body.text || copy.generic) : (/\/app\/?$/.test(xhr.responseURL || '') ? copy.signedOut : copy.generic));
    };
    xhr.onerror = xhr.onabort = function () { clearTimeout(quiet); stuck(pages, group); };
    stall();
    xhr.send(fd);
  }

  function progress(n, total, isStuck) {
    lastSent = n; trayEl.hidden = true; upEl.hidden = false;
    var line = fill(copy.upCount, { n: n, total: total }, set);
    $('up-count').textContent = line;
    $('up-bar').max = total; $('up-bar').value = n;
    $('up-bar').setAttribute('aria-label', line);
    $('up-slow').hidden = !isStuck;
    var ol = $('up-pages');
    while (ol.firstChild) { ol.removeChild(ol.firstChild); }
    for (var i = 0; i < total; i++) {
      var li = document.createElement('li'), a = document.createElement('span'), b = document.createElement('span');
      a.textContent = fill(copy.trayPage, { n: i + 1 }, set);
      b.textContent = i < n ? copy.upSent : (isStuck ? copy.upStuck : '');
      li.appendChild(a); li.appendChild(b); ol.appendChild(li);
    }
  }
  function stuck(pages, group) {
    progress(lastSent, pages.length, true);
    again = $('up-retry'); again.hidden = false;
    again.onclick = function () { again.hidden = true; post(pages, group); };   // the same upload_key: a retry, never a second paper
    root.addEventListener('online', function once() { root.removeEventListener('online', once); if (!again.hidden) { again.click(); } });
  }
  function back() { upEl.hidden = true; trayEl.hidden = tray.length === 0; }

  Array.prototype.forEach.call(form.querySelectorAll('input[type=file]'), function (input) {
    input.addEventListener('change', function () {
      $('tray-more').dataset.src = input.dataset.src === 'pdf' ? 'gallery' : input.dataset.src;
      add(input.files, input.dataset.src);
      input.value = '';   // the tray holds the files now; the same input can be used again
    });
  });
  $('tray-more').addEventListener('click', function () { form.querySelector('input[data-src="' + ($('tray-more').dataset.src || 'gallery') + '"]').click(); });
  $('tray-send').addEventListener('click', send);
  form.addEventListener('submit', function (e) { e.preventDefault(); send(); });
  Array.prototype.forEach.call(form.querySelectorAll('input[name=patient]'), function (r) { r.addEventListener('change', rowsFollowPatient); });
  root.addEventListener('beforeunload', function (e) { if (busy || (again && !again.hidden)) { e.preventDefault(); e.returnValue = ''; } });
  rowsFollowPatient();
})(typeof window !== 'undefined' ? window : this);

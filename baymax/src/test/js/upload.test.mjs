// Pure parts of the add-report sheet's script (plan D4, W3.2). Run: node --test baymax/src/test/js/*.test.mjs
// The DOM wiring is not exercised here; these are the rules it applies: counts, order, the allowance, the downscale
// plan, the per-page progress and exactly what one press sends.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const u = require('../../main/resources/baymax/ui/upload.js');
const BN = '০১২৩৪৫৬৭৮৯';

test('counts on a Bangla page are in Bangla digits, and a slot with no value is left visible, not blanked', () => {
  assert.equal(u.fill('পাঠানো হচ্ছে — {total}টির {n}টি পাতা', { n: 2, total: 3 }, BN), 'পাঠানো হচ্ছে — ৩টির ২টি পাতা');
  assert.equal(u.fill('{n} of {total} pages', { n: 10, total: 10 }, '0123456789'), '10 of 10 pages');
  assert.equal(u.fill('পাতা {n}', {}, BN), 'পাতা {n}');
  assert.equal(u.fill(undefined, { n: 1 }, BN), '');   // a missing data-* attribute shows nothing, never "undefined"
});

test('a move past either end changes nothing; a move inside swaps exactly two', () => {
  assert.deepEqual(u.move(['a', 'b', 'c'], 0, -1), ['a', 'b', 'c']);
  assert.deepEqual(u.move(['a', 'b', 'c'], 2, 1), ['a', 'b', 'c']);
  assert.deepEqual(u.move(['a', 'b', 'c'], 1, -1), ['b', 'a', 'c']);
  assert.deepEqual(u.move(['a', 'b', 'c'], 1, 1), ['a', 'c', 'b']);
  const list = ['a', 'b'];
  u.move(list, 0, 1);
  assert.deepEqual(list, ['a', 'b']);   // never mutates the tray it was given
});

test('one report is one document whatever the pages; separate pages are one each; the cap is checked before upload', () => {
  assert.equal(u.documentsNeeded(3, 'one'), 1);
  assert.equal(u.documentsNeeded(3, 'separate'), 3);
  assert.equal(u.documentsNeeded(0, 'one'), 0);
  assert.equal(u.allowed(3, 'separate', 2), false);
  assert.equal(u.allowed(2, 'separate', 2), true);
  assert.equal(u.allowed(9, 'one', 1), true);
  assert.equal(u.allowed(1, 'one', 0), false);
  assert.equal(u.allowed(9, 'separate', -1), true);   // a paying family: no cap
});

test('downscale is off unless the server turns it on; a small JPEG is never re-compressed; a PDF is never touched', () => {
  const on = { edge: 2200, quality: 0.92, passBytes: 1500000 };
  assert.equal(u.plan(4000, 3000, 5000000, 'image/jpeg', { ...on, edge: 0 }), null);
  assert.equal(u.plan(1240, 1754, 230947, 'image/jpeg', on), null);   // t1 goes byte for byte
  assert.deepEqual(u.plan(4000, 3000, 5000000, 'image/jpeg', on), { width: 2200, height: 1650, quality: 0.92 });
  assert.deepEqual(u.plan(1080, 2400, 900000, 'image/png', on), { width: 990, height: 2200, quality: 0.92 });
  assert.equal(u.plan(1000, 1000, 10, 'application/pdf', on), null);
  assert.equal(u.plan(4000, 3000, 5000000, 'image/heic', on), null);   // a format the canvas may not decode goes as is
});

test('a page counts as sent only when its bytes have left, and the last one only when the server has them all', () => {
  const sizes = [100, 100, 100];
  assert.equal(u.pagesSent(0, 330, sizes, false), 0);
  assert.equal(u.pagesSent(115, 330, sizes, false), 1);
  assert.equal(u.pagesSent(330, 330, sizes, false), 2);   // every byte written, not yet answered
  assert.equal(u.pagesSent(330, 330, sizes, true), 3);
  assert.equal(u.pagesSent(0, 0, sizes, false), 0);
});

test('a page is sent as page-N, never under its own name (a filename may name the patient)', () => {
  assert.equal(u.pageName(0, 'jpg'), 'page-1.jpg');
  assert.equal(u.pageName(9, 'pdf'), 'page-10.pdf');
  const req = u.buildRequest([{ blob: { size: 3, name: 'Rokeya-Begum-CBC.jpg' }, name: u.pageName(0, 'jpg') }], 'separate', 'k-0f8e2c1a', 'p-1');
  assert.deepEqual(req.files.map((f) => f.name), ['page-1.jpg']);
  assert.ok(!JSON.stringify(req.fields).includes('Rokeya'));
  assert.ok(!req.files.some((f) => f.name.includes('Rokeya')));
});

test('a retry is the same press: the same key, the same patient; one page is always one report', () => {
  const pages = [{ blob: { size: 3 }, name: 'page-1.jpg' }];
  const a = u.buildRequest(pages, 'separate', 'k-same-0001', 'p-1');
  const b = u.buildRequest(pages, 'separate', 'k-same-0001', 'p-1');
  assert.deepEqual(a.fields, b.fields);
  assert.deepEqual(a.fields, [['patient', 'p-1'], ['group', 'one'], ['upload_key', 'k-same-0001']]);
  const two = u.buildRequest(pages.concat([{ blob: { size: 4 }, name: 'page-2.jpg' }]), 'separate', 'k-same-0001', 'p-1');
  assert.deepEqual(two.fields[1], ['group', 'separate']);
  assert.deepEqual(u.buildRequest(pages.concat(pages), 'one', 'k', 'p').fields[1], ['group', 'one']);
});

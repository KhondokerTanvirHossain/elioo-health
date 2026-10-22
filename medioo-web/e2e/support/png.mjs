import { deflateSync } from "node:zlib";

/*
 * The synthetic crop and page images the e2e stub serves in place of Java's image routes (plan Wave 3, Shared
 * interfaces → e2e support). Each row's image is told apart by its pixels: its width is the stored crop width, or a
 * width hashed from its crop key, so a page that swapped two rows' crops shows two images whose naturalWidth is wrong.
 * One copy, used by the stub, by browser-api.ts and by every crop check.
 */

/** The crop key of a row: the `key` query parameter of its crop URL, or the URL itself. */
export function cropKey(row) {
  const src = row?.crop?.src ?? "";
  const key = new URL(src, "http://e2e.invalid").searchParams.get("key");
  return key ?? src;
}

/** A width from 40 to 400 px, stable per key (FNV-1a). */
export function hashWidth(key) {
  let h = 0x811c9dc5;
  for (const ch of String(key)) {
    h ^= ch.codePointAt(0);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return 40 + (h % 361);
}

/** The width a row's image is served at, and must decode at. */
export function expectedWidth(row) {
  return row?.crop?.width ?? hashWidth(cropKey(row));
}

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});

function crc32(bytes) {
  let c = 0xffffffff;
  for (const b of bytes) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const head = Buffer.alloc(8);
  head.writeUInt32BE(data.length, 0);
  head.write(type, 4, "ascii");
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([head.subarray(4), data])), 0);
  return Buffer.concat([head, data, crc]);
}

/** A grey PNG of `width` × `height`. */
export function png(width, height) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 0; // greyscale
  const row = Buffer.alloc(1 + width, 0x9a);
  row[0] = 0; // filter: none
  const raw = Buffer.concat(Array.from({ length: height }, () => row));
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", deflateSync(raw)),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

/** The image for one row: its expected width, and its stored height (or the width again). */
export function pngFor(row) {
  const width = expectedWidth(row);
  return png(width, row?.crop?.height ?? width);
}

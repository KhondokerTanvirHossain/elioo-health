package com.elioo.baymax.web.adapter.in.ui;

import org.brotli.dec.BrotliInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The parts of a served woff2 a page depends on, read from its bytes (W3C WOFF2 §4–5): which code points the font maps
 * to a glyph (cmap), the weight axis it really has (fvar), and the scripts its substitutions shape (GSUB). A woff2's
 * magic bytes say a file is there; this says what is in it.
 */
final class Woff2 {

    /** WOFF2 §4.2: the known-table tags, by index. */
    private static final String[] KNOWN = {"cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post", "cvt ", "fpgm",
            "glyf", "loca", "prep", "CFF ", "VORG", "EBDT", "EBLC", "gasp", "hdmx", "kern", "LTSH", "PCLT", "VDMX", "vhea",
            "vmtx", "BASE", "GDEF", "GPOS", "GSUB", "EBSC", "JSTF", "MATH", "CBDT", "CBLC", "COLR", "CPAL", "SVG ", "sbix",
            "acnt", "avar", "bdat", "bloc", "bsln", "cvar", "fdsc", "feat", "fmtx", "fvar", "gvar", "hsty", "just", "lcar",
            "mort", "morx", "opbd", "prop", "trak", "Zapf", "Silf", "Glat", "Gloc", "Feat", "Sill"};

    /** tag → the table's bytes as stored in the decompressed stream (glyf/loca transformed; cmap, fvar, GSUB never are). */
    final Map<String, ByteBuffer> tables;

    private Woff2(Map<String, ByteBuffer> tables) {
        this.tables = tables;
    }

    static Woff2 read(byte[] file) {
        ByteBuffer in = ByteBuffer.wrap(file);
        if (in.getInt(0) != 0x774F4632) {   // "wOF2"
            throw new IllegalArgumentException("not a woff2");
        }
        int numTables = Short.toUnsignedInt(in.getShort(12));
        int compressed = in.getInt(20);
        in.position(48);
        String[] tags = new String[numTables];
        long[] lengths = new long[numTables];
        for (int i = 0; i < numTables; i++) {
            int flags = Byte.toUnsignedInt(in.get());
            int index = flags & 0x3F;
            int transform = flags >> 6;
            if (index == 63) {
                byte[] t = new byte[4];
                in.get(t);
                tags[i] = new String(t, StandardCharsets.US_ASCII);
            } else {
                tags[i] = KNOWN[index];
            }
            long orig = base128(in);
            boolean glyfOrLoca = "glyf".equals(tags[i]) || "loca".equals(tags[i]);
            boolean transformed = glyfOrLoca ? transform == 0 : transform != 0;
            lengths[i] = transformed ? base128(in) : orig;
        }
        byte[] stream = new byte[compressed];
        in.get(stream);
        byte[] data;
        try (BrotliInputStream b = new BrotliInputStream(new ByteArrayInputStream(stream))) {
            data = b.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, ByteBuffer> tables = new LinkedHashMap<>();
        int at = 0;
        for (int i = 0; i < numTables; i++) {
            tables.put(tags[i], ByteBuffer.wrap(data, at, (int) lengths[i]).slice());
            at += (int) lengths[i];
        }
        if (at != data.length) {
            throw new IllegalStateException("table lengths sum to " + at + " but the stream holds " + data.length);
        }
        return new Woff2(tables);
    }

    /** Every code point the best Unicode cmap subtable maps to a glyph other than .notdef. */
    Set<Integer> codePoints() {
        ByteBuffer cmap = table("cmap");
        int n = Short.toUnsignedInt(cmap.getShort(2));
        int best = -1;
        int bestRank = -1;
        for (int i = 0; i < n; i++) {
            int platform = Short.toUnsignedInt(cmap.getShort(4 + i * 8));
            int encoding = Short.toUnsignedInt(cmap.getShort(6 + i * 8));
            int offset = cmap.getInt(8 + i * 8);
            int format = Short.toUnsignedInt(cmap.getShort(offset));
            int rank = format == 12 ? 2 : format == 4 && (platform == 3 && encoding == 1 || platform == 0) ? 1 : -1;
            if (rank > bestRank) {
                best = offset;
                bestRank = rank;
            }
        }
        if (best < 0) {
            throw new IllegalStateException("no Unicode cmap subtable");
        }
        Set<Integer> out = new TreeSet<>();
        ByteBuffer t = cmap.duplicate().position(best).slice();
        if (bestRank == 2) {
            long groups = Integer.toUnsignedLong(t.getInt(12));
            for (int g = 0; g < groups; g++) {
                int start = t.getInt(16 + g * 12);
                int end = t.getInt(20 + g * 12);
                int glyph = t.getInt(24 + g * 12);
                for (int c = start; c <= end; c++) {
                    if (glyph + (c - start) != 0) {
                        out.add(c);
                    }
                }
            }
            return out;
        }
        int segs = Short.toUnsignedInt(t.getShort(6)) / 2;
        int ends = 14;
        int starts = ends + segs * 2 + 2;
        int deltas = starts + segs * 2;
        int rangeOffsets = deltas + segs * 2;
        for (int s = 0; s < segs; s++) {
            int end = Short.toUnsignedInt(t.getShort(ends + s * 2));
            int start = Short.toUnsignedInt(t.getShort(starts + s * 2));
            int delta = t.getShort(deltas + s * 2);
            int ro = Short.toUnsignedInt(t.getShort(rangeOffsets + s * 2));
            for (int c = start; c <= end && c != 0xFFFF; c++) {
                int glyph;
                if (ro == 0) {
                    glyph = (c + delta) & 0xFFFF;
                } else {
                    int g = Short.toUnsignedInt(t.getShort(rangeOffsets + s * 2 + ro + (c - start) * 2));
                    glyph = g == 0 ? 0 : (g + delta) & 0xFFFF;
                }
                if (glyph != 0) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /** The weight axis as {min, max}, from fvar; a static font has no fvar and fails here. */
    double[] weightAxis() {
        ByteBuffer fvar = table("fvar");
        int axesAt = Short.toUnsignedInt(fvar.getShort(4));
        int count = Short.toUnsignedInt(fvar.getShort(8));
        int size = Short.toUnsignedInt(fvar.getShort(10));
        for (int i = 0; i < count; i++) {
            int a = axesAt + i * size;
            if (fvar.getInt(a) == 0x77676874) {   // "wght"
                return new double[]{fvar.getInt(a + 4) / 65536.0, fvar.getInt(a + 12) / 65536.0};
            }
        }
        throw new IllegalStateException("no wght axis");
    }

    /** The script tags GSUB's ScriptList names: the scripts whose conjuncts and reordering this font can shape. */
    Set<String> substitutionScripts() {
        ByteBuffer gsub = table("GSUB");
        int list = Short.toUnsignedInt(gsub.getShort(4));
        int n = Short.toUnsignedInt(gsub.getShort(list));
        Set<String> out = new TreeSet<>();
        for (int i = 0; i < n; i++) {
            byte[] tag = new byte[4];
            gsub.get(list + 2 + i * 6, tag);
            out.add(new String(tag, StandardCharsets.US_ASCII));
        }
        return out;
    }

    /**
     * The name table's Windows-Unicode English records (platform 3, encoding 1, language 0x409), by name ID: 0 is the
     * copyright line, 13 the licence description, 14 the licence URL (OpenType "name" table).
     */
    Map<Integer, String> names() {
        ByteBuffer name = table("name");
        int count = Short.toUnsignedInt(name.getShort(2));
        int strings = Short.toUnsignedInt(name.getShort(4));
        Map<Integer, String> out = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            int r = 6 + i * 12;
            int platform = Short.toUnsignedInt(name.getShort(r));
            int encoding = Short.toUnsignedInt(name.getShort(r + 2));
            int language = Short.toUnsignedInt(name.getShort(r + 4));
            if (platform != 3 || encoding != 1 || language != 0x409) {
                continue;
            }
            int id = Short.toUnsignedInt(name.getShort(r + 6));
            byte[] text = new byte[Short.toUnsignedInt(name.getShort(r + 8))];
            name.get(strings + Short.toUnsignedInt(name.getShort(r + 10)), text);
            out.put(id, new String(text, StandardCharsets.UTF_16BE));
        }
        return out;
    }

    private ByteBuffer table(String tag) {
        ByteBuffer t = tables.get(tag);
        if (t == null) {
            throw new IllegalStateException("no " + tag + " table; tables are " + tables.keySet());
        }
        return t.duplicate();
    }

    /** WOFF2 §4.1 UIntBase128. */
    private static long base128(ByteBuffer in) {
        long v = 0;
        for (int i = 0; i < 5; i++) {
            int b = Byte.toUnsignedInt(in.get());
            v = (v << 7) | (b & 0x7F);
            if ((b & 0x80) == 0) {
                return v;
            }
        }
        throw new IllegalStateException("UIntBase128 longer than 5 bytes");
    }
}

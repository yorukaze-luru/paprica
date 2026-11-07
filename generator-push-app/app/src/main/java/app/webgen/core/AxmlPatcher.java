package app.webgen.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binary AndroidManifest.xml (AXML) string-pool patcher.
 *
 * <p>Faithful Java port of patch_apk.py from the Web2APK spike: the string
 * pool chunk is rebuilt from scratch so replacement strings may have ANY
 * length (the pool offsets / chunk size / file size are recomputed).
 * Both UTF-8 and UTF-16 string pools are supported; pools with styles are
 * rejected, exactly like the Python prototype.
 */
public final class AxmlPatcher {

    private static final int UTF8_FLAG = 0x00000100;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_TYPE = 0x0003;

    private AxmlPatcher() {}

    // ------------------------------------------------------------------
    // length codecs (AXML string pool variable-length encoding)
    // ------------------------------------------------------------------

    private static int[] readLength8(byte[] data, int off) {
        int b0 = data[off] & 0xFF;
        if ((b0 & 0x80) != 0) {
            return new int[]{((b0 & 0x7F) << 8) | (data[off + 1] & 0xFF), off + 2};
        }
        return new int[]{b0, off + 1};
    }

    private static int[] readLength16(ByteBuffer buf, int off) {
        int w0 = buf.getShort(off) & 0xFFFF;
        if ((w0 & 0x8000) != 0) {
            int w1 = buf.getShort(off + 2) & 0xFFFF;
            return new int[]{((w0 & 0x7FFF) << 16) | w1, off + 4};
        }
        return new int[]{w0, off + 2};
    }

    private static void encodeLength8(ByteArrayOutputStream out, int value) {
        if (value > 0x7F) {
            out.write(0x80 | (value >> 8));
            out.write(value & 0xFF);
        } else {
            out.write(value);
        }
    }

    private static void encodeLength16(ByteArrayOutputStream out, int value) {
        if (value > 0x7FFF) {
            writeShort(out, 0x8000 | (value >> 16));
            writeShort(out, value & 0xFFFF);
        } else {
            writeShort(out, value);
        }
    }

    private static void writeShort(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    // ------------------------------------------------------------------
    // string pool parse / rebuild
    // ------------------------------------------------------------------

    private static final class Pool {
        List<String> strings;
        boolean utf8;
        int flags;
    }

    private static Pool parseStringPool(byte[] chunk) {
        ByteBuffer buf = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN);
        if ((buf.getShort(0) & 0xFFFF) != RES_STRING_POOL_TYPE) {
            throw new IllegalArgumentException("not a string pool chunk");
        }
        int headerSize = buf.getShort(2) & 0xFFFF;
        int stringCount = buf.getInt(8);
        int styleCount = buf.getInt(12);
        int flags = buf.getInt(16);
        int stringsStart = buf.getInt(20);
        if (styleCount != 0) {
            throw new IllegalArgumentException("string pool with styles is not supported");
        }
        Pool pool = new Pool();
        pool.utf8 = (flags & UTF8_FLAG) != 0;
        pool.flags = flags;
        pool.strings = new ArrayList<>(stringCount);
        for (int i = 0; i < stringCount; i++) {
            int rel = buf.getInt(headerSize + 4 * i);
            int off = stringsStart + rel;
            if (pool.utf8) {
                int[] r = readLength8(chunk, off); // char count (unused)
                int p = r[1];
                r = readLength8(chunk, p);         // byte count
                int blen = r[0];
                p = r[1];
                pool.strings.add(new String(chunk, p, blen, StandardCharsets.UTF_8));
            } else {
                int[] r = readLength16(buf, off);
                int clen = r[0];
                int p = r[1];
                byte[] raw = new byte[clen * 2];
                System.arraycopy(chunk, p, raw, 0, raw.length);
                pool.strings.add(new String(raw, StandardCharsets.UTF_16LE));
            }
        }
        return pool;
    }

    private static byte[] buildStringPool(List<String> strings, boolean utf8, int flags) {
        int headerSize = 28;
        int stringsStart = headerSize + 4 * strings.size();
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>(strings.size());
        for (String s : strings) {
            offsets.add(blob.size());
            if (utf8) {
                byte[] raw = s.getBytes(StandardCharsets.UTF_8);
                encodeLength8(blob, s.codePointCount(0, s.length())); // char count
                encodeLength8(blob, raw.length);                      // byte count
                blob.write(raw, 0, raw.length);
                blob.write(0);
            } else {
                byte[] raw = s.getBytes(StandardCharsets.UTF_16LE);
                encodeLength16(blob, s.length());
                blob.write(raw, 0, raw.length);
                blob.write(0);
                blob.write(0);
            }
        }
        int total = stringsStart + blob.size();
        int pad = (-total) % 4;
        if (pad < 0) pad += 4;
        total += pad;
        for (int i = 0; i < pad; i++) blob.write(0);

        ByteArrayOutputStream out = new ByteArrayOutputStream(total);
        writeShort(out, RES_STRING_POOL_TYPE);
        writeShort(out, headerSize);
        writeInt(out, total);
        writeInt(out, strings.size());
        writeInt(out, 0); // styleCount
        writeInt(out, flags);
        writeInt(out, stringsStart);
        writeInt(out, 0); // stylesStart
        for (int o : offsets) writeInt(out, o);
        byte[] b = blob.toByteArray();
        out.write(b, 0, b.length);
        byte[] result = out.toByteArray();
        if (result.length != total) {
            throw new IllegalStateException("pool size mismatch: " + result.length + " != " + total);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // public entry point
    // ------------------------------------------------------------------

    /**
     * Replace exact-match strings in the AXML string pool.
     *
     * @param axml         binary AndroidManifest.xml bytes
     * @param replacements old value -&gt; new value; every key MUST be present
     *                     in the pool, otherwise an exception is thrown
     * @return new AXML bytes
     */
    public static byte[] patch(byte[] axml, Map<String, String> replacements) {
        return patch(axml, replacements, null);
    }

    /**
     * Same as {@link #patch(byte[], Map)} plus optional replacements:
     * entries are applied only when the old value actually occurs in the
     * pool; absent keys are silently ignored. Used for the GeckoView
     * template's provider-authority strings ("&lt;package&gt;.clipboard"
     * and "&lt;package&gt;.androidx-startup"), which do not exist in the
     * WebView template's pool. Mirrors patch_apk.py's
     * optional_replacements.
     */
    public static byte[] patch(byte[] axml, Map<String, String> replacements,
                               Map<String, String> optionalReplacements) {
        ByteBuffer buf = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
        int fileType = buf.getShort(0) & 0xFFFF;
        int headerSize = buf.getShort(2) & 0xFFFF;
        if (fileType != RES_XML_TYPE) {
            throw new IllegalArgumentException("not an AXML file");
        }
        int poolOff = headerSize;
        int poolSize = buf.getInt(poolOff + 4);
        byte[] poolBytes = new byte[poolSize];
        System.arraycopy(axml, poolOff, poolBytes, 0, poolSize);
        Pool pool = parseStringPool(poolBytes);

        Map<String, Integer> hits = new LinkedHashMap<>();
        for (String k : replacements.keySet()) hits.put(k, 0);
        Map<String, String> optional = optionalReplacements != null
                ? optionalReplacements : new LinkedHashMap<>();
        List<String> newStrings = new ArrayList<>(pool.strings.size());
        for (String s : pool.strings) {
            String rep = replacements.get(s);
            if (rep != null) {
                hits.put(s, hits.get(s) + 1);
                newStrings.add(rep);
            } else if (optional.containsKey(s)) {
                newStrings.add(optional.get(s));
            } else {
                newStrings.add(s);
            }
        }
        for (Map.Entry<String, Integer> e : hits.entrySet()) {
            if (e.getValue() == 0) {
                throw new IllegalArgumentException("string not found in AXML pool: " + e.getKey());
            }
        }
        byte[] newPool = buildStringPool(newStrings, pool.utf8, pool.flags);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(axml, 0, poolOff);
        out.write(newPool, 0, newPool.length);
        out.write(axml, poolOff + poolSize, axml.length - poolOff - poolSize);
        byte[] result = out.toByteArray();
        // fix total file size in the header (offset 4, u32 LE)
        result[4] = (byte) (result.length & 0xFF);
        result[5] = (byte) ((result.length >> 8) & 0xFF);
        result[6] = (byte) ((result.length >> 16) & 0xFF);
        result[7] = (byte) ((result.length >> 24) & 0xFF);
        return result;
    }
}

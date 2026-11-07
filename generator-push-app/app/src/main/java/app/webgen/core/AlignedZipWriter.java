package app.webgen.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Minimal ZIP writer that aligns the data of STORED entries to 4-byte
 * boundaries (the in-app equivalent of `zipalign -f 4`). Alignment is
 * achieved by padding the local header's extra field with a 0xFFFF
 * extension block, the same approach zipalign uses.
 *
 * <p>Entry contents are held in memory; this is fine for WebGen APKs
 * (~1 MB) and keeps the code identical on JVM and Android.
 */
public final class AlignedZipWriter {

    public static final class Entry {
        public final String name;
        public final byte[] data;
        /** ZipEntry.STORED (0) or ZipEntry.DEFLATED (8). */
        public final int method;

        public Entry(String name, byte[] data, int method) {
            this.name = name;
            this.data = data;
            this.method = method;
        }
    }

    private AlignedZipWriter() {}

    // Fixed DOS timestamp (2024-01-01 00:00:00) for deterministic output.
    private static final int DOS_TIME = 0;
    private static final int DOS_DATE = ((2024 - 1980) << 9) | (1 << 5) | 1;
    private static final int FLAG_UTF8 = 0x0800;
    private static final int VERSION = 20;

    public static void write(OutputStream out, List<Entry> entries) throws IOException {
        List<byte[]> centralRecords = new ArrayList<>();
        long offset = 0;
        byte[] buf4 = new byte[4];
        for (Entry e : entries) {
            byte[] nameBytes = e.name.getBytes(StandardCharsets.UTF_8);
            boolean stored = e.method == 0;
            byte[] payload;
            if (stored) {
                payload = e.data;
            } else {
                payload = deflate(e.data);
            }
            CRC32 crc = new CRC32();
            crc.update(e.data);

            // --- alignment padding for STORED entries ---
            int headerNoExtra = 30 + nameBytes.length;
            int extraLen = 0;
            if (stored) {
                int rem = (int) ((offset + headerNoExtra) % 4);
                if (rem != 0) {
                    extraLen = 4 - rem;
                    if (extraLen < 4) extraLen += 4; // need room for the 4-byte field header
                }
            }
            byte[] extra = new byte[extraLen];
            if (extraLen > 0) {
                extra[0] = (byte) 0xFF;
                extra[1] = (byte) 0xFF; // field id 0xFFFF (padding)
                int sz = extraLen - 4;
                extra[2] = (byte) (sz & 0xFF);
                extra[3] = (byte) ((sz >> 8) & 0xFF);
            }

            ByteArrayOutputStream lh = new ByteArrayOutputStream();
            putInt(lh, 0x04034b50);
            putShort(lh, VERSION);
            putShort(lh, FLAG_UTF8);
            putShort(lh, e.method);
            putShort(lh, DOS_TIME);
            putShort(lh, DOS_DATE);
            putInt(lh, (int) crc.getValue());
            putInt(lh, payload.length);
            putInt(lh, e.data.length);
            putShort(lh, nameBytes.length);
            putShort(lh, extra.length);
            lh.write(nameBytes);
            lh.write(extra);
            byte[] lhBytes = lh.toByteArray();
            out.write(lhBytes);
            out.write(payload);
            long localOffset = offset;
            offset += lhBytes.length + payload.length;

            // --- central directory record (kept for later) ---
            ByteArrayOutputStream cd = new ByteArrayOutputStream();
            putInt(cd, 0x02014b50);
            putShort(cd, VERSION); // version made by
            putShort(cd, VERSION); // version needed
            putShort(cd, FLAG_UTF8);
            putShort(cd, e.method);
            putShort(cd, DOS_TIME);
            putShort(cd, DOS_DATE);
            putInt(cd, (int) crc.getValue());
            putInt(cd, payload.length);
            putInt(cd, e.data.length);
            putShort(cd, nameBytes.length);
            putShort(cd, extra.length);
            putShort(cd, 0); // comment len
            putShort(cd, 0); // disk number
            putShort(cd, 0); // internal attrs
            putInt(cd, 0);   // external attrs
            putInt(cd, (int) localOffset);
            cd.write(nameBytes);
            cd.write(extra);
            centralRecords.add(cd.toByteArray());
        }
        long cdOffset = offset;
        long cdSize = 0;
        for (byte[] rec : centralRecords) {
            out.write(rec);
            cdSize += rec.length;
        }
        ByteArrayOutputStream eocd = new ByteArrayOutputStream();
        putInt(eocd, 0x06054b50);
        putShort(eocd, 0);
        putShort(eocd, 0);
        putShort(eocd, centralRecords.size());
        putShort(eocd, centralRecords.size());
        putInt(eocd, (int) cdSize);
        putInt(eocd, (int) cdOffset);
        putShort(eocd, 0);
        byte[] e = eocd.toByteArray();
        out.write(e);
        out.flush();
    }

    private static byte[] deflate(byte[] data) {
        Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        def.setInput(data);
        def.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        while (!def.finished()) {
            int n = def.deflate(chunk);
            out.write(chunk, 0, n);
        }
        def.end();
        return out.toByteArray();
    }

    private static void putShort(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static void putInt(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }
}

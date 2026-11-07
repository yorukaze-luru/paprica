package app.webgen.generator;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Minimal FileProvider equivalent (this project intentionally has no
 * AndroidX). Serves ONLY the APK files inside the generator's "generated"
 * directory, addressed by bare file name:
 *   content://app.webgen.generator.push.files/&lt;fileName&gt;
 * The provider is not exported; access requires a URI permission grant
 * issued by this app (install / share intents).
 */
public class GenFileProvider extends ContentProvider {

    public static final String AUTHORITY = "app.webgen.generator.push.files";

    public static Uri uriFor(File file) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(file.getName()));
    }

    static File generatedDir(android.content.Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        return new File(base, "generated");
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String name = Uri.decode(uri.getLastPathSegment());
        if (name == null || name.contains("/") || name.contains("..")) {
            throw new FileNotFoundException("bad uri: " + uri);
        }
        File dir = generatedDir(getContext());
        File f = new File(dir, name);
        try {
            String canonDir = dir.getCanonicalPath();
            if (!f.getCanonicalPath().startsWith(canonDir + File.separator)) {
                throw new FileNotFoundException("outside generated dir");
            }
        } catch (java.io.IOException e) {
            throw new FileNotFoundException("path check failed");
        }
        if (!f.isFile()) throw new FileNotFoundException("not found: " + name);
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read-only provider");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String name = Uri.decode(uri.getLastPathSegment());
        if (name != null && name.endsWith(".apk")) {
            return "application/vnd.android.package-archive";
        }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            File f = resolve(uri);
            MatrixCursor cursor = new MatrixCursor(
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            cursor.addRow(new Object[]{f.getName(), f.length()});
            return cursor;
        } catch (FileNotFoundException e) {
            return new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}

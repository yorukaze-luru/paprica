package app.webgen.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Builds a stand-alone site-app APK from the bundled template APK.
 * Java port of patch_apk.py (zip entry swap + AXML patch + icon swap +
 * signature strip), with signing delegated to {@link ApkSignerHelper}
 * (apksig) instead of the apksigner CLI tool, and alignment done by
 * {@link AlignedZipWriter} instead of zipalign.
 */
public final class ApkBuilder {

    public static final String OLD_PACKAGE = "app.webgen.geckotemplate";
    public static final String OLD_LABEL = "WebGen Gecko Template";

    private ApkBuilder() {}

    public static final class Result {
        public int iconsReplaced;
        public boolean configReplaced;
        public boolean manifestPatched;
        /** Permission-name strings neutralized because the toggle was OFF. */
        public int permissionsDisabled;
    }

    /**
     * Per-site options written into assets/site_config.json and (for the
     * permission toggles) applied to the binary manifest.
     *
     * <p>Permission model (v1.2): the template declares every optional
     * permission. When a capability toggle is OFF, its permission name
     * strings in the AXML string pool are replaced with undefined dummy
     * names ({@code app.webgen.disabled.*}); Android treats unknown
     * permission names as never-granted custom permissions, i.e. the
     * generated app simply does not hold the real permission.
     */
    public static final class BuildOptions {
        public boolean microphone;
        public boolean camera;
        public boolean location;
        public boolean notifications;
        /** Template versionCode baked into the generated config. */
        public int templateVersion;
        /** Optional update feed URL for the generated app; may be null. */
        public String updateFeedUrl;
        /**
         * Push delivery model (Phase 5): "hub" (via Pアプリカ's built-in
         * distributor) or "self" (distributor embedded in the generated
         * app). Written into site_config.json as "pushMode"; null is
         * treated as "hub" (the template default when the field is
         * absent, so the schema stays backward-compatible).
         */
        public String pushMode;
    }

    // Android permission name -> dummy name used when the toggle is OFF.
    private static final String[][] MIC_PERMISSIONS = {
        {"android.permission.RECORD_AUDIO", "app.webgen.disabled.record_audio"},
        {"android.permission.MODIFY_AUDIO_SETTINGS", "app.webgen.disabled.modify_audio_settings"},
    };
    private static final String[][] CAMERA_PERMISSIONS = {
        {"android.permission.CAMERA", "app.webgen.disabled.camera"},
    };
    private static final String[][] LOCATION_PERMISSIONS = {
        {"android.permission.ACCESS_FINE_LOCATION", "app.webgen.disabled.access_fine_location"},
        {"android.permission.ACCESS_COARSE_LOCATION", "app.webgen.disabled.access_coarse_location"},
    };
    private static final String[][] NOTIFICATION_PERMISSIONS = {
        {"android.permission.POST_NOTIFICATIONS", "app.webgen.disabled.post_notifications"},
    };

    /**
     * @param templateApk bundled template APK file
     * @param unsignedOut destination for the patched-but-unsigned APK
     * @param url         target site URL (written into assets/site_config.json)
     * @param appName     new app label (any length, any script)
     * @param packageName new package name
     * @param iconByDensity map from density token ("mdpi", "hdpi", "xhdpi",
     *                      "xxhdpi", "xxxhdpi") to PNG bytes; may be null.
     *                      A single entry under key "*" is used for all densities.
     */
    public static Result buildUnsigned(File templateApk, File unsignedOut, String url,
                                       String appName, String packageName,
                                       Map<String, byte[]> iconByDensity) throws IOException {
        return buildUnsigned(templateApk, unsignedOut, url, appName, packageName,
                iconByDensity, null);
    }

    /**
     * @param options per-site options (permissions / template version /
     *                update feed); null keeps the legacy 2-field config and
     *                leaves the manifest permissions untouched.
     */
    public static Result buildUnsigned(File templateApk, File unsignedOut, String url,
                                       String appName, String packageName,
                                       Map<String, byte[]> iconByDensity,
                                       BuildOptions options) throws IOException {
        Result result = new Result();
        String configJson = buildConfigJson(url, appName, options);
        byte[] configBytes = configJson.getBytes(StandardCharsets.UTF_8);

        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put(OLD_PACKAGE, packageName);
        replacements.put(OLD_LABEL, appName);
        if (options != null) {
            result.permissionsDisabled = addPermissionReplacements(replacements, options);
        }

        // GeckoView provider-authority rules (Phase 0, mandatory for the
        // Gecko template): the GeckoView clipboard content provider and
        // the androidx.startup InitializationProvider declare their
        // authorities in the merged AAR manifest as
        // "<template package>.clipboard" / "<template package>.androidx-startup".
        // The exact-match replacement above cannot catch those (they are
        // not the bare package name), so without these rules every app
        // generated by this push edition would declare the SAME provider
        // authorities and installing a second one would fail with
        // INSTALL_FAILED_CONFLICTING_PROVIDER. These are OPTIONAL
        // replacements (ported from patch_apk.py's optional_replacements):
        // each fires only when the string exists in the pool. Class-name
        // strings (e.g. "<package>.MainActivity", "<package>.PushReceiver")
        // are deliberately NOT rewritten — the classes keep their original
        // names inside the dex.
        Map<String, String> optionalReplacements = new LinkedHashMap<>();
        optionalReplacements.put(OLD_PACKAGE + ".clipboard", packageName + ".clipboard");
        optionalReplacements.put(OLD_PACKAGE + ".androidx-startup",
                packageName + ".androidx-startup");

        List<AlignedZipWriter.Entry> outEntries = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(templateApk))) {
            ZipEntry ze;
            while ((ze = zin.getNextEntry()) != null) {
                String name = ze.getName();
                if (isSignatureEntry(name)) continue; // strip old signature
                byte[] data = readAll(zin);
                int method = ze.getMethod();
                if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
                    method = ZipEntry.DEFLATED;
                }
                if (name.equals("assets/site_config.json")) {
                    data = configBytes;
                    result.configReplaced = true;
                } else if (name.equals("AndroidManifest.xml")) {
                    data = AxmlPatcher.patch(data, replacements, optionalReplacements);
                    result.manifestPatched = true;
                } else if (iconByDensity != null && name.startsWith("res/")
                        && name.endsWith("ic_launcher.png")) {
                    byte[] icon = pickIcon(iconByDensity, name);
                    if (icon != null) {
                        data = icon;
                        result.iconsReplaced++;
                    }
                }
                outEntries.add(new AlignedZipWriter.Entry(name, data, method));
            }
        }
        if (!result.configReplaced || !result.manifestPatched) {
            throw new IOException("template APK is missing config or manifest entry");
        }
        try (FileOutputStream fos = new FileOutputStream(unsignedOut)) {
            AlignedZipWriter.write(fos, outEntries);
        }
        return result;
    }

    /** Full build: patch + sign. */
    public static Result buildAndSign(File templateApk, File signedOut, String url,
                                      String appName, String packageName,
                                      Map<String, byte[]> iconByDensity,
                                      SelfSignedCert.KeyMaterial keys) throws Exception {
        return buildAndSign(templateApk, signedOut, url, appName, packageName,
                iconByDensity, keys, null);
    }

    /** Full build: patch + sign, with per-site {@link BuildOptions}. */
    public static Result buildAndSign(File templateApk, File signedOut, String url,
                                      String appName, String packageName,
                                      Map<String, byte[]> iconByDensity,
                                      SelfSignedCert.KeyMaterial keys,
                                      BuildOptions options) throws Exception {
        File tmp = new File(signedOut.getParentFile(), signedOut.getName() + ".unsigned.tmp");
        try {
            Result r = buildUnsigned(templateApk, tmp, url, appName, packageName,
                    iconByDensity, options);
            ApkSignerHelper.sign(tmp, signedOut, keys.privateKey,
                    java.util.Collections.singletonList(keys.certificate));
            return r;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    // ------------------------------------------------------------------
    // config JSON + permission replacements
    // ------------------------------------------------------------------

    private static String buildConfigJson(String url, String appName, BuildOptions o) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"url\": ").append(jsonString(url));
        sb.append(", \"name\": ").append(jsonString(appName));
        if (o != null) {
            sb.append(", \"templateVersion\": ").append(o.templateVersion);
            sb.append(", \"notifications\": ").append(o.notifications);
            sb.append(", \"permissions\": {");
            sb.append("\"microphone\": ").append(o.microphone);
            sb.append(", \"camera\": ").append(o.camera);
            sb.append(", \"location\": ").append(o.location);
            sb.append(", \"notifications\": ").append(o.notifications);
            sb.append('}');
            sb.append(", \"pushMode\": ")
                    .append(jsonString(o.pushMode != null ? o.pushMode : "hub"));
            if (o.updateFeedUrl != null && !o.updateFeedUrl.isEmpty()) {
                sb.append(", \"updateFeedUrl\": ").append(jsonString(o.updateFeedUrl));
            }
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Adds dummy-name replacements for every capability whose toggle is
     * OFF. Returns how many permission strings were neutralized.
     */
    private static int addPermissionReplacements(Map<String, String> replacements,
                                                 BuildOptions o) {
        int count = 0;
        if (!o.microphone) count += putAll(replacements, MIC_PERMISSIONS);
        if (!o.camera) count += putAll(replacements, CAMERA_PERMISSIONS);
        if (!o.location) count += putAll(replacements, LOCATION_PERMISSIONS);
        if (!o.notifications) count += putAll(replacements, NOTIFICATION_PERMISSIONS);
        return count;
    }

    private static int putAll(Map<String, String> map, String[][] pairs) {
        for (String[] p : pairs) map.put(p[0], p[1]);
        return pairs.length;
    }

    private static byte[] pickIcon(Map<String, byte[]> iconByDensity, String entryName) {
        byte[] star = iconByDensity.get("*");
        // entryName like res/mipmap-xxhdpi-v4/ic_launcher.png
        String[] parts = entryName.split("/");
        for (String part : parts) {
            if (part.startsWith("mipmap-")) {
                String token = part.substring("mipmap-".length());
                int dash = token.indexOf("-v");
                if (dash >= 0) token = token.substring(0, dash);
                byte[] hit = iconByDensity.get(token);
                if (hit != null) return hit;
            }
        }
        return star;
    }

    static boolean isSignatureEntry(String name) {
        String u = name.toUpperCase(java.util.Locale.US);
        return u.startsWith("META-INF/") && (u.endsWith(".SF") || u.endsWith(".RSA")
                || u.endsWith(".DSA") || u.endsWith(".EC") || u.endsWith("MANIFEST.MF"));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}

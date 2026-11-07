import app.webgen.core.ApkBuilder;
import app.webgen.core.SelfSignedCert;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * JVM test harness for the PUSH-edition generator core (NOT shipped in
 * the app). Uses the exact same app.webgen.core classes that are dexed
 * into アプリカ Push, plus the apksig classes from the SDK
 * apksigner.jar — i.e. the identical code path the on-device patcher
 * runs (ApkBuilder.buildAndSign -> AxmlPatcher + AlignedZipWriter +
 * ApkSignerHelper), only the Context/Bitmap plumbing of
 * GeneratorEngine is replaced by plain file arguments.
 *
 * args: <gecko-template.apk> <icon.png> <keyDir> <out.apk>
 * Fixed sample (mirrors the template-v2 desktop sample's toggle set):
 *   URL https://tests.peter.sh/push-message-generator/,
 *   name "プッシュサンプル2", package app.webgen.sample.geckopush2,
 *   templateVersion 2, notifications ON, microphone/camera/location
 *   OFF (so the OFF-permission neutralization is exercised too),
 *   updateFeedUrl = the regular generator's generated-app feed URL.
 */
public class TestPushMain {
    public static void main(String[] args) throws Exception {
        File template = new File(args[0]);
        File icon = new File(args[1]);
        File keyDir = new File(args[2]);
        File out = new File(args[3]);

        SelfSignedCert.KeyMaterial keys = SelfSignedCert.loadOrCreate(keyDir);
        byte[] certDer = keys.certificate.getEncoded();
        String sha256 = hex(MessageDigest.getInstance("SHA-256").digest(certDer));
        System.out.println("signer cert subject: " + keys.certificate.getSubjectX500Principal());
        System.out.println("signer cert SHA-256: " + sha256);

        Map<String, byte[]> icons = new HashMap<>();
        icons.put("*", Files.readAllBytes(icon.toPath()));

        ApkBuilder.BuildOptions o = new ApkBuilder.BuildOptions();
        o.microphone = false;
        o.camera = false;
        o.location = false;
        o.notifications = true;
        o.templateVersion = 3;
        o.pushMode = args.length > 4 ? args[4] : "hub";
        o.updateFeedUrl = "https://api.github.com/repos/yorukaze-luru/paprica/releases/latest";
        String pkg = args.length > 5 ? args[5] : "app.webgen.sample.geckopush2";
        String name = args.length > 6 ? args[6] : "プッシュサンプル2";
        ApkBuilder.Result r = ApkBuilder.buildAndSign(
                template, out,
                "https://tests.peter.sh/push-message-generator/",
                name, pkg,
                icons, keys, o);
        System.out.println("sample: pushMode=" + o.pushMode + " pkg=" + pkg);
        System.out.println("patched: config=" + r.configReplaced
                + " manifest=" + r.manifestPatched + " icons=" + r.iconsReplaced
                + " permissionsDisabled=" + r.permissionsDisabled);
        System.out.println("OK -> " + out + " (" + out.length() + " bytes)");

        SelfSignedCert.KeyMaterial keys2 = SelfSignedCert.loadOrCreate(keyDir);
        String sha256b = hex(MessageDigest.getInstance("SHA-256")
                .digest(keys2.certificate.getEncoded()));
        System.out.println("key reuse check: " + sha256.equals(sha256b));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}

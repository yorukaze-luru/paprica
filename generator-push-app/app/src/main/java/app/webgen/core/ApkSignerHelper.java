package app.webgen.core;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

/**
 * Signs an APK with the apksig library (com.android.apksig), the same code
 * that powers the SDK's `apksigner` tool. In the generator app the apksig
 * classes are extracted from the SDK build-tools apksigner.jar and dexed
 * into the app (see build_generator.sh); on the JVM test harness the SDK
 * jar itself is on the classpath. Either way the executed signing code is
 * identical.
 *
 * <p>License note: apksig is part of the Android SDK (Android Open Source
 * Project), licensed under the Apache License, Version 2.0.
 */
public final class ApkSignerHelper {

    private ApkSignerHelper() {}

    public static void sign(File inputApk, File outputApk, PrivateKey privateKey,
                            List<X509Certificate> certs) throws Exception {
        if (outputApk.exists() && !outputApk.delete()) {
            throw new IllegalStateException("cannot delete existing output: " + outputApk);
        }
        ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder(
                "webgen", privateKey, certs).build();
        ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(config))
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                .setMinSdkVersion(26)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setCreatedBy("WebGen Generator")
                .build();
        signer.sign();
    }
}

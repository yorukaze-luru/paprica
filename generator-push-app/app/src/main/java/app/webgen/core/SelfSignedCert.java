package app.webgen.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Self-signed certificate generation without any external library.
 *
 * <p>The spike used BouncyCastle in its plan, but BouncyCastle's public API
 * is not directly available on Android (the platform ships an internal,
 * hidden fork) and bundling the full bcprov jar would add ~8 MB to the
 * generator APK. An X.509 certificate is just DER-encoded ASN.1, so this
 * class encodes the (fixed-shape) certificate by hand instead:
 * RSA 2048, sha256WithRSAEncryption, CN=WebGen Generator, 25-year validity,
 * BasicConstraints CA:TRUE + SubjectKeyIdentifier extensions — the same
 * shape `keytool -genkeypair` produces for self-signed keys.
 *
 * <p>Key material is persisted in the app-private directory as two files
 * (PKCS#8 private key + DER certificate), generated on first launch and
 * reused afterwards so that all generated APKs share one stable signature
 * (required for overwrite-updates of generated apps). See
 * REPORT-GENERATOR.md for the reasoning.
 */
public final class SelfSignedCert {

    public static final String KEY_FILE = "signing.key";   // PKCS#8 DER
    public static final String CERT_FILE = "signing.crt";  // X.509 DER
    private static final String CN = "WebGen Generator";

    public static final class KeyMaterial {
        public final PrivateKey privateKey;
        public final X509Certificate certificate;

        KeyMaterial(PrivateKey p, X509Certificate c) {
            privateKey = p;
            certificate = c;
        }
    }

    private SelfSignedCert() {}

    /** Load the persisted key material, generating + saving it on first use. */
    public static KeyMaterial loadOrCreate(File dir) throws Exception {
        File keyFile = new File(dir, KEY_FILE);
        File certFile = new File(dir, CERT_FILE);
        if (keyFile.isFile() && certFile.isFile()) {
            byte[] keyBytes = Files.readAllBytes(keyFile.toPath());
            PrivateKey priv = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(Files.readAllBytes(certFile.toPath())));
            return new KeyMaterial(priv, cert);
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        X509Certificate cert = createCertificate(kp);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create key dir: " + dir);
        }
        Files.write(keyFile.toPath(), kp.getPrivate().getEncoded()); // PKCS#8
        Files.write(certFile.toPath(), cert.getEncoded());
        // best effort: owner-only permissions (app sandbox already protects it)
        keyFile.setReadable(false, false);
        keyFile.setReadable(true, true);
        keyFile.setWritable(false, false);
        keyFile.setWritable(true, true);
        return new KeyMaterial(kp.getPrivate(), cert);
    }

    /** Build a self-signed X.509 certificate for the given RSA key pair. */
    public static X509Certificate createCertificate(KeyPair kp) throws Exception {
        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 86400000L);                    // yesterday
        Date notAfter = new Date(now + 25L * 365 * 86400000L);         // +25 years
        BigInteger serial = BigInteger.valueOf(now / 1000).abs()
                .add(BigInteger.valueOf(0x77656267L));                 // "webg"

        byte[] algId = seq(concat(
                oid(1, 2, 840, 113549, 1, 1, 11), derNull()));         // sha256WithRSAEncryption
        byte[] name = nameSeq(CN);
        byte[] validity = seq(concat(
                generalizedTime(notBefore), generalizedTime(notAfter)));
        byte[] spki = kp.getPublic().getEncoded();                     // already DER SEQUENCE

        // SubjectKeyIdentifier: SHA-1 over the RSAPublicKey DER (the BIT
        // STRING contents of the SPKI), as per RFC 5280 method (1).
        byte[] rsaPubDer = seq(concat(
                integer(pub.getModulus()), integer(pub.getPublicExponent())));
        byte[] ski = MessageDigest.getInstance("SHA-1").digest(rsaPubDer);
        byte[] extensions = tlv(0xA3, seq(concat(
                seq(concat(oid(2, 5, 29, 19), boolDer(true),
                        octet(seq(boolDer(true))))),                    // BasicConstraints CA:TRUE
                seq(concat(oid(2, 5, 29, 14),
                        octet(octet(ski))))                             // SubjectKeyIdentifier
        )));

        byte[] tbs = seq(concat(
                tlv(0xA0, integer(BigInteger.valueOf(2))),              // version v3
                integer(serial),
                algId,
                name,                                                   // issuer
                validity,
                name,                                                   // subject
                spki,
                extensions));

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbs);
        byte[] signature = sig.sign();

        byte[] certDer = seq(concat(tbs, algId, bitString(signature)));
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certDer));
    }

    // ------------------------------------------------------------------
    // tiny DER writer
    // ------------------------------------------------------------------

    private static byte[] nameSeq(String cn) {
        return seq(tlv(0x31, seq(concat(oid(2, 5, 4, 3),
                tlv(0x0C, cn.getBytes(StandardCharsets.UTF_8))))));     // SET { SEQ { OID CN, UTF8String } }
    }

    private static byte[] generalizedTime(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x18, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] integer(BigInteger v) {
        return tlv(0x02, v.toByteArray());
    }

    private static byte[] boolDer(boolean v) {
        return tlv(0x01, new byte[]{(byte) (v ? 0xFF : 0x00)});
    }

    private static byte[] derNull() {
        return new byte[]{0x05, 0x00};
    }

    private static byte[] octet(byte[] content) {
        return tlv(0x04, content);
    }

    private static byte[] bitString(byte[] data) {
        byte[] content = new byte[data.length + 1];
        content[0] = 0; // unused bits
        System.arraycopy(data, 0, content, 1, data.length);
        return tlv(0x03, content);
    }

    private static byte[] seq(byte[] content) {
        return tlv(0x30, content);
    }

    private static byte[] oid(int... arcs) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            int v = arcs[i];
            // base-128 big-endian
            int stack = 0;
            byte[] tmp = new byte[5];
            int n = 0;
            tmp[n++] = (byte) (v & 0x7F);
            v >>= 7;
            while (v > 0) {
                tmp[n++] = (byte) (0x80 | (v & 0x7F));
                v >>= 7;
            }
            for (int j = n - 1; j >= 0; j--) body.write(tmp[j]);
        }
        return tlv(0x06, body.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = content.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len < 0x100) {
            out.write(0x81);
            out.write(len);
        } else {
            out.write(0x82);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }
}

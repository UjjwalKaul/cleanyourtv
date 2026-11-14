package io.github.cleanyourtv.airemote.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Generates an ephemeral in-memory 2048-bit RSA key pair and self-signed X.509 v3
 * certificate with Subject Alternative Names (SAN) for localhost and the TV's LAN IP.
 *
 * Zero private keys or certificates are ever written to disk or bundled in the APK.
 * The keys reside strictly in volatile RAM for the duration of the server lifecycle.
 */
public final class SslContextFactory {

    private static final String COMMON_NAME = "CleanYourTV Web Remote";
    private static final char[] KEYSTORE_PASSWORD = "cleanyourtv".toCharArray();

    private SslContextFactory() {}

    public static SSLContext createEphemeralSslContext(String localIpAddress) throws Exception {
        // 1. Generate ephemeral 2048-bit RSA key pair in memory
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        // 2. Generate self-signed X.509 v3 certificate with SAN
        X509Certificate cert = generateSelfSignedCertificate(kp, COMMON_NAME, localIpAddress);

        // 3. Store into in-memory PKCS12 KeyStore
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("https", kp.getPrivate(), KEYSTORE_PASSWORD, new Certificate[]{cert});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, KEYSTORE_PASSWORD);

        // 4. Initialize TLS SSLContext
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, new SecureRandom());
        return sslContext;
    }

    private static X509Certificate generateSelfSignedCertificate(KeyPair kp, String commonName, String ipAddress) throws Exception {
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 24 * 3600 * 1000L); // Valid 1 day in past for clock skew
        Date notAfter = new Date(now + 10L * 365 * 24 * 3600 * 1000L); // 10 years

        BigInteger serial = BigInteger.valueOf(now);

        byte[] version = DerWriter.wrap(0xA0, DerWriter.integer(BigInteger.valueOf(2))); // v3
        byte[] serialDer = DerWriter.integer(serial);
        byte[] sigAlg = DerWriter.sha256RsaAlgorithmId();
        byte[] issuer = DerWriter.name(commonName);
        byte[] validity = DerWriter.sequence(DerWriter.utcTime(notBefore), DerWriter.utcTime(notAfter));
        byte[] subject = issuer;
        byte[] spki = kp.getPublic().getEncoded();

        // Subject Alternative Names (SAN) extension (OID 2.5.29.17)
        ByteArrayOutputStream sanOut = new ByteArrayOutputStream();
        sanOut.write(DerWriter.wrap(0x82, "localhost".getBytes(StandardCharsets.US_ASCII))); // DNS: localhost
        sanOut.write(DerWriter.wrap(0x87, new byte[]{127, 0, 0, 1})); // IP: 127.0.0.1
        if (ipAddress != null && ipAddress.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            String[] octets = ipAddress.split("\\.");
            byte[] ipBytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                ipBytes[i] = (byte) Integer.parseInt(octets[i]);
            }
            sanOut.write(DerWriter.wrap(0x87, ipBytes)); // IP: LAN IP
        }
        byte[] sanSeq = DerWriter.sequence(sanOut.toByteArray());
        byte[] sanExt = DerWriter.sequence(
                new byte[]{0x06, 0x03, 0x55, 0x1D, 0x11}, // OID 2.5.29.17
                DerWriter.wrap(0x04, sanSeq) // OCTET STRING
        );
        byte[] extensions = DerWriter.wrap(0xA3, DerWriter.sequence(sanExt));

        byte[] tbs = DerWriter.sequence(version, serialDer, sigAlg, issuer, validity, subject, spki, extensions);

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbs);
        byte[] sigBytes = sig.sign();
        byte[] sigBitString = DerWriter.bitString(sigBytes);

        byte[] certDer = DerWriter.sequence(tbs, sigAlg, sigBitString);

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certDer));
    }

    // Lightweight ASN.1 DER Builder
    private static final class DerWriter {

        static byte[] sequence(byte[]... contents) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] c : contents) {
                out.write(c);
            }
            return wrap(0x30, out.toByteArray());
        }

        static byte[] set(byte[]... contents) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] c : contents) {
                out.write(c);
            }
            return wrap(0x31, out.toByteArray());
        }

        static byte[] wrap(int tag, byte[] content) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(tag);
            int len = content.length;
            if (len < 128) {
                out.write(len);
            } else if (len < 256) {
                out.write(0x81);
                out.write(len);
            } else if (len < 65536) {
                out.write(0x82);
                out.write((len >> 8) & 0xFF);
                out.write(len & 0xFF);
            } else {
                out.write(0x83);
                out.write((len >> 16) & 0xFF);
                out.write((len >> 8) & 0xFF);
                out.write(len & 0xFF);
            }
            out.write(content);
            return out.toByteArray();
        }

        static byte[] integer(BigInteger val) throws IOException {
            return wrap(0x02, val.toByteArray());
        }

        static byte[] bitString(byte[] content) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0x00); // 0 unused bits
            out.write(content);
            return wrap(0x03, out.toByteArray());
        }

        static byte[] utf8String(String str) throws IOException {
            return wrap(0x0C, str.getBytes(StandardCharsets.UTF_8));
        }

        static byte[] utcTime(Date date) throws IOException {
            SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            return wrap(0x17, sdf.format(date).getBytes(StandardCharsets.US_ASCII));
        }

        static byte[] sha256RsaAlgorithmId() throws IOException {
            // OID 1.2.840.113549.1.1.11 with NULL parameters
            byte[] oidBytes = new byte[]{0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B};
            return sequence(oidBytes, new byte[]{0x05, 0x00});
        }

        static byte[] name(String cn) throws IOException {
            // AttributeTypeAndValue: OID 2.5.4.3 (commonName) -> UTF8String
            byte[] oidCn = new byte[]{0x06, 0x03, 0x55, 0x04, 0x03};
            byte[] attr = sequence(oidCn, utf8String(cn));
            return sequence(set(attr));
        }
    }
}

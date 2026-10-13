package de.aichele.childlauncher;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Optional dashboard TLS. Material is created or loaded only when HTTPS is enabled.
 * Modes: self-signed (keytool PKCS12), keystore (existing PKCS12/JKS), pem (cert + PKCS#8 or RSA PKCS#1).
 */
final class HttpsSupport {
    record Spec(String mode, String keystore, String keystorePassword, String keyPassword,
                String cert, String key, int days, String dn, List<String> sans) {}

    private HttpsSupport() {}

    static void configure(HttpsServer server, SSLContext ctx) {
        server.setHttpsConfigurator(new HttpsConfigurator(ctx) {
            @Override public void configure(HttpsParameters params) {
                var engine = ctx.createSSLEngine();
                params.setNeedClientAuth(false);
                params.setCipherSuites(engine.getEnabledCipherSuites());
                params.setProtocols(engine.getEnabledProtocols());
                var ssl = ctx.getDefaultSSLParameters();
                ssl.setNeedClientAuth(false);
                params.setSSLParameters(ssl);
            }
        });
    }

    static SSLContext build(Path dataDir, Spec spec, Consumer<String> log) throws Exception {
        String mode = spec.mode == null || spec.mode.isBlank() ? "self-signed" : spec.mode.toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "self-signed" -> fromKeystore(ensureSelfSigned(dataDir, spec, log), password(dataDir, spec));
            case "keystore" -> fromKeystore(resolve(dataDir, spec.keystore, "dashboard.p12"), password(dataDir, spec));
            case "pem" -> fromPem(resolve(dataDir, spec.cert, "dashboard.crt"), resolve(dataDir, spec.key, "dashboard.key"), password(dataDir, spec)[0]);
            default -> throw new IllegalArgumentException("dashboard.https.mode: " + mode);
        };
    }

    private static char[][] password(Path dataDir, Spec spec) throws IOException {
        String store = spec.keystorePassword == null ? "" : spec.keystorePassword;
        String key = spec.keyPassword == null ? "" : spec.keyPassword;
        if (store.isBlank()) store = readOrCreatePass(dataDir.resolve("dashboard-keystore.pass"));
        if (key.isBlank()) key = store;
        return new char[][] { store.toCharArray(), key.toCharArray() };
    }

    private static String readOrCreatePass(Path file) throws IOException {
        if (Files.exists(file)) {
            String p = Files.readString(file).trim();
            if (!p.isEmpty()) return p;
        }
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 24; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb + "\n");
        try { Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw----")); } catch (Exception ignored) {}
        return sb.toString();
    }

    private static Path ensureSelfSigned(Path dataDir, Spec spec, Consumer<String> log) throws Exception {
        Path ks = resolve(dataDir, spec.keystore, "dashboard.p12");
        if (Files.exists(ks)) return ks;
        Files.createDirectories(ks.getParent() == null ? dataDir : ks.getParent());
        char[][] pw = password(dataDir, spec);
        String dn = spec.dn == null || spec.dn.isBlank() ? "CN=FabricLauncher" : spec.dn.trim();
        if (dn.indexOf('\n') >= 0 || dn.indexOf('\r') >= 0 || dn.startsWith("-")) throw new IllegalArgumentException("dashboard.https.dn");
        int days = spec.days <= 0 ? 825 : Math.min(spec.days, 3650);
        List<String> sans = normalizeSans(spec.sans);
        Path keytool = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "keytool.exe" : "keytool");
        if (!Files.isExecutable(keytool)) throw new IOException("keytool missing: " + keytool);
        List<String> cmd = new ArrayList<>(List.of(
                keytool.toString(), "-genkeypair", "-alias", "dashboard", "-keyalg", "RSA", "-keysize", "2048",
                "-sigalg", "SHA256withRSA", "-validity", Integer.toString(days), "-storetype", "PKCS12",
                "-keystore", ks.toString(), "-storepass", new String(pw[0]), "-keypass", new String(pw[1]),
                "-dname", dn, "-ext", "SAN=" + String.join(",", sans), "-noprompt"));
        log.accept("Creating self-signed dashboard certificate " + ks);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("keytool timeout"); }
        if (p.exitValue() != 0 || !Files.exists(ks)) {
            Files.deleteIfExists(ks);
            throw new IOException("keytool exit " + p.exitValue() + ": " + new String(out, StandardCharsets.UTF_8));
        }
        try { Files.setPosixFilePermissions(ks, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----")); } catch (Exception ignored) {}
        return ks;
    }

    static List<String> normalizeSans(List<String> sans) {
        List<String> out = new ArrayList<>();
        if (sans != null) for (String s : sans) {
            if (s == null || s.isBlank()) continue;
            String v = s.trim();
            if (v.startsWith("dns:") || v.startsWith("ip:") || v.startsWith("DNS:") || v.startsWith("IP:")) out.add(v.toLowerCase(Locale.ROOT).startsWith("dns:") ? "dns:" + v.substring(4) : "ip:" + v.substring(3));
            else if (v.chars().allMatch(c -> Character.isDigit(c) || c == '.' || c == ':')) out.add("ip:" + v);
            else out.add("dns:" + v);
        }
        if (out.isEmpty()) { out.add("dns:localhost"); out.add("ip:127.0.0.1"); }
        return out;
    }

    private static SSLContext fromKeystore(Path ks, char[][] pw) throws Exception {
        if (!Files.isRegularFile(ks)) throw new IOException("Keystore not found: " + ks);
        String type = ks.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jks") ? "JKS" : "PKCS12";
        KeyStore store = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(ks)) { store.load(in, pw[0]); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, pw[1]);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    static SSLContext fromPem(Path certFile, Path keyFile, char[] keyPass) throws Exception {
        if (!Files.isRegularFile(certFile) || !Files.isRegularFile(keyFile)) throw new IOException("PEM cert or key missing");
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certs;
        try (InputStream in = Files.newInputStream(certFile)) { certs = cf.generateCertificates(in); }
        if (certs.isEmpty()) throw new IOException("No certificate in " + certFile);
        PrivateKey key = loadKey(Files.readString(keyFile));
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("dashboard", key, keyPass, certs.toArray(Certificate[]::new));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, keyPass);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    static PrivateKey loadKey(String pem) throws Exception {
        String rsa = pemBlock(pem, "BEGIN RSA PRIVATE KEY");
        String pkcs8 = pemBlock(pem, "BEGIN PRIVATE KEY");
        if (rsa == null && pkcs8 == null) throw new IllegalArgumentException("Expected PKCS#8 (BEGIN PRIVATE KEY) or RSA PKCS#1");
        byte[] der = Base64.getDecoder().decode(rsa != null ? rsa : pkcs8);
        if (rsa != null) der = wrapPkcs1(der);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static String pemBlock(String pem, String marker) {
        int s = pem.indexOf(marker);
        if (s < 0) return null;
        int line = pem.indexOf('\n', s);
        int e = pem.indexOf("-----END", line < 0 ? s : line);
        if (line < 0 || e < 0) return null;
        String body = pem.substring(line, e).replaceAll("[^A-Za-z0-9+/=]", "");
        return body.isEmpty() ? null : body;
    }

    /** PKCS#8 wrapper around an RSA PKCS#1 private key. */
    static byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] version = new byte[] { 0x02, 0x01, 0x00 };
        byte[] alg = new byte[] { 0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00 };
        byte[] octet = concat(new byte[] { 0x04 }, derLen(pkcs1.length), pkcs1);
        byte[] body = concat(version, alg, octet);
        return concat(new byte[] { 0x30 }, derLen(body.length), body);
    }

    private static byte[] derLen(int len) {
        if (len < 128) return new byte[] { (byte) len };
        byte[] raw = new byte[4];
        int n = 0;
        int v = len;
        while (v > 0) { raw[3 - n] = (byte) (v & 0xff); v >>= 8; n++; }
        byte[] out = new byte[n + 1];
        out[0] = (byte) (0x80 | n);
        System.arraycopy(raw, 4 - n, out, 1, n);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }

    private static Path resolve(Path base, String configured, String fallback) {
        String c = configured == null || configured.isBlank() ? fallback : configured;
        Path p = Path.of(c);
        return p.isAbsolute() ? p : base.resolve(p);
    }
}

package de.aichele.childlauncher;

import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoreAdminTest {
    @TempDir Path tmp;

    @Test void backoffCapsAndDoesNotOverflow() {
        assertEquals(5, RestartBackoff.delaySeconds(5, 300, 0));
        assertEquals(10, RestartBackoff.delaySeconds(5, 300, 1));
        assertEquals(300, RestartBackoff.delaySeconds(5, 300, 10));
        assertEquals(300, RestartBackoff.delaySeconds(Long.MAX_VALUE, 300, 4));
        assertEquals(0, RestartBackoff.delaySeconds(-1, 10, 3));
    }

    @Test void tickQueryHidesOnlyTheReply() {
        TickQueryFilter f = new TickQueryFilter();
        long now = 10_000;
        assertFalse(f.consume("[12:00:00] [Server thread/INFO]: Alice: tick rate is bad", now, now));
        assertTrue(f.consume("[12:00:00] [Server thread/INFO]: Target tick rate: 20,0 per second", 9_000, now));
        assertEquals(20, f.targetRate, 0.001);
        assertTrue(f.consume("[12:00:00] [Server thread/INFO]: Average time per tick: 12,5 ms", 9_000, now));
        assertEquals(12.5, f.mspt, 0.001);
        assertEquals(20, f.tps, 0.001);
        assertTrue(f.consume("[12:00:00] [Server thread/INFO]: The game is running normally.", 9_000, now));
        assertTrue(f.consume("[12:00:00] [Server thread/INFO]: Percentiles: P50: 10.0 ms", 9_000, now));
        assertFalse(f.consume("[12:00:00] [Server thread/INFO]: Average time per tick: 12.5 ms", 1_000, now));
    }

    @Test void propertiesKeepCommentsAndForeignKeys() throws Exception {
        Path f = tmp.resolve("server.properties");
        Files.writeString(f, "# head\nmotd=old\nview-distance=8\n");
        PropertiesFile.update(f, Map.of("motd", "neu", "server-port", "2063"), Map.of("level-name", "world", "motd", "nope"));
        String text = Files.readString(f);
        assertTrue(text.startsWith("# head"));
        assertTrue(text.contains("motd=neu"));
        assertTrue(text.contains("view-distance=8"));
        assertTrue(text.contains("server-port=2063"));
        assertTrue(text.contains("level-name=world"));
        assertFalse(text.contains("motd=nope"));
    }

    @Test void pathGuardRejectsEscapeAndSymlink() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("child"));
        PathGuard g = PathGuard.open(root);
        Path inside = g.resolve("mods/a.jar");
        assertTrue(inside.startsWith(g.root()));
        assertThrows(SecurityException.class, () -> g.resolve("../secret"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.createSymbolicLink(root.resolve("link"), outside);
        assertThrows(SecurityException.class, () -> g.resolve("link/x"));
    }

    @Test void logQueryFiltersFromTheEnd() throws Exception {
        Path log = tmp.resolve("latest.log");
        Files.writeString(log, "INFO start\nWARN disk\nERROR boom\nException x\nINFO end\n");
        LogQuery.Result err = LogQuery.query(log, "error", "", 10, 1024 * 1024);
        assertEquals(List.of("ERROR boom", "Exception x"), err.lines());
        assertFalse(err.truncated());
        LogQuery.Result tail = LogQuery.query(log, "all", "end", 10, 1024 * 1024);
        assertEquals(List.of("INFO end"), tail.lines());
        byte[] big = new byte[9000];
        java.util.Arrays.fill(big, (byte) 'a');
        Files.write(tmp.resolve("big.log"), big);
        LogQuery.Result cut = LogQuery.query(tmp.resolve("big.log"), "all", "", 5, 4096);
        assertTrue(cut.truncated());
    }

    @Test void modToggleAndCdnGuard() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("srv"));
        Files.createDirectories(root.resolve("mods"));
        Files.writeString(root.resolve("mods/lithium.jar"), "x");
        Files.writeString(root.resolve("mods/.fl-lithium-lithium.jar.marker"), "1");
        PathGuard g = PathGuard.open(root);
        List<ModAdmin.Entry> list = ModAdmin.list(root);
        assertEquals(1, list.size());
        assertTrue(list.get(0).enabled());
        String next = ModAdmin.toggle(g, "mods/lithium.jar", "lithium.jar");
        assertEquals("mods/lithium.jar.disabled", next);
        assertFalse(ModAdmin.list(root).get(0).enabled());
        assertEquals("mods/lithium.jar", ModAdmin.toggle(g, next, "lithium.jar.disabled"));
        assertThrows(IllegalArgumentException.class, () -> ModAdmin.toggle(g, "mods/lithium.jar", "nope"));
        Files.createDirectories(root.resolve("other"));
        Files.writeString(root.resolve("other/lithium.jar"), "x");
        assertThrows(SecurityException.class, () -> ModAdmin.toggle(g, "other/lithium.jar", "lithium.jar"));
        assertDoesNotThrow(() -> ModAdmin.requireModrinthCdn("https://cdn.modrinth.com/data/x/versions/y/a.jar"));
        assertThrows(IllegalArgumentException.class, () -> ModAdmin.requireModrinthCdn("http://cdn.modrinth.com/a.jar"));
        assertThrows(IllegalArgumentException.class, () -> ModAdmin.requireModrinthCdn("https://evil.example/a.jar"));
    }

    @Test void checksumMismatch() throws Exception {
        Path f = tmp.resolve("blob");
        Files.writeString(f, "abc");
        String good = Upstream.digest(f, "SHA-256");
        Upstream.checkHash(f, "SHA-256", good);
        assertThrows(java.io.IOException.class, () -> Upstream.checkHash(f, "SHA-256", "00"));
        Upstream.checkHash(f, null, null);
    }

    @Test void selfSignedHandshakeAndPem() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("tls"));
        HttpsSupport.Spec spec = new HttpsSupport.Spec("self-signed", "dashboard.p12", "testpass", "testpass", "dashboard.crt", "dashboard.key", 30, "CN=FabricLauncher", List.of("dns:localhost", "ip:127.0.0.1"));
        SSLContext serverCtx = HttpsSupport.build(dir, spec, m -> {});
        assertTrue(Files.isRegularFile(dir.resolve("dashboard.p12")));
        HttpsServer hs = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpsSupport.configure(hs, serverCtx);
        hs.createContext("/", ex -> {
            byte[] b = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        hs.start();
        try {
            HttpResponse<String> res = client(dir.resolve("dashboard.p12"), "testpass".toCharArray())
                    .send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + hs.getAddress().getPort() + "/")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, res.statusCode());
            assertEquals("ok", res.body());
        } finally { hs.stop(0); }

        Process cert = new ProcessBuilder("openssl", "pkcs12", "-in", dir.resolve("dashboard.p12").toString(), "-clcerts", "-nokeys", "-out", dir.resolve("cert.pem").toString(), "-passin", "pass:testpass").start();
        assertEquals(0, cert.waitFor());
        Process key = new ProcessBuilder("openssl", "pkcs12", "-in", dir.resolve("dashboard.p12").toString(), "-nocerts", "-nodes", "-out", dir.resolve("key.pem").toString(), "-passin", "pass:testpass").start();
        assertEquals(0, key.waitFor());
        SSLContext pem = HttpsSupport.fromPem(dir.resolve("cert.pem"), dir.resolve("key.pem"), "pempass".toCharArray());
        assertNotNull(pem);
        Process pkcs1 = new ProcessBuilder("openssl", "rsa", "-in", dir.resolve("key.pem").toString(), "-traditional", "-out", dir.resolve("key1.pem").toString()).start();
        assertEquals(0, pkcs1.waitFor());
        assertNotNull(HttpsSupport.loadKey(Files.readString(dir.resolve("key1.pem"))));
        assertEquals(List.of("dns:localhost", "ip:127.0.0.1"), HttpsSupport.normalizeSans(List.of()));
        assertEquals(List.of("dns:example.com", "ip:10.0.0.2"), HttpsSupport.normalizeSans(List.of("example.com", "10.0.0.2")));
    }

    @Test void probeAndIndexContract() throws Exception {
        long pid = ProcessHandle.current().pid();
        Map<String, Object> snap = ProcessProbe.snapshot(pid);
        assertTrue(((Number) snap.get("rssMb")).longValue() > 0);
        assertTrue(((Number) snap.get("threads")).intValue() > 0);
        String flags = ProcessProbe.jcmd(pid, "VM.flags");
        assertFalse(flags.isBlank());
        assertThrows(IllegalArgumentException.class, () -> ProcessProbe.jcmd(pid, "GC.run"));
        Path dir = Files.createDirectories(tmp.resolve("disk"));
        Files.writeString(dir.resolve("a.txt"), "12345");
        ProcessProbe.Disk disk = ProcessProbe.disk(dir, 100);
        assertEquals(1, disk.files());
        assertEquals(5, disk.bytes());
        String html = Files.readString(Path.of("src/main/resources/web/index.html"));
        assertTrue(html.contains("id=\"props\""));
        assertTrue(html.contains("id=\"config\""));
        assertTrue(html.contains("<textarea"));
        assertTrue(html.contains("['mods','Mods']"));
        assertTrue(html.contains("['diag','Diagnose']"));
        assertFalse(html.toLowerCase().contains("wizard"));
    }

    private static HttpClient client(Path keystore, char[] pass) throws Exception {
        KeyStore ts = KeyStore.getInstance("PKCS12");
        try (java.io.InputStream in = Files.newInputStream(keystore)) { ts.load(in, pass); }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(ctx).build();
    }
}

package de.aichele.childlauncher;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Consumer;

/** On-demand HTTPS fetches. Nothing is downloaded until a caller asks for that URL. */
final class Upstream {
    private final HttpClient http;
    private final String userAgent;
    private final Consumer<String> log;

    Upstream(HttpClient http, String userAgent, Consumer<String> log) {
        this.http = http;
        this.userAgent = userAgent;
        this.log = log;
    }

    String getText(String url) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", userAgent).timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode() + " for " + url);
        return r.body();
    }

    JsonElement getJson(String url) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", userAgent).timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode() + " for " + url);
        return JsonParser.parseString(r.body());
    }

    void download(String url, Path target) throws Exception { download(url, target, null, null); }

    void download(String url, Path target, String algo, String expectedHex) throws Exception {
        log.accept("Downloading " + url);
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", userAgent).timeout(Duration.ofMinutes(10)).GET().build(), HttpResponse.BodyHandlers.ofFile(tmp));
        if (r.statusCode() / 100 != 2) { Files.deleteIfExists(tmp); throw new IOException("HTTP " + r.statusCode() + " for " + url); }
        try { checkHash(tmp, algo, expectedHex); }
        catch (IOException e) { Files.deleteIfExists(tmp); throw e; }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    static void checkHash(Path file, String algo, String expectedHex) throws Exception {
        if (algo == null || expectedHex == null || expectedHex.isBlank()) return;
        String got = digest(file, algo);
        if (!expectedHex.equalsIgnoreCase(got)) throw new IOException("checksum mismatch (" + algo + ") for " + file.getFileName());
    }

    static String digest(Path file, String algo) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algo);
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }
}

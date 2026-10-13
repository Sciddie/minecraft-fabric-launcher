package de.aichele.childlauncher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Resolves a user path inside a root and rejects traversal and symlink escapes. */
final class PathGuard {
    private final Path root;

    private PathGuard(Path root) { this.root = root; }

    static PathGuard open(Path workDir) throws IOException {
        Files.createDirectories(workDir);
        return new PathGuard(workDir.toRealPath());
    }

    Path root() { return root; }

    Path resolve(String rel) throws IOException {
        if (rel == null) rel = "";
        rel = rel.replace('\\', '/');
        while (rel.startsWith("/")) rel = rel.substring(1);
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root)) throw new SecurityException("Pfad außerhalb des Server-Ordners");
        Path check = p;
        while (check != null && !Files.exists(check, LinkOption.NOFOLLOW_LINKS)) check = check.getParent();
        if (check == null || !check.toRealPath().startsWith(root)) throw new SecurityException("Pfad außerhalb des Server-Ordners");
        return p;
    }

    String relativize(Path p) { return root.relativize(p).toString().replace('\\', '/'); }
}

package de.aichele.childlauncher;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Ring buffer of recent console lines plus live listeners (for SSE). */
final class ConsoleBuffer {
    private final int cap;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    ConsoleBuffer(int cap) { this.cap = cap; }

    void add(String line) {
        synchronized (lines) {
            lines.addLast(line); while (lines.size() > cap) lines.removeFirst();
            for (Consumer<String> l : listeners) { try { l.accept(line); } catch (Exception ignored) {} }
        }
    }

    List<String> snapshot() { synchronized (lines) { return new ArrayList<>(lines); } }

    /** Atomically take snapshot and register listener so no line is lost or duplicated. */
    List<String> subscribe(Consumer<String> l) { synchronized (lines) { listeners.add(l); return new ArrayList<>(lines); } }
    void unsubscribe(Consumer<String> l) { listeners.remove(l); }
}

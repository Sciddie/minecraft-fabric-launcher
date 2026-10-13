# FabricLauncher

Spigot-Plugin (`de.aichele.childlauncher`), das einen äußeren Spigot-Server als Wirt nutzt: Es startet und überwacht einen Fabric-Child-Server und stellt ein Web-Dashboard bereit. Minimale Spigot-Konfiguration für den äußeren Server liegt unter `spigot-minimal-config/`.

## Server einrichten

Schritt für Schritt auf einem neuen Spigot-Server (nur Spigot beim Hoster, FTP, ein freier Zusatzport für Fabric und einer für das Dashboard): **[docs/SETUP.md](docs/SETUP.md)**.

## Bauen

Voraussetzung: **Java 25** (Minecraft 26.x) und Maven.

```bash
mvn package
```

Das Plugin-Jar liegt danach unter `target/FabricLauncher-1.2.0-pre.2.jar`.

## Dokumentation

Ausführliche Beschreibung: [docs/Dokumentation.md](docs/Dokumentation.md) (HTML-Fassung: [docs/Dokumentation.html](docs/Dokumentation.html)).

## WorldPorter

Der Unterordner `worldporter/` enthält das frühere Plugin **WorldPorter**. Es hat Spigot-Welten einmalig ins Vanilla-Layout verschoben und ist **außer Dienst**. Nicht erneut installieren.

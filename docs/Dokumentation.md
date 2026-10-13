# FabricLauncher – Dokumentation

*Stand: 13.10.2026 · Plugin-Version 1.4.0 · Spigot 26.3 / Java 25 · Zap-Hosting-Server `g266459`*

**Neu aufsetzen:** Schritt für Schritt auf einem neuen Spigot-Server steht in [SETUP.md](SETUP.md).

## 1. Zweck und Architektur

Zap-Hosting bietet für dieses Paket nur Spigot an. **FabricLauncher** ist ein Spigot-Plugin, das daraus einen „Wirt“ macht:

- Der **äußere Spigot-Server** (Port 2060) ist eine leere, minimierte Lobby (`lobby_empty`, Void-Flachwelt, Whitelist an und leer). Er existiert nur, damit Zap etwas starten kann.
- FabricLauncher startet beim Enable als **eigenen Prozess (Child)** einen **Fabric-Server** im Ordner `child-server/` auf **Port 2063**. Dort wird tatsächlich gespielt (Welt `child-server/world`).
- Ausgabe des Child landet mit Präfix `[Child]` in der Spigot-Konsole/-Log; Befehle gehen per stdin an den Child.
- Ein eingebautes **Web-Dashboard** (Port 2064) zeigt Status, Ressourcen, Konsole, Spieler, Dateien, Logs und Konfiguration.
- **OuterTuner** drosselt den äußeren Spigot auf ein Minimum an CPU/RAM.

```
Zap-Container (4 Kerne)
└─ Spigot-JVM (Lobby, ~0,5 GB RSS)            Port 2060 (Spiel), 2061 (Query), 2062 (RCON)
   └─ FabricLauncher-Plugin
      ├─ Dashboard (HTTP)                       Port 2064
      └─ Child-Prozess: java -Xmx8G -jar fabric-server-launch-26.3.jar   Port 2063
```

## 2. Ports

| Port | Dienst | Hinweis |
|---|---|---|
| 2060 | Spigot (Lobby) | Whitelist an, niemand soll hier joinen |
| 2061 | Query des Spigot | für Zap-Statusanzeige, aktiv gelassen |
| 2062 | RCON des Spigot | Passwort in `server.properties` / `deploy\rcon.txt` auf FlosPC |
| **2063** | **Fabric-Child – hier spielen** | `<SERVER-IP>:2063` |
| 2064 | Web-Dashboard | `http://<SERVER-IP>:2064/` |

## 3. Installation

Die ausführliche Ersteinrichtung (Voraussetzungen, FTP, erster Start, `config.yml`, Weltumzug, Dashboard) steht in [SETUP.md](SETUP.md). Kurzfassung:

1. `FabricLauncher-1.4.0.jar` nach `plugins/` hochladen (FTP).
2. Spigot starten. Beim ersten Start:
   - `plugins/FabricLauncher/config.yml` wird erzeugt,
   - Dashboard-Passwort wird erzeugt (`plugins/FabricLauncher/dashboard-password.txt`, einmalig im Log),
   - Fabric-Server-Jar und Mods (Modrinth) werden nach `child-server/` geladen, `eula.txt` wird gesetzt.
3. Sind beim ersten Anlegen von `child-server/` im Spigot-Root `ops.json`, `whitelist.json`, `banned-*.json`, `usercache.json` vorhanden, werden sie kopiert.

## 4. config.yml (Schlüssel und Standardwerte)

| Schlüssel | Standard | Bedeutung |
|---|---|---|
| `type` | `fabric` | `fabric` \| `paper` \| `vanilla` \| `custom` |
| `mcVersion` | `"26.3"` | Minecraft-Version des Child |
| `port` | `2063` | Port des Child (wird in dessen server.properties erzwungen) |
| `serverIp` | `"<SERVER-IP>"` | Bind-Adresse des Child (`""` = alle) |
| `customJar` | `"server.jar"` | nur bei `type: custom` |
| `fabricLoaderVersion` / `fabricInstallerVersion` | `latest` | neueste stabile Version |
| `workingDir` | `./child-server` | Ordner des Child |
| `memory.xms` / `memory.xmx` | `8G` / `8G` | Heap des Child |
| `jvmFlags` | Aikar-Flags (G1) | zusätzliche JVM-Flags; `G1RSetUpdatingPauseIntervalMillis` wird automatisch ausgefiltert (Java 25) |
| `serverArgs` | `[]` | Argumente nach `nogui` |
| `autoStart` | `true` | Child beim Plugin-Start starten |
| `autoRestart.enabled` | `true` | Child nach Absturz neu starten |
| `autoRestart.initialDelaySeconds` | `5` | erste Wartezeit, verdoppelt sich je Versuch |
| `autoRestart.maxDelaySeconds` | `300` | Obergrenze der Wartezeit |
| `autoRestart.resetAfterSeconds` | `600` | nach so langer Laufzeit wird der Zähler zurückgesetzt |
| `autoRestart.maxAttempts` | `0` | 0 = unbegrenzt |
| `stopTimeoutSeconds` | `60` | Wartezeit auf sauberes Stoppen, danach Kill |
| `shutdownHookTimeoutSeconds` | `25` | dito beim JVM-Shutdown (SIGTERM/Zap) |
| `saveAllIntervalMinutes` | `5` | periodisches `save-all` (0 = aus) |
| `logPrefix` | `"[Child] "` | Präfix im Spigot-Log |
| `mods.lithium` / `mods.ferrite-core` / `mods.c2me-fabric` | `true` / `true` / `false` | Modrinth-Slugs, automatisch geladen |
| `serverProperties.*` | siehe unten | werden bei **jedem** Start in die Child-`server.properties` geschrieben |
| `dashboard.enabled` | `true` | Dashboard an/aus |
| `dashboard.port` | `2064` | Port |
| `dashboard.bind` | `""` | `""` = `serverIp`, `0.0.0.0` = alle |
| `neuterOuter` | `true` | OuterTuner aktiv (siehe Abschnitt 7) |

Standardwerte unter `serverProperties`: `white-list: false`, `enforce-whitelist: false`, `view-distance: 12`, `simulation-distance: 4`, `motd`, `difficulty: normal`, `allow-flight: true`, `spawn-protection: 0`, `max-players: 30`, `pause-when-empty-seconds: 0`, `enable-query: false`, `enable-rcon: false`, `sync-chunk-writes: false`, `network-compression-threshold: 512`.

> **Wichtig:** Im Child müssen `white-list=false` und `pause-when-empty-seconds=0` bleiben.

## 5. Befehle (`/child`, Permission `fabriclauncher.admin`, Standard: OP/Konsole/RCON)

| Befehl | Wirkung |
|---|---|
| `/child start` | Child starten |
| `/child stop` | Child sauber stoppen (bis `stopTimeoutSeconds`) |
| `/child restart` | Stoppen, config neu laden, starten |
| `/child status` | Läuft? PID, Uptime, Typ, Port, Ordner |
| `/child cmd <befehl>` | Befehl an die Child-Konsole, z. B. `/child cmd whitelist add Name` |
| `/child config set <pfad> <wert>` | Wert in config.yml setzen (`true/false/Zahl/Text`, Listen: `[list]a,b`) |
| `/child prop set <key> <wert>` | Wert in der Child-server.properties setzen |
| `/child mods` | Jars unter `mods/` und `plugins/` |
| `/child mod install <slug> [version]` | Modrinth-Download, erst auf diesen Befehl |
| `/child mod toggle <pfad>` | `.jar` ↔ `.jar.disabled` |
| `/child diag` | Prozess- und Plattenwerte |
| `/child threaddump` / `heap` / `flags` | lesende `jcmd`-Ausgabe |
| `/child servers` | Profile und Laufzustand |
| `/child focus <id>` | Fokusprofil, schreibt `focusServer` |
| `/child tasks` / `task run <id>` | Cron-Aufgaben |
| `/child backup` | Zip des Fokusprofils, optional JDBC-Metadaten |
| `/child backup list` | Zips des Fokusprofils |
| `/child backup delete <datei>` | Zip löschen |
| `/child backup restore <datei>` | Zurückspielen, nur bei gestopptem Child |
| `/child versions <typ>` | Katalog `fabric`, `paper`, `vanilla` oder `spigot`. Es wird nichts geladen. |
| `/child plan` | JVM-argv. Lädt nichts und startet nichts. |
| `/child jar` | Ob das Jar aus dem argv lokal liegt: Name, Bytes, Pfad. Symlinks werden nicht gelesen. |
| `/child crashes` | `crash-reports/*.txt`. `/child crashes <datei>` liest. |

Per RCON (Port 2062) funktionieren dieselben Befehle ohne Schrägstrich.

## 6. Dashboard (`http://<SERVER-IP>:2064/`)

- **Login** mit Passwort aus `plugins/FabricLauncher/dashboard-password.txt` (Datei ändern + Spigot-Neustart = neues Passwort). Sitzungen 12 h; nach 5 Fehlversuchen Sperre pro IP (30 s, steigend bis 1 h).
- **Status:** Start/Neustart/Stopp, Uptime, PID, Spieler.
- **Ressourcen Spigot vs. Fabric** (Aktualisierung alle 3 s): RSS, Heap, CPU (% eines Kerns), Threads, TPS/MSPT, geladene Chunks/Entities der Lobby. Child-TPS/MSPT kommen aus `tick query` (höchstens alle 10 s, nur solange das Dashboard offen ist; die Antwortzeilen werden aus Konsole/Log herausgefiltert). Spigot-„MSPT“ ist das gemessene Tick-Intervall (~50 ms = gesund). Child-Heap ist von außen nicht messbar (angezeigt: Xmx).
- **Konsole** live (SSE) mit Befehlseingabe, **Spieler** (Whitelist/OP/Kick/Ban), Rohtext für **server.properties** und **config.yml**, **Dateimanager** (nur innerhalb `child-server/`, Upload bis 1 GB, Löschen nur einzelner Dateien/leerer Ordner mit Bestätigung), **Logs** (Level, Filter, auch `.gz`, Fenster vom Dateiende), **Mods** (Liste, Toggle, Modrinth on demand), **Diagnose** (RSS, FDs, Platte, MSPT-Verlauf, `jcmd`).
- **TLS:** `dashboard.https.enabled` mit `mode` `self-signed`, `keystore` oder `pem`. Aus bleibt HTTP. Paper- und Vanilla-Jars werden beim Download per SHA-256 bzw. SHA-1 geprüft, Modrinth-Dateien per SHA-1.
- **Server, Aufgaben, Backup, Versionen:** Profilwechsel, Start/Stopp, Cron-Lauf mit nächster Zeit, Zip-Backup, Liste, Download, Löschen, Restore, JDBC-Test, Versionsliste. Gleiche Ports stehen als `clash`. Restore nur bei gestopptem Child. Die Versionsliste schreibt nichts in die Config. `config.yml` und `server.properties` bleiben Rohtext. `?server=<id>` wählt das Profil; der Schalter im Kopf setzt `focusServer`.
- **Mehrere Profile:** `servers.<id>` neben dem Wurzelprofil. `allowParallel: false` hält einen Child. Spigot-Jars entstehen erst beim Start über BuildTools. Backup-Zips und die JDBC-Verbindung entstehen erst beim Backup. Es wird keine lokale Datenbank installiert.

Aufsicht, Dateibaum, Konsole und Archiv mit Prüfsumme folgen Pterodactyl Wings. Zeitpläne, Zeitzonen, mehrere Versionen und das Zurückspielen eines Archivs folgen Crafty Controller. Der MSPT-Verlauf folgt der Darstellung von spark, die Werte kommen nur aus dem Vanilla-Befehl `tick query`. Paper-Prüfsummen folgen Fill v3. Mods kommen von der Modrinth-API und werden wie in Prism/Modrinth mit `.jar.disabled` abgeschaltet. Spigot-Jars kommen aus dem offiziellen BuildTools. Nichts davon lädt ein Mod in den Child, solange es nicht ausdrücklich installiert wird, und nichts davon ändert Spielregeln.

> **Sicherheitshinweis:** Ohne `dashboard.https.enabled` läuft das Dashboard über **unverschlüsseltes HTTP**. Passwort und Sitzungscookie können in fremden Netzen mitgelesen werden. Nur aus vertrauenswürdigen Netzen nutzen, starkes Passwort verwenden, bei Verdacht Passwort-Datei ändern und Spigot neu starten. Mit TLS setzt das Cookie `Secure`. Das Dashboard hat Vollzugriff auf den Child-Server (Konsole, Dateien).

## 7. OuterTuner (äußerer Spigot)

Bei jedem Plugin-Start (wenn `neuterOuter: true`):

- **server.properties (Spigot):** `view-/simulation-distance=2`, `spawn-monsters/animals/npcs=false`, `allow-nether=false`, `generate-structures=false`, `max-players=2`, `white-list=true`, `enforce-whitelist=true`, `spawn-protection=0`, `sync-chunk-writes=false`, `entity-broadcast-range-percentage=10`. Query (2061) bleibt an.
- **bukkit.yml:** `allow-end=false`, `allow-nether=false`, alle `spawn-limits` = 0, Spawn-Intervalle 6000, `autosave` 72000 Ticks, `chunk-gc` 200. Vorherige Version als `bukkit.yml.fl-bak`.
- **spigot.yml:** View/Simulation 2, `mob-spawn-range` 0, Activation-Range 2, Tracking-Range 8, `tick-inactive-villagers: false`, `nerf-spawner-mobs: true`, `save-user-cache-on-stop-only: true`. Backup `spigot.yml.fl-bak`.
- **Laufzeit (Lobby-Welten):** Mob-Spawning, Wetter, Feuer aus, `randomTickSpeed 0`, Spawn-Chunk-Radius 0 (falls vorhanden).
- Entfernt **leere** Altordner `world_nether/`, `world_the_end/`, `timings/` im Root (nur wenn leer).
- Datei-Änderungen wirken ab dem **nächsten** Spigot-Start. `child-server/` wird nie angefasst.
- Zap setzt beim Start u. U. `max-players` zurück; der OuterTuner setzt es danach wieder (unkritisch, Whitelist sperrt ohnehin).

## 8. Herunterfahren und Speichern

- Alle 5 min `save-all` an den Child.
- Spigot-Stopp (Konsole, RCON `stop`, Zap-Panel): `onDisable` schickt `stop` an den Child und wartet bis 60 s; danach Kill (Log-Warnung).
- Zusätzlich JVM-Shutdown-Hook (25 s) für SIGTERM / Zap-Neustart, falls `onDisable` nicht läuft.
- Empfohlen: Server per RCON `stop` oder Panel-Stopp beenden, **nicht** „Kill“.

## 9. Fabric / Mods aktualisieren

- **Neue MC-Version:** `mcVersion` ändern → `/child restart`. Fehlt `fabric-server-launch-<mc>.jar`, wird es mit neuestem Loader geladen.
- **Neuer Fabric-Loader bei gleicher Version:** `child-server/fabric-server-launch-<mc>.jar` löschen → `/child restart`.
- **Mods:** neue Slugs unter `mods:` mit `true` eintragen. Geladen wird nur, wenn keine Markerdatei `mods/.fl-<slug>-*.marker` existiert. Update: Mod-Jar **und** Marker löschen → Neustart. Eigene Mods einfach nach `child-server/mods/` legen.
- Vor Versionssprüngen immer ein Backup der Welt machen.

## 10. Wechsel auf Paper / Vanilla / eigenes Jar

`type: paper` (lädt Paper über fill.papermc.io, bevorzugt STABLE), `type: vanilla` (Mojang-Manifest) oder `type: custom` + `customJar`. Danach `/child restart`. Der Weltordner `child-server/world` bleibt; Paper legt Nether/End allerdings in eigene Ordner (`world_nether`, `world_the_end`) – vorher sichern und ggf. umziehen.

## 11. Fehlerbehebung

| Problem | Lösung |
|---|---|
| Child startet nicht, „Unrecognized VM option“ | Java 25 kennt einige alte Aikar-Flags nicht. `-XX:+IgnoreUnrecognizedVMOptions` wird automatisch gesetzt, `G1RSetUpdatingPauseIntervalMillis` gefiltert. Weitere problematische Flags aus `jvmFlags` entfernen. |
| FTP-Upload liefert **550** | Datei/Ordner gehört dem Server-Prozess bzw. Root-Ordner ist für FTP nicht beschreibbar. Jars lassen sich überschreiben. Konfiguration über Dashboard bzw. `/child config set` ändern; Root-Ordner kann nicht per FTP gelöscht werden (OuterTuner räumt leere Ordner). |
| Zap-Neustart / Panel-Stopp | Shutdown-Hook stoppt den Child (25 s). Bei Datenverlust-Sorge vorher `/child cmd save-all` bzw. RCON `stop` nutzen. |
| Child stürzt in Schleife ab | Log ansehen; Auto-Restart mit wachsender Pause bis 300 s. `/child stop` beendet die Schleife. |
| Dashboard nicht erreichbar | Port 2064, `dashboard.enabled`, Log „Dashboard listening…“. Gesperrt? 30 s bis 1 h warten. |
| Spieler kommen nicht rein | Port **2063** benutzen (nicht 2060). `/child status`, Child-Whitelist ist absichtlich aus. |
| TPS im Dashboard leer | Child noch < 30 s online oder Dashboard gerade erst geöffnet – ~10 s warten. |

## 12. Backup

- Vollbackup: `C:\Users\aiche\MinecraftBackup\2026-10-12\g266459` (auf FlosPC).
- Plugin-Jars + diese Doku: `C:\Users\aiche\MinecraftBackup\plugins\`.
- Deploy-Hilfen (RCON-Skript, Konfig-Kopien): `C:\Users\aiche\MinecraftBackup\deploy\`.

## 13. WorldPorter (außer Dienst)

WorldPorter war ein einmaliges Hilfsplugin, das die alte Spigot-Welt (`world`, `world_nether`, `world_the_end`) nach `child-server/world` (Vanilla-Dimensionsstruktur) verschoben und mit `plugins/WorldPorter/done.marker` als erledigt markiert hat. Die Migration ist abgeschlossen; das Plugin wurde am 12.10.2026 vom Server entfernt. Das Jar liegt unter `C:\Users\aiche\MinecraftBackup\plugins\WorldPorter-1.0.0.jar`. **Nicht erneut installieren**, solange `child-server/world` existiert.

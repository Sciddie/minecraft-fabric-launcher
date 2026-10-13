# FabricLauncher einrichten (neuer Spigot-Server)

Schritt für Schritt auf einem **neuen** Server, bei dem der Hoster nur Spigot als Spieltyp anbietet (zum Beispiel Zap-Hosting). Gespielt wird nicht auf dem äußeren Spigot, sondern auf dem Fabric-Prozess, den das Plugin startet.

Alle Ports, Pfade und Standardwerte unten stammen aus `src/main/resources/config.yml`, `spigot-minimal-config/` und dem Plugin-Code (`FabricLauncherPlugin`, `OuterTuner`, `Dashboard`). Die Zahlen **2063** und **2064** sind die mitgelieferten Beispiele. Wenn der Hoster andere freie Ports vorgibt, diese Werte in der Konfiguration eintragen, nicht die Beispiele erzwingen.

Öffentliche Adresse immer als `<SERVER-IP>` lesen. Kein festes Passwort ist im Plugin hinterlegt.

## Ablauf in Kurzform

1. Hoster: Spigot mit **Java 25**, genug RAM, zwei freien Zusatzports.
2. Äußere `server.properties` / `bukkit.yml` / `spigot.yml` aus `spigot-minimal-config/` übernehmen, **ohne** den Spigot-Port des Hosters zu ändern.
3. `FabricLauncher-1.4.0.jar` nach `plugins/` laden.
4. Spigot starten und warten, bis Fabric-Jar und Mods da sind.
5. Spigot einmal **sauber** neu starten, damit die OuterTuner-Änderungen gelten.
6. Im Spiel `<SERVER-IP>:<Fabric-Port>` nutzen, Dashboard im Browser über den Dashboard-Port.

## 1. Voraussetzungen

### Java

- Der **Host** muss Spigot mit **Java 25** betreiben. `pom.xml` setzt `maven.compiler.release` auf 25, `plugin.yml` hat `api-version: '26.3'`, die Abhängigkeit ist `spigot-api` `26.3-R0.1-SNAPSHOT`. Standard-`mcVersion` des Child ist `"26.3"`.
- Das Plugin lädt **kein** eigenes JDK. Der Child wird mit derselben Java gestartet, die Spigot benutzt: `java.home/bin/java` (unter Windows `java.exe`).
- Zum **Selberbauen** brauchst du JDK 25 und Maven. Ein fertiges Jar braucht auf dem Host nur das Java des Spigot-Prozesses.

### RAM

`spigot-minimal-config/NOTES.md` rechnet ein Paket mit **12 GB** so vor:

| Anteil | Größe | Wo |
|---|---|---|
| Äußerer Spigot | etwa 1–1,5 GB Xmx, im Betrieb oft darunter | Hoster-Panel, RAM/Xmx von Spigot |
| Fabric-Child | `memory.xms` / `memory.xmx`, Standard **8G / 8G** | `config.yml` |
| Puffer für beide JVMs | etwa 1–1,5 GB | Metaspace, Threads, Direct Buffers. `AlwaysPreTouch` wird nicht gesetzt |

Zwingt das Panel den äußeren Spigot auf die vollen ~12 GB, den Child auf etwa **7G** senken. Sonst killt der Container beide Prozesse (OOM). Der äußere Server soll leer bleiben, damit die Kerne dem Child gehören.

### Ports

Du brauchst den normalen Spigot-Port des Hosters **plus zwei freie Ports**, die von außen erreichbar sind:

| Rolle | Konfigurationsschlüssel | Mitgelieferter Wert | Wer bindet |
|---|---|---|---|
| Spigot (Lobby) | äußerer `server-port` | im Beispiel 2060, **nicht ändern** | Hoster-Spigot |
| Fabric, hier wird gespielt | `port` | `2063` | Child |
| Web-Dashboard | `dashboard.port` | `2064` | Plugin, HTTP |

`serverIp` (Standard `"0.0.0.0"`) ist die Bind-Adresse des Child. `""` bindet ebenfalls alle Schnittstellen. Spieler verbinden sich mit `<SERVER-IP>` und dem Wert von `port`, nicht mit dem Spigot-Port.

Query- und RCON-Port des äußeren Spigot (im Beispiel 2061 und 2062) gehören zum Hoster. Das Plugin setzt sie nicht.

### Netz vom Server nach außen

Beim ersten Child-Start lädt das Plugin per HTTPS:

- Fabric-Loader, Installer und Server-Jar von `meta.fabricmc.net`
- Mods von `api.modrinth.com` (nur bei `type: fabric`)

Ohne ausgehendes HTTPS bleibt der Child stehen, das Spigot-Log zeigt `Failed to start child` bzw. `Downloading …` und danach einen HTTP-Fehler.

### Zugang

FTP oder SFTP auf das Spigot-Verzeichnis, Konsole oder RCON. Das RCON-Passwort steht **nicht** in diesem Repo (`spigot-minimal-config/server.properties` vermerkt nur, dass `rcon.password` getrennt gesetzt wird).

## 2. Jar besorgen

### Selber bauen

Auf einem Rechner mit **JDK 25** und Maven, im Repository-Root (`pom.xml` liegt dort, nicht in `worldporter/`):

```bash
mvn package
```

Maven holt `spigot-api` `26.3-R0.1-SNAPSHOT` von `https://hub.spigotmc.org/nexus/content/repositories/snapshots/`. Das Ergebnis ist `target/FabricLauncher-1.4.0.jar` (`finalName` ist `FabricLauncher-${project.version}`, Version `1.4.0`).

`worldporter/` ist ein zweites, stillgelegtes Projekt. Es nicht bauen und nicht installieren.

### Fertiges Jar

Wenn du `FabricLauncher-1.4.0.jar` schon hast, diesen Build-Schritt weglassen und genau diese Datei hochladen. Im Repo liegt kein vorgebautes Jar.

## 3. Äußeren Spigot vorbereiten

Bevor das Plugin zum ersten Mal läuft, die Schlüssel aus `spigot-minimal-config/` in die Dateien des Hosters übernehmen. Den **`server-port` des Hosters nicht anfassen**. Die 2060 in der Beispieldatei ist nur der Port dieses einen Zap-Pakets.

Aus `spigot-minimal-config/server.properties`:

- `server-ip=0.0.0.0` (alle Schnittstellen; Spieler nutzen `<SERVER-IP>`)
- `level-name=lobby_empty`
- `level-type=minecraft:flat` und die Void-`generator-settings` (eine Luftschicht, Biom `the_void`)
- `generate-structures=false`, `spawn-monsters=false`, `spawn-protection=0`, `allow-nether=false`
- `white-list=true`, `enforce-whitelist=true`, `max-players=1`
- `view-distance=2`, `simulation-distance=2`, `max-world-size=16`
- `online-mode=true`, `sync-chunk-writes=false`, `network-compression-threshold=256`
- `enable-query=false`, `enable-rcon=true`, `entity-broadcast-range-percentage=10`

`enable-query` lässt der OuterTuner später in Ruhe. Die Beispieldatei schaltet Query aus. Braucht die Hoster-Anzeige Query, `enable-query=true` selbst setzen und den Query-Port des Hosters behalten.

RCON-Passwort hier nicht eintragen, wenn du die Datei weitergibst. Der Hoster setzt es in der Regel selbst.

Aus `bukkit.yml` und `spigot.yml` die Schlüssel derselben Dateien mergen (Nether/End aus, Spawn-Limits 0, kurze View-Distance, wenige Threads). Exakte Werte stehen in den beiden Dateien. Der OuterTuner überschreibt einen Teil davon beim Plugin-Start noch einmal (Abschnitt 6); wirksam wird das erst beim **nächsten** Spigot-Start.

Neue Lobby: vorhandene Ordner `world`, `world_nether`, `world_the_end` vor dem ersten Start entfernen, damit Spigot `lobby_empty` als kleine Void-Welt neu erzeugt. Eine Welt, die du behalten willst, **nicht** löschen, sondern erst nach Abschnitt 8 umziehen. `level-name=lobby_empty` muss stehen, **bevor** Spigot die alte Welt lädt.

## 4. Upload per FTP

Spigot muss dabei aus sein, wenn du die äußeren Yml/Properties schreibst.

```
<Spigot-Root>/
├── plugins/
│   └── FabricLauncher-1.4.0.jar    ← nur das Jar, nichts anderes aus dem Repo
├── server.properties               ← äußerer Spigot, Port des Hosters behalten
├── bukkit.yml
├── spigot.yml
└── child-server/                   ← nicht vorher anlegen
```

- Das Plugin gehört nach `plugins/`, nicht nach `plugins/FabricLauncher/` und nicht in den Server-Root.
- `child-server/` legt das Plugin selbst an (`workingDir: ./child-server`, relativ zum Arbeitsverzeichnis von Spigot). Legst du den Ordner vorher an, gilt er nicht mehr als „frisch“: `ops.json`, `whitelist.json`, `banned-players.json`, `banned-ips.json` und `usercache.json` werden dann **nicht** aus dem Root kopiert.
- `plugins/FabricLauncher/` entsteht beim ersten Start. Dort liegen danach `config.yml` und `dashboard-password.txt`.
- WorldPorter nicht hochladen.

Stimmen die freien Ports nicht mit 2063/2064 überein, die `config.yml` **in der Jar** vor dem Upload anpassen (`port`, `dashboard.port`, bei Bedarf `memory`). Grund: `onEnable` schreibt die Standard-Config und startet den Child danach sofort (`autoStart: true`). Eine Datei auf der Platte gibt es vorher nicht.

## 5. Erster Start

Spigot starten. In dieser Reihenfolge passiert beim Enable:

1. `plugins/FabricLauncher/config.yml` wird aus der Jar erzeugt, falls sie fehlt (`saveDefaultConfig`).
2. `neuterOuter: true`: OuterTuner patcht die äußeren `server.properties`, `bukkit.yml` und `spigot.yml`. Datei-Änderungen gelten ab dem nächsten Spigot-Start. Leere Ordner `world_nether/`, `world_the_end/` und `timings/` im Root werden entfernt, wenn sie leer sind und nicht die geladene Welt sind. `child-server/` wird nicht angefasst.
3. Gleich danach Laufzeit-Drosselung der schon geladenen Lobby-Welten (keine Mobs, kein Wetter, kein Feuer, `randomTickSpeed 0`, Spawn-Chunk-Radius 0, soweit die Regeln existieren).
4. Datenordner und `config.yml` werden beschreibbar gemacht (POSIX `rwxrwxrwx` bzw. `rw-rw-rw-`, Fehler werden ignoriert).
5. Dashboard startet, sofern `dashboard.enabled` true ist. Im Log steht `Dashboard listening on http://<bind>:<port>/`.
6. Liegt `dashboard-password.txt` nicht oder ist sie leer, erzeugt das Plugin 20 Zeichen und schreibt die Datei. Das Passwort erscheint **einmal** im Spigot-Log zwischen den Trennstrichen `====`.
7. Shutdown-Hook und, standardmäßig alle 5 Minuten, `save-all` an den Child.
8. `autoStart: true` startet den Child. Die Konsole meldet `Starting child (downloads may take a while)...` nur beim Befehl `/child start`; beim Auto-Start steht im Log `Launching child: …`.

Beim ersten `launch()` zusätzlich:

- Ordner `child-server/` anlegen.
- Nur wenn der Ordner eben neu war: die fünf Spielerdateien aus dem Root kopieren, falls vorhanden und im Child noch nicht da.
- Fehlt `child-server/fabric-server-launch-<mcVersion>.jar`, neuesten **stabilen** Fabric-Loader und Installer von `meta.fabricmc.net` ermitteln und das Server-Jar laden. Log: `Downloading …`.
- Mods, nur bei `type: fabric` und nur wenn der Schalter true ist und noch keine Markerdatei `mods/.fl-<slug>-*` existiert:
  - `lithium: true` → Modrinth-Projekt `lithium`
  - `ferrite-core: true` → Modrinth-Projekt `ferrite-core` (FerriteCore)
  - `c2me-fabric: false` → wird nicht geladen
  - Es wird ein `release` bevorzugt, sonst der erste Treffer für Loader `fabric` und `mcVersion`. Log: `Installed mod <slug> -> <dateiname>`. Gibt es keinen Build, nur eine Warnung, der Start geht weiter.
- `child-server/eula.txt` wird bei **jedem** Start auf `eula=true` gesetzt.
- `child-server/server.properties`: erzwungene Schlüssel aus `serverProperties` plus `server-port` und `server-ip`. Andere vorhandene Schlüssel bleiben. Fehlt die Datei, werden zusätzlich nur falls abwesend gesetzt: `level-name=world`, `white-list=false`, `enforce-whitelist=false`, `enable-rcon=false`, `enable-query=false`.
- Prozess: `java -XX:+IgnoreUnrecognizedVMOptions -Xms… -Xmx… <jvmFlags> -jar <jar> nogui`. Flags, die `G1RSetUpdatingPauseIntervalMillis` enthalten, werden ausgelassen. Ausgabe des Child erscheint mit Präfix `[Child] ` im Spigot-Log.

Der erste Start dauert, solange Jar und Mods laden. Danach Spigot **einmal sauber stoppen und wieder starten** (Abschnitt 12), damit View-Distance, Whitelist und `max-players` des äußeren Servers wirklich aus den gepatchten Dateien kommen.

Prüfen:

- Spigot-Log: `Dashboard listening on http://0.0.0.0:2064/` (oder dein Bind/Port) und `Installed mod lithium` / `Installed mod ferrite-core`.
- `child-server/fabric-server-launch-26.3.jar` und `child-server/mods/` existieren.
- Im Spiel: `<SERVER-IP>:2063` (bzw. dein `port`). Der Spigot-Port ist die leere Lobby mit Whitelist.

## 6. Äußeren Spigot klein halten

`neuterOuter: true` ist der Standard. Bei jedem Plugin-Start schreibt der OuterTuner in die **äußeren** Dateien, nicht in `child-server/`:

**server.properties:** `view-distance=2`, `simulation-distance=2`, `spawn-monsters=false`, `spawn-animals=false`, `spawn-npcs=false`, `allow-nether=false`, `generate-structures=false`, `max-players=2`, `white-list=true`, `enforce-whitelist=true`, `spawn-protection=0`, `sync-chunk-writes=false`, `entity-broadcast-range-percentage=10`.

Die Beispieldatei setzt `max-players=1`. Nach dem ersten Enable steht in der Datei **2**, sobald der OuterTuner sie angefasst hat. Beides ist absichtlich klein. Niemand soll auf dem äußeren Port spielen.

**Whitelist zu:** `white-list=true` und `enforce-whitelist=true`. Die Liste leer lassen. Wer den äußeren Port trotzdem aufmacht, kommt nicht durch.

**bukkit.yml** (vorherige Fassung wird `bukkit.yml.fl-bak`): `settings.allow-end=false`, `settings.allow-nether=false`, alle `spawn-limits` auf 0, `ticks-per.*-spawns` auf 6000, `ticks-per.autosave` auf 72000, `chunk-gc.period-in-ticks` auf 200. Die mitgelieferte Beispiel-`bukkit.yml` hat noch die kleineren Spawn-Intervalle 400 und `autosave: 36000`; nach dem Patch gelten die OuterTuner-Werte.

**spigot.yml** (Backup `spigot.yml.fl-bak`): View/Simulation 2, `mob-spawn-range` 0, Activation-Range 2, Tracking-Range 8, `tick-inactive-villagers: false`, `nerf-spawner-mobs: true`, `settings.save-user-cache-on-stop-only: true`.

Ein Hoster-Panel kann `max-players` beim Start zurücksetzen. Der OuterTuner setzt es beim nächsten Enable wieder. Die Whitelist sperrt unabhängig davon.

`neuterOuter: false` schaltet das Patchen ab. Für einen reinen Wirt-Server nicht abschalten.

Optional aus `NOTES.md`: unnötige Plugins entfernen und in `plugins/bStats/config.yml` die Schalter `settings.timings` / bStats ausmachen. Das macht das Plugin nicht selbst.

## 7. `config.yml`

Datei: `plugins/FabricLauncher/config.yml`. Änderungen an Child-Optionen gelten mit `/child restart` (lädt die Datei neu). `dashboard.port` und `dashboard.bind` erst nach einem Spigot-Neustart, weil das Dashboard nur in `onEnable` startet.

| Schlüssel | Standard | Bedeutung |
|---|---|---|
| `type` | `fabric` | `fabric`, `paper`, `spigot`, `vanilla` oder `custom`. Paper lädt über `fill.papermc.io` (STABLE, sonst der erste Build). Vanilla lädt über das Mojang-Manifest. `spigot` baut das Jar erst beim Start mit BuildTools (`plugins/FabricLauncher/buildtools/`, `git` muss liegen). `custom` startet nur `customJar`, ohne Mod-Download. |
| `mcVersion` | `"26.3"` | Minecraft-Version des Child. Steckt im Jar-Namen `fabric-server-launch-<mcVersion>.jar` und in der Modrinth-Abfrage. |
| `port` | `2063` | Wird bei jedem Child-Start als `server-port` erzwungen. Muss ein freier, weitergeleiteter Port sein. |
| `serverIp` | `"0.0.0.0"` | Bind-Adresse. `""` = alle Schnittstellen. Kein Hostname und nicht `<SERVER-IP>` eintragen, wenn das keine lokale Bind-Adresse ist. |
| `customJar` | `"server.jar"` | Nur bei `type: custom`. Relativ zu `workingDir`, oder absolut. Fehlt die Datei, bricht der Start mit `Custom jar not found` ab. |
| `fabricLoaderVersion` | `latest` | `latest` = neuester stabiler Loader. Ein konkreter Versionsstring wird so verwendet. |
| `fabricInstallerVersion` | `latest` | Wie der Loader, für den Fabric-Installer. |
| `workingDir` | `./child-server` | Arbeitsverzeichnis des Child. Relativ zum Spigot-Root, sofern nicht absolut. |
| `memory.xms` / `memory.xmx` | `8G` / `8G` | Werden zu `-Xms` und `-Xmx`. An den Hoster anpassen (Abschnitt 1). Die `jvmFlags` enthalten keine Memory-Flags. |
| `jvmFlags` | Aikar-Flags (G1) | Zusätzliche JVM-Argumente vor `-jar`. `-XX:+IgnoreUnrecognizedVMOptions` setzt das Plugin immer davor. Ein Flag, dessen Text `G1RSetUpdatingPauseIntervalMillis` enthält, wird entfernt (unter Java 25 ungültig). |
| `serverArgs` | `[]` | Argumente nach `nogui`. |
| `autoStart` | `true` | Child starten, sobald das Plugin enabled. |
| `autoRestart.enabled` | `true` | Nach einem Absturz neu starten. `/child stop` setzt das Wunsch-Flag auf aus und beendet die Schleife. |
| `autoRestart.initialDelaySeconds` | `5` | Erste Pause. Jeder weitere Versuch verdoppelt sie (`base * 2^Versuche`, gedeckelt). |
| `autoRestart.maxDelaySeconds` | `300` | Obergrenze der Pause. |
| `autoRestart.resetAfterSeconds` | `600` | Lief der Child mindestens so lange, wird der Zähler nach dem Exit auf 0 gesetzt. |
| `autoRestart.maxAttempts` | `0` | `0` = unbegrenzt. Sonst gibt das Plugin nach dieser Zahl auf. |
| `stopTimeoutSeconds` | `60` | Nach `stop`: so lange auf ein sauberes Ende warten, danach Kill. |
| `shutdownHookTimeoutSeconds` | `25` | Dieselbe Logik, wenn die JVM per SIGTERM endet und `onDisable` nicht läuft. |
| `saveAllIntervalMinutes` | `5` | Intervall für `save-all` an den Child. `0` = aus. |
| `logPrefix` | `"[Child] "` | Präfix der Child-Zeilen im Spigot-Log. |
| `mods.<slug>` | lithium an, ferrite-core an, c2me-fabric aus | Nur `type: fabric`. `true` lädt den Modrinth-Slug, wenn kein Marker `mods/.fl-<slug>-*` existiert. Eigene Mods einfach nach `child-server/mods/` legen. |
| `serverProperties.*` | siehe Abschnitt 8 | Diese Schlüssel schreibt der Start **jedes Mal** in die Child-`server.properties`. |
| `dashboard.enabled` | `true` | `false` startet keinen HTTP-Server. |
| `dashboard.port` | `2064` | Port des Dashboards. |
| `dashboard.bind` | `""` | Leer = `serverIp` verwenden. Ist auch das leer, bindet das Dashboard `0.0.0.0`. |
| `dashboard.https.enabled` | `false` | `true` startet das Dashboard als HTTPS. Material entsteht erst dann. |
| `dashboard.https.mode` | `self-signed` | `self-signed` (keytool-PKCS12), `keystore` (vorhandenes PKCS12/JKS) oder `pem` (Zertifikat + PKCS#8 oder RSA PKCS#1). |
| `consoleBufferLines` | `4000` | Zeilen im Konsolen-Ring des Dashboards (200–20000). |
| `serverId` | `default` | Id des Wurzelprofils (die Schlüssel auf oberster Ebene). |
| `focusServer` | `default` | Profil für `/child` und das Dashboard ohne `?server=`. |
| `allowParallel` | `false` | `false`: ein Start stoppt die anderen Profile, Auto-Start nur für `focusServer`. `true`: jedes `autoStart` darf parallel laufen. |
| `servers.<id>.*` | `{}` | Weiteres Profil. Erbt Memory, JVM-Flags, Args, `serverIp`, Fabric-Versionen und `serverProperties`. Mods werden nicht geerbt. Fehlt `workingDir`, gilt `./child-<id>`. Port setzen, sonst Kollision mit dem Wurzelport. |
| `tasks` | `[]` | Cron mit fünf Feldern, `timezone` als ZoneId, `action` `command`/`save-all`/`start`/`stop`/`restart`/`backup`. DOM und DOW beide gesetzt = ODER. |
| `backup.directory` | `""` | Leer = `plugins/FabricLauncher/backups`. Zip erst beim Backup. |
| `backup.paths` | `[world]` | Pfade innerhalb des Profilordners. Symlinks werden übersprungen. |
| `backup.keep` | `5` | Ältere Zips desselben Profils werden nach dem Dateinamen gelöscht. Liste, Download, Löschen und Restore liegen im Dashboard und unter `/child backup list|delete|restore`. Restore verweigert den Lauf, solange der Child-Prozess läuft, und verwirft Zip-Einträge außerhalb des Profilordners. |
| `backup.saveWaitSeconds` | `3` | Pause nach `save-all`, wenn der Prozess läuft. |
| `backup.database.enabled` | `false` | Metadaten per JDBC. Keine lokale Datenbank. `driverJar` wird beim Schreiben geladen. |
| `neuterOuter` | `true` | OuterTuner an/aus. Er fasst keinen Profilordner an. |

Befehl statt Editor: `/child config set <pfad> <wert>`. `true`/`false` werden Boolean, ganze Zahlen Integer, `1.5` Double, `[list]a,b` eine Liste. Beispiel: `/child config set memory.xmx 7G`. Wirkt beim nächsten `/child restart`.

## 8. `server.properties` des Fabric-Child

Datei: `child-server/server.properties`. Nicht die äußere Datei.

Diese Standards stehen unter `serverProperties` und werden bei jedem Child-Start überschrieben:

| Schlüssel | Standard | Warum der Wert so ist |
|---|---|---|
| `white-list` | `false` | Auf dem Child wird gespielt. Die Whitelist des **äußeren** Servers bleibt an. |
| `enforce-whitelist` | `false` | Passend zu `white-list`. |
| `view-distance` | `12` | Sichtweite der Spielwelt. Unabhängig von der Lobby (dort 2). |
| `simulation-distance` | `4` | Simulationsweite. Kleiner als die Sichtweite, weniger Last. |
| `motd` | `Entspannt ne runde MC` | Serverliste. Frei änderbar. |
| `difficulty` | `normal` | Schwierigkeit. |
| `allow-flight` | `true` | Flug erlaubt (Elytren, Mods). |
| `spawn-protection` | `0` | Kein Spawn-Schutz. |
| `max-players` | `30` | Spielerlimit des Child, nicht das der Lobby. |
| `pause-when-empty-seconds` | `0` | `0` heißt: nicht pausieren, wenn niemand online ist. Der Wert muss `0` bleiben, sonst friert die Welt bei leerem Server ein. Steht er nur in der Child-Datei und nicht in `config.yml`, setzt der nächste Start ihn aus `serverProperties` wieder auf `0`. |
| `enable-query` | `false` | Kein Query auf dem Child. |
| `enable-rcon` | `false` | Kein RCON auf dem Child. Steuern über Spigot-Konsole, äußeres RCON oder Dashboard. |
| `sync-chunk-writes` | `false` | Chunk-Schreibmodus. |
| `network-compression-threshold` | `512` | Kompressionsschwelle. |

Zusätzlich immer: `server-port` aus `port`, `server-ip` aus `serverIp`.

`level-name=world` setzt das Plugin nur, wenn der Schlüssel noch fehlt. Die Welt liegt damit unter `child-server/world`.

Live ändern, ohne die ganze Datei zu ersetzen: `/child prop set <key> <wert>`. Ist der Schlüssel unter `serverProperties` geführt, wird `config.yml` mitgeschrieben, damit der nächste Start den Wert nicht zurückdreht. `server-port` und `server-ip` aktualisieren zusätzlich `port` und `serverIp`. Wirkt nach `/child restart`.

Andere Schlüssel (zum Beispiel `online-mode`) fasst das Plugin nicht an, solange sie nicht unter `serverProperties` stehen. Was die Datei schon enthält, bleibt.

## 9. Lithium: optionale Vanilla-Parity

Das Plugin lädt Lithium, schreibt aber **keine** `lithium.properties`. Lithium legt die Datei selbst im Config-Ordner des Child an:

`child-server/config/lithium.properties`

Erst nach dem ersten erfolgreichen Fabric-Start vorhanden. Danach diese Zeilen eintragen (vorhandene gleiche Schlüssel auf `false` setzen):

```
mixin.ai.poi.tasks=false
mixin.entity.framed_maps=false
mixin.minimal_nonvanilla=false
mixin.world.explosions.block_raycast.skip_air=false
mixin.ai.poi.fast_portals=false
mixin.ai.poi=false
mixin.ai.sensor.secondary_poi=false
```

`false` schaltet das jeweilige Mixin aus. Begründung, jeweils damit das Verhalten bei Vanilla bleibt:

- `mixin.ai.poi` — POI-Optimierung (Points of Interest: Betten, Arbeitsblöcke, Glocken). Genau hier weichen Dorfbewohner sonst vom Vanilla-Pfad ab und nehmen Betten nicht an.
- `mixin.ai.poi.tasks` — die Aufgaben, die POIs nachziehen. Aus, damit die POI-Daten wie in Vanilla aktualisiert werden.
- `mixin.ai.poi.fast_portals` — schnellere Portal-POI-Suche. Portale sind POIs; aus für den Vanilla-Weg.
- `mixin.ai.sensor.secondary_poi` — Sensor für zweite POIs. Dorfbewohner suchen darüber unter anderem ihr Bett.
- `mixin.entity.framed_maps` — Sonderfall Rahmen mit Karten. Aus, damit Rahmen-Karten nicht über die Lithium-Abkürzung laufen.
- `mixin.minimal_nonvanilla` — Sammelschalter für Optimierungen, die bewusst nicht deckungsgleich mit Vanilla sind.
- `mixin.world.explosions.block_raycast.skip_air` — der Explosions-Raycast überspringt sonst Luft. Aus, damit die Strahlen wie in Vanilla ausgewertet werden.

Lithium liest die Datei **nur beim Start des Child**. Speichern allein ändert nichts. Danach `/child restart` (oder ein sauberer Spigot-Stopp, der den Child mitnimmt).

Kontrolle im Child-Log (`child-server/logs/latest.log` oder die Spigot-Zeilen mit `[Child] `): eine Zeile, die `override(s) found` enthält. Fehlt sie, hat Lithium die Overrides beim Start nicht gelesen (falscher Ordner, Tippfehler, oder der Child lief noch mit der alten Datei).

Die äußere Lobby hat damit nichts zu tun. `tick-inactive-villagers: false` in der äußeren `spigot.yml` betrifft nur den Spigot-Wirt.

## 10. Bestehende Welt übernehmen

**WorldPorter ist außer Dienst.** Nicht bauen, nicht nach `plugins/` legen, nicht starten, sobald `child-server/world` existiert. Das Plugin hat Welten nur einmal per Umbenennen verschoben und sich mit `plugins/WorldPorter/done.marker` als erledigt markiert.

Für einen neuen Server ohne alte Welt entfällt dieser Abschnitt. Der Child legt `child-server/world` an.

Eine vorhandene Spigot-Welt von Hand in das Vanilla-/Fabric-Layout von 26.x bringen. Das ist derselbe Plan wie `direction: spigot-to-vanilla` in WorldPorter, nur ohne das Plugin. Quelle ist der Spigot-Root, Ziel `child-server`, Überworld-Ordner im Ziel heißt `world`.

1. Volles Backup. Das Plugin und diese Anleitung löschen nichts absichtlich; ein falsches Ziel schon.
2. In der **äußeren** `server.properties` `level-name=lobby_empty` setzen, **bevor** Spigot die alte `world` noch einmal lädt. Eine geladene Welt wird nicht angefasst (WorldPorter verweigert das, von Hand wäre es Datenverlust).
3. Spigot und Child stoppen (`child stop`, dann Panel-Stopp).
4. Ziel darf nicht existieren. Ein schon erzeugtes leeres `child-server/world` vorher beiseitelegen (umbenennen, nicht über den FTP-Root löschen wollen: der Root ist für FTP oft nicht beschreibbar, Fehler 550).
5. Auf **demselben Dateisystem** umbenennen, nicht quer über ein Netzlaufwerk kopieren. WorldPorter nutzt ausschließlich `ATOMIC_MOVE` und bricht ab, wenn das Ziel auf einem anderen Dateisystem liegt.

Layout ab 26.1, wenn die modernen Ordner existieren:

| Von | Nach |
|---|---|
| `world/` | `child-server/world/` |
| `world_nether/dimensions/minecraft/the_nether/` | `child-server/world/dimensions/minecraft/the_nether/` |
| `world_the_end/dimensions/minecraft/the_end/` | `child-server/world/dimensions/minecraft/the_end/` |

Gibt es `dimensions/minecraft/the_nether` nicht, dafür aber den alten Ordner, dann den Legacy-Ordner unter demselben Namen ins Ziel legen (so macht es der Code, er wandelt nicht um):

| Von | Nach |
|---|---|
| `world_nether/DIM-1/` | `child-server/world/DIM-1/` |
| `world_the_end/DIM1/` | `child-server/world/DIM1/` |

`level.dat` und `data` in den übrig gebliebenen `world_nether/` und `world_the_end/` nicht löschen. Sie bleiben als Restbackup liegen. Der OuterTuner löscht diese Ordner nur, wenn sie **leer** sind.

Liegt `child-server/` danach schon, kopiert der nächste Start die Spielerdateien aus dem Root nicht mehr. `ops.json`, `whitelist.json`, `banned-players.json`, `banned-ips.json` und `usercache.json` bei Bedarf selbst nach `child-server/` kopieren, solange die Zieldatei fehlt.

Paper ist ein anderes Layout (`world_nether` und `world_the_end` neben der Overworld). Das steht nur in der Referenzdoku für `type: paper` und ist nicht der Fabric-Weg.

## 11. Dashboard

Im Browser: `http://<SERVER-IP>:<dashboard.port>/` (Standard `http://<SERVER-IP>:2064/`).

Das Passwort steht nicht im Quellcode. Beim ersten Start, wenn `plugins/FabricLauncher/dashboard-password.txt` fehlt oder leer ist:

- 20 Zeichen aus einem eingeschränkten Alphabet, Datei mit abschließendem Zeilenumbruch.
- Versuch, die Rechte auf `rw-rw----` zu setzen.
- Einmalige Warnung im **Spigot-Log** (nicht in `child-server/logs`):

```
==================================================
 Dashboard password generated (shown once): …
 Stored in …/plugins/FabricLauncher/dashboard-password.txt (edit file + restart to change)
==================================================
```

Existiert die Datei und ist nicht leer, wird sie nur gelesen und **nicht** erneut geloggt. FTP auf `plugins/FabricLauncher/dashboard-password.txt` ist der Weg, wenn das Log schon weggerollt ist.

Passwort ändern: Datei ersetzen und **Spigot** neu starten. Ein `/child restart` lädt das Dashboard nicht neu. Die Login-Seite zeigt denselben Pfad.

Sitzung 12 Stunden, Cookie `FLS`, `HttpOnly`, `SameSite=Strict`. Nach 5 Fehlversuchen sperrt die IP für 30 Sekunden, danach länger, bis höchstens 1 Stunde.

Ohne `dashboard.https.enabled` ist das Dashboard unverschlüsseltes HTTP und hat Vollzugriff auf Konsole und Dateien des Child. Nur aus einem Netz nutzen, dem du vertraust. Mit `dashboard.https.enabled: true` spricht es TLS (`HttpsServer`). `mode: self-signed` legt beim Start `plugins/FabricLauncher/dashboard.p12` an (Passwort in `dashboard-keystore.pass`, falls in der Config leer) und trägt `localhost` sowie `127.0.0.1` als SAN ein, plus Einträge aus `dashboard.https.sans`. `mode: keystore` lädt eine vorhandene Datei. `mode: pem` erwartet Zertifikat und Schlüssel (PKCS#8 `BEGIN PRIVATE KEY` oder RSA PKCS#1). Das Sitzungscookie bekommt dann `Secure`. Ein fehlgeschlagenes TLS beendet nur das Dashboard, der Child kann weiterlaufen. `config.yml` und `server.properties` bleiben Rohtext.

## 12. Befehle

Permission `fabriclauncher.admin`, Standard **OP**. Konsole und RCON gelten als OP. Im Spiel mit Schrägstrich, in der Spigot-Konsole und per äußerem RCON **ohne** Schrägstrich (`child restart`).

| Befehl | Wirkung |
|---|---|
| `child start` | Child starten. Läuft er schon: `Already running.` Downloads können dauern. |
| `child stop` | `stop` an den Child, bis zu `stopTimeoutSeconds` (60 s) warten, danach Kill. Auto-Restart aus. |
| `child restart` | Stoppen, `config.yml` neu laden, Zähler zurück, wieder starten. |
| `child status` | Läuft oder nicht, PID, Uptime, letzter Status, Typ, Version, Port, Ordner. |
| `child cmd <befehl>` | Zeile an die Child-Konsole, führendes `/` wird entfernt. Beispiel: `child cmd op Spieler`. |
| `child config set <pfad> <wert>` | Schlüssel in `config.yml`. |
| `child prop set <key> <wert>` | Ein Schlüssel in der Child-`server.properties`. |
| `child mods` | Jars in `mods/` und `plugins/` (`.jar` an, `.jar.disabled` aus). |
| `child mod search <text>` | Modrinth-Suche, passend zum `type`. |
| `child mod install <slug> [version]` | Lädt das Jar erst auf diesen Befehl. Wirkt nach Neustart des Child. |
| `child mod toggle <mods/datei.jar>` | Benennt `.jar` ↔ `.jar.disabled` um. |
| `child diag` | RSS, Threads, FDs, MSPT, Plattenbelegung des Child-Ordners. |
| `child threaddump` / `heap` / `flags` | `jcmd Thread.print`, `GC.heap_info`, `VM.flags`. Nur lesend. |
| `child jar` | Lokaler Blick auf das Jar aus dem argv: fehlt, Bytes oder symlink. Kein Download, kein Lesen des Inhalts. |

`child restart` und `child stop` sind der normale Weg. Nicht den Java-Prozess des Child von außen killen.

## 13. Sauber stoppen, Hoster-Neustart

Alle `saveAllIntervalMinutes` (5) Minuten geht `save-all` an den Child.

| Wie der Prozess endet | Was der Code tut |
|---|---|
| `child stop`, Spigot-Befehl `stop`, RCON `stop`, Panel-Stopp, der `onDisable` ausführt | `stop` an den Child, Warten bis **60 s**, sonst Kill. Log warnt: ungesicherte Änderungen seit dem letzten `save-all` können weg sein. |
| SIGTERM / Panel-Neustart, bei dem `onDisable` nicht mehr läuft | Shutdown-Hook, Warten nur **25 s** (`shutdownHookTimeoutSeconds`), sonst Kill. |
| Hoster-Button „Kill“, oder ein Restart-Timeout kürzer als die Wartezeit | Der Fabric-Prozess wird hart beendet. Es gibt keinen sauberen `stop`. Weltstände seit dem letzten `save-all` (bis zu 5 Minuten) können fehlen. |

Vor einem geplanten Neustart `child cmd save-all` und danach `child stop` schicken, und erst dann das Panel. In `NOTES.md`: das Stopp-Timeout des Hosters sollte über 60 Sekunden liegen, wenn es sich einstellen lässt. „Kill“ nicht benutzen.

Ein Neustart über den Hoster kann Fabric also hart killen, sobald das Panel die JVM abwürgt, bevor die 25 Sekunden des Shutdown-Hooks (oder die 60 Sekunden von `onDisable`) um sind.

## 14. Optional: Domain über Cloudflare

Das Plugin kennt keine Domains. Minecraft-Java auf dem Fabric-Port ist reines TCP, das Dashboard ist HTTP auf einem zweiten Port.

**Spiel (Fabric-Port):**

- DNS-Eintrag (A oder AAAA) auf `<SERVER-IP>`, Proxy **aus** (nur DNS, graue Wolke). Der orangene Cloudflare-Proxy leitet das Minecraft-Protokoll nicht durch.
- Optional ein SRV-Record, damit der Client den Port nicht braucht: Name `_minecraft._tcp`, Ziel der DNS-Name von eben, Port = `port` aus `config.yml` (Standard 2063), Priorität und Gewicht 0. Der SRV-Name zeigt auf den DNS-only-Namen, nicht direkt auf eine IP.

**Dashboard:**

- Eigener Hostname auf `<SERVER-IP>`.
- Origin Rule: Zielport auf `dashboard.port` setzen (Standard 2064). Das Plugin lauscht nur auf diesem Port, nicht auf 80 oder 443.
- TLS am Origin ist optional (`dashboard.https.enabled`). Ohne diesen Schalter erwartet der Origin HTTP. Ein SSL-Modus, der dann HTTPS vom Origin verlangt, schlägt fehl. Mit eingeschaltetem TLS kann der Proxy auf HTTPS zum Origin gehen; das Zertifikat ist selbstsigniert, solange kein eigenes Keystore oder PEM liegt. Die Konsole hängt an einer dauerhaften SSE-Verbindung (`/api/console/stream`); ein Proxy muss die offen halten.
- Wer angemeldet ist, bedient den Server. Ohne TLS sind Passwort und Cookie unterwegs lesbar.

## 15. Störungen

| Was du siehst | Was zu tun ist |
|---|---|
| Child startet nicht | Spigot-Log nach `Failed to start child` und `Downloading` durchsuchen. Danach `child-server/logs/latest.log`. Die Child-Ausgabe liegt zusätzlich mit Präfix `[Child] ` im Spigot-Log. |
| Zeiten im Log passen nicht zur Uhr | Das Plugin stellt keine Zeitzone. Die Stempel in `child-server/logs/latest.log` sind die der JVM. Auf Linux-Hostern ist das in der Regel **UTC**, nicht die lokale Zeit. |
| Port belegt | Fabric-Port: der Child beendet sich, Auto-Restart versucht es erneut (5 s, dann länger, bis 300 s). `child stop` bricht die Schleife ab. `port` auf einen freien, im Panel geöffneten Port setzen und `child restart`. Dashboard-Port: Meldung `Dashboard failed to start`; der Child kann trotzdem laufen. `dashboard.port` ändern und Spigot neu starten. |
| Java fehlt oder ist zu alt | Es wird nur `java.home/bin/java` des Spigot-Prozesses benutzt, kein zweites JDK. `Failed to start child` zeigt die Ursache (Binary fehlt, oder der Server ist nicht Java 25). `Unrecognized VM option`: das Plugin setzt `-XX:+IgnoreUnrecognizedVMOptions` und filtert `G1RSetUpdatingPauseIntervalMillis`. Weitere alte Flags aus `jvmFlags` entfernen. |
| Dorfbewohner nehmen keine Betten | Abschnitt 9. Overrides in `child-server/config/lithium.properties`, Child neu starten, im Log `override(s) found` suchen. Die Lobby ist eine Void-Welt ohne Spiel; Betten gehören in `child-server/world`. |
| Spieler landen in der leeren Lobby | Sie benutzen den Spigot-Port. Verbinden mit `<SERVER-IP>` und `port` (Standard 2063). Die äußere Whitelist ist an und leer. |
| FTP 550 | Der Root gehört dem Serverprozess. Jars in `plugins/` lassen sich meist überschreiben. `config.yml` über das Dashboard oder `/child config set` ändern. Leere Root-Ordner räumt nur der OuterTuner. |
| Dashboard nicht erreichbar | `dashboard.enabled`, richtiger Port, Logzeile `Dashboard listening`. Nach Fehlversuchen 30 s bis 1 h warten. HTTP, nicht HTTPS, außer ein Proxy davor spricht selbst TLS. |
| Child-Schleife | `/child stop`, dann `child-server/logs/latest.log`. |
| TPS im Dashboard leer | Child jünger als 30 s, oder das Dashboard ist gerade erst offen. `tick query` geht höchstens alle 10 s und nur, solange das Dashboard den Status abfragt. |

Logpfade noch einmal:

- Spigot: das normale Serverlog des Hosters, Child-Zeilen mit `[Child] `.
- Fabric: `child-server/logs/`, aktuell `latest.log`, ältere Dateien auch `.gz`. Dieselben Dateien zeigt der Reiter „Logs“ im Dashboard.

Weiteres zur Architektur steht in [Dokumentation.md](Dokumentation.md).

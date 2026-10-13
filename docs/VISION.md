# Wohin die Plugins gehen

Zwei Linien, zwei Repos. Plugin-Name, Artifact und Datenordner bleiben überall `FabricLauncher` (`plugins/FabricLauncher`).

## Kern: minecraft-fabric-launcher

Ein äußerer Spigot-Wirt, ein Fabric-Child, Dashboard. Stand ist das volle Release **1.1.0** (TLS, Mods, lesende Diagnose).

Weitere Arbeit hier bleibt Kern und Admin am einzelnen Child: Prozess, Dashboard, HTTPS, Mods, Diagnose. Gameplay auf dem Child bleibt vanilla. Volle Releases.

Hoster-Funktionen kommen hier nicht mehr dazu. Draft [#1](https://github.com/Sciddie/minecraft-fabric-launcher/pull/1) bleibt Draft. Die Pre-Releases 1.2.0-pre.1 und 1.2.0-pre.2 bleiben als Historie liegen und werden nicht fortgesetzt.

## Hoster: minecraft-serverhoster-plugin

Mehrere Server und Versionen (Paper, Fabric, Spigot, Vanilla), Cron mit Zeitzone, Backup mit optionalem externen JDBC, Versionsliste, argv-Vorschau, lesende crash-reports.

Der leere Stand wird aus Commit `cedf0ab` (Tag `v1.2.0-pre.2`) als volles **1.2.0** übernommen, danach **1.3.0** (argv und crash-reports). Danach nur noch volle Releases auf diesem Repo. Draft [#2](https://github.com/Sciddie/minecraft-fabric-launcher/pull/2) bleibt Draft und wird nicht nach fabric-launcher gemergt.

## Für beide Linien

- Nichts am Gameplay ändern. Keine neuen Gameplay-Mods. Lithium- und Ferrite-Defaults bleiben.
- Ressourcen nur bei Benutzung laden. Keine lokale Datenbank, keine vorab geladenen Server-Jars.
- `config.yml` und `server.properties` bleiben rohe Textfelder. Listen und Aktionsbuttons sind erlaubt.
- Keine Ingame-Menüs.
- WorldPorter nicht bauen und nicht wieder einbauen.
- Jede Release-Note nennt die Vorlage: Pterodactyl Wings, Crafty Controller, spark nur als MSPT-Anzeige, PaperMC Fill v3, Prism/Modrinth `.jar.disabled`, Spigot BuildTools.

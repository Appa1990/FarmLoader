# FarmLoader 0.1.0 — erste Testversion

Separates Plugin für **Paper 26.3, Java 25**. Nicht für Folia oder Spigot.
CustomDaytime wird weder ersetzt noch verändert. Bei langen Tagen für Eisenfarmen
weiterhin CustomDaytime 2.1.4 mit aktiviertem Schlaf-Ausgleich verwenden.

## Installation

Server mit `stop` beenden. `build/libs/FarmLoader-0.1.0.jar` in den Serverordner
`plugins` kopieren, dann den Server normal starten. Kein `/reload`.
Die Konfiguration erscheint unter `plugins/FarmLoader/config.yml`.

## Einen Loader bauen

1. Einen normalen Armorstand am gewünschten AFK-Punkt aufstellen.
2. Einen **geschnitzten Kürbis** als Helm aufsetzen (normalen Kürbis zuerst mit einer Schere bearbeiten).
3. Ein Namensschild am Amboss **FarmLoader** nennen.
4. Mit diesem Namensschild in der Haupthand den Armorstand rechts anklicken.

Das Namensschild wird im Überlebensmodus verbraucht. Die Registrierung zählt
zum Limit des Spielers, der das Namensschild anwendet. **Standard: maximal zwei**
registrierte Loader je Spieler, einschließlich ausgeschalteter und entladener.
Normale Armorstands und gewöhnlich benannte Items sind keine Loader.

**Alle Spieler** dürfen Loader registrieren, per Rechtsklick umschalten und mit
**Schleichen + Linksklick** abbauen. Es gibt keine Besitzer-Sperre. Beim Abbauen
werden Armorstand, Ausrüstung und ein FarmLoader-Namensschild zurückgegeben;
der Platz wird beim ursprünglichen Ersteller frei. Ausrüstung eines registrierten
Loaders kann nicht einzeln entnommen werden. Bestehende Schutzplugins, die die
Interaktion oder den Schlag vorher abbrechen, werden respektiert.

Der sichtbare Name zeigt **AN**, **AUS** oder **WARTET**. Bei AN wird ein echter
serverseitiger, unsichtbarer Creative-Spieler am Stand simuliert. Er lädt und
simuliert über Papers Spieler-Chunkloader die Umgebung und zählt für natürliche
Spawns und Mob-Limits. Er besitzt keine Netzwerkverbindung und erzeugt keine
künstlichen Login-/Join-Ereignisse. Er sammelt keine Items ein und lässt sich
nicht per Teleport oder Portal vom AFK-Punkt entfernen.

## Position an der Farm

**Creeperfarm:** Den Stand an den bewährten AFK-Punkt setzen, nicht auf die
Spawnplattform. Es gelten weiterhin die normalen Spawn-Abstände: insbesondere
keine natürlichen Monster-Spawns innerhalb von 24 Blöcken eines relevanten
Spielers. Farm und Tötung müssen im passenden Simulations-/Despawn-Bereich liegen.
Andere echte Spieler oder weitere Loader können Spawn-Abstände und Mob-Limits
beeinflussen. Der Loader garantiert keine bestimmte Ausbeute und erzeugt keine
Creeper mit einem eigenen Spawn-Algorithmus.

**Eisenfarm:** Dorfbewohner, Betten, Golem-Plattform, Transport und Tötung müssen
in den simulierten Bereich passen. Die üblichen Golem-Bedingungen gelten weiter;
insbesondere müssen Dorfbewohner schlafen können.

## Konfiguration und Status

```yaml
max-per-player: 2
name-tag: FarmLoader
chunk-radius: 8
run-when-empty: true
```

Nach Änderungen Server neu starten. Radius 8 entspricht einem Simulationsradius
von acht Chunks je virtuellem Spieler (mit zusätzlichen Lade-Randchunks).
Der Wert wird auf 2–16 begrenzt. Paper- und Weltregeln für Mob-Spawns gelten weiter.
`run-when-empty: true` hält Loader auch ohne echte Spieler aktiv. Bei `false`
pausieren sie, wenn niemand online ist, und starten bei Rückkehr wieder.
Der Radius erzeugt vergleichbare Serverlast wie echte AFK-Spieler an den Farmen.
Die virtuellen Spieler können in Spielerzahlen und APIs anderer Plugins auftauchen.

`/farmloader` zeigt Hilfe und Anzahl, `/farmloader list` zeigt die registrierten
Punkte mit Koordinaten und Laufstatus. Beide Befehle sind für alle zugänglich.
`loaders.yml` enthält die dauerhafte Registrierung; nicht löschen, wenn Limits
und Loader nach einem Neustart erhalten bleiben sollen. Bei nicht lesbaren Daten
wird das Plugin gestoppt, anstatt die Datei und die Limits still zurückzusetzen.

## Stand der Prüfung

Gegen Paper **26.3 Build 214 stable** kompiliert. Automatische Tests prüfen
Zweierlimit, ausgeschaltete Loader, Besitzerzuordnung beim Umschalten/Abbau,
Speicherung und Wiederherstellung sowie die Ablehnung beschädigter Daten.

**Ein vollständiger Test auf einem laufenden Paper-Server mit echten Creeper-
und Eisenfarmen steht noch aus.** Diese erste Version enthält insbesondere einen
neuen Adapter für interne Serverklassen. Vor dauerhaftem Einsatz an einer Farm
prüfen und bei Fehlern die FarmLoader-Meldung aus `logs/latest.log` weitergeben.

Prüfung im Spiel:

1. Loader am AFK-Punkt registrieren; der Status muss nach kurzer Ladezeit AN sein.
2. Zweiten Loader registrieren; ein dritter muss ohne Namensschild-Verbrauch abgelehnt werden.
3. Ein anderer Spieler muss AN/AUS schalten und abbauen können.
4. Alle echten Spieler über 128 Blöcke entfernen bzw. ausloggen; Farm-Ertrag prüfen.
5. Server neu starten: aktive Loader müssen wieder anlaufen, inaktive bleiben AUS,
   und das Zweierlimit muss erhalten bleiben.
6. Loader abbauen: virtueller Spieler wird entfernt, Chunk-Tickets werden freigegeben,
   und der ursprüngliche Ersteller darf einen Ersatz registrieren.

Interne Serverklassen sind versionsabhängig. Ein Update auf eine andere
Minecraft-Version benötigt eine neue Prüfung und gegebenenfalls Anpassung.

## Bauen

Im FarmLoader-Ordner mit Java 25: `gradlew.bat test jar`.
Der eigene Wrapper nutzt Gradle 9.7.1 und paperweight-userdev; der Build des
separaten [CustomDaytime-Projekts](https://github.com/Appa1990/CustomDaytime-Paper)
bleibt unverändert. Lizenz: GPL-3.0.

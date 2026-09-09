# Bilderkennung geschossener Pfeile — Design

Datum: 2026-09-09
Status: Entwurf zur Umsetzung
Basis: `merge/eidgah-3.5.0` (Version 3.5.0, `ee274123` plus drei Korrekturen)

## Ziel

Der Schütze fotografiert nach jeder Passe die Auflage. Die App erkennt die
steckenden Pfeile, errechnet Lage und Ringwert und trägt sie als Treffer der
laufenden Passe ein. Das Foto wird zur Passe gespeichert, sodass die Korrektur
nicht am Schießplatz stattfinden muss.

Der Nutzen ist die Zeitersparnis am Ziel und eine Trefferlage, die genauer ist
als das, was sich mit dem Finger auf einem Handydisplay antippen lässt.

## Annahmen

Diese Annahmen begrenzen den Lösungsraum. Ändert sich eine, ist das Design neu
zu bewerten.

| Annahme | Begründung |
|---|---|
| Nahaufnahme aus 1–3 m, frontal bis leicht schräg | Der Schütze geht ohnehin zum Ziel, um die Pfeile zu ziehen. Aufnahmen von der Schießlinie lösen die Pfeilspitzen nicht auf. |
| Ein Foto pro Passe, Pfeile stecken noch | Eindeutige Zuordnung ohne Vergleich mit Vorbildern. |
| Auflagen: WA Full-Face und WA Spiegel/3-Spot | Beide sind farblich und geometrisch exakt im Modell beschrieben. Feldbogen und 3D bleiben außen vor. |
| Vollständig offline auf dem Gerät | Bogenplätze haben oft kein Netz. MyTargets ist GPLv2 und F-Droid-freundlich. |
| Kamera wird aufrecht gehalten | Konzentrische Kreise legen die Drehung um die Scheibenachse nicht fest (siehe *Offene Risiken*). |

## Umfang

**In v1:**

- Erkennung von Trefferlage (`x`/`y`) und Ringwert (`scoringRing`)
- Eintragen in die laufende Passe über den bestehenden `TargetView`
- Speichern des **Originalfotos** als `EndImage` der Passe
- Fehlerbehandlung mit unterscheidbaren Meldungen
- Debug-Ansicht der Pipelinestufen im Debug-Build
- Testkorpus mit Wahrheitsdaten und vier Kennzahlen

**Ausdrücklich nicht in v1:**

- Erkennung von Pfeilnummern (auf dem Foto meist unlesbar)
- Optische Markierung unsicherer Treffer im `TargetView`
- Feldbogen-, IFAA- und 3D-Auflagen
- Aufnahmen von der Schießlinie
- Mehrere Passen auf einem Bild

## Architektur

### Modulschnitt

```
:wearable ──> :shared                    (unverändert)
:app ──> :detection ──> :shared          (neu)
```

`:detection` ist ein eigenes Gradle-Modul. Der ausschlaggebende Grund ist die
**Iterationsgeschwindigkeit**: `:app` umfasst 255 Kotlin-Dateien und 60 Layouts
mit kapt, Data Binding, Navigation-Safe-Args und Crashlytics in der Build-Kette.
CV-Arbeit bedeutet dutzende Durchläufe pro Sitzung; jeder davon hinge sonst am
vollständigen App-Build.

Zweitens erzwingt die Modulgrenze, was sonst Disziplin wäre: In `:app` lägen
Room-DAOs, `SharedPreferences` und `InputActivity` in Reichweite, und die
Erkennung würde über kurz oder lang selbst darauf zugreifen — womit sie ohne
Datenbank nicht mehr testbar wäre.

`:detection` verwendet aus `:shared` ausschließlich `Target`, `TargetModelBase`
und `TargetDrawable`. Es kennt weder Room noch UI noch Kontext.

Abhängigkeit: OpenCV (Apache-2.0, mit GPLv2 verträglich). Die nativen Bibliotheken
vergrößern das APK spürbar; über ABI-Splits beziehungsweise ein App Bundle
bekommt der einzelne Nutzer nur seine Architektur.

### Schnittstelle

```kotlin
interface ArrowDetector {
    fun detect(bitmap: Bitmap, target: Target, expectedShots: Int): DetectionResult
}

data class DetectionResult(
    val shots: List<DetectedShot>,      // x/y in Scheibenkoordinaten
    val faceConfidence: Float,          // Güte der Auflagenregistrierung
    val failure: DetectionFailure?      // null bei Erfolg
)

data class DetectedShot(val x: Float, val y: Float, val confidence: Float)

enum class DetectionFailure { FACE_NOT_FOUND, FACE_MISMATCH }
```

Ein Bitmap und ein `Target` hinein, eine Trefferliste heraus. Kein Kamerazugriff,
kein Kontext, keine Nebenwirkungen. Genau diese Enge macht die Pipeline gegen
einen Testkorpus prüfbar und erlaubt später den Tausch der Pfeilerkennung
(Ansatz C), ohne die App anzufassen.

### Koordinatensystem

`Shot.x`/`Shot.y` sind normalisierte Scheibenkoordinaten: Mittelpunkt `(0,0)`,
äußerster Ring Radius `1.0` (siehe `WAFull.kt`). Der Ringwert folgt daraus über
`TargetModelBase.getZoneFromPoint()`. Die Pipeline liefert direkt dieses
Koordinatensystem; es findet keine zweite Umrechnung statt.

Spiegelauflagen brauchen keine Sonderbehandlung: `TargetModelBase.faceRadius`
und `facePositions` beschreiben Vollauflage (ein Spot bei `(0,0)`, Radius `1`)
und 3-Spot einheitlich.

## Die Erkennungspipeline

Der Kern des Ansatzes: Die App muss die Auflage nicht *verstehen* — sie kennt
sie bereits. Ringradien, Farben und Spot-Positionen stehen in den Modellklassen,
und `TargetDrawable` kann die Auflage rendern. Aus einer offenen
Erkennungsaufgabe wird damit eine **Registrierung**.

**Stufe 1 — Vorverarbeitung.** EXIF-Rotation anwenden (`androidx.exifinterface`
ist bereits Abhängigkeit), auf 1600 px lange Kante skalieren, nach HSV/Lab wandeln.

**Stufe 2 — Auflage lokalisieren.** Gelbe Blobs segmentieren; ihre Anzahl muss zu
`facePositions.size` passen, sonst `FACE_MISMATCH`. An den Farbübergängen mit
bekannten Radien (bei WA Full: 0.2, 0.4, 0.6, 0.8) Ellipsen fitten. Ein Kreis
wird perspektivisch zur Ellipse; aus mehreren konzentrischen Kreisen bekannter
Radien folgt per RANSAC eine überbestimmte und damit robuste Homographie.

**Stufe 3 — Entzerren.** Per Homographie in ein kanonisches Quadrat warpen, das
`[-1.1, 1.1]²` abdeckt. Ab hier arbeitet alles in Modellkoordinaten.

**Stufe 4 — Farbabgleich.** Referenzauflage in derselben Auflösung rendern. Da
bekannt ist, welche Pixel reines Gelb, Rot, Blau, Schwarz und Weiß sein müssen,
lassen sich Belichtung und Weißabgleich schätzen und herausrechnen. Damit ist
wechselndes Licht erledigt, bevor es die Segmentierung stört.

**Stufe 5 — Pfeile als Residuum.** Referenz vom abgeglichenen Foto abziehen.
Übrig bleiben Schäfte, Befiederung, Schatten und Altlöcher. Reine Abdunklung
ohne Farbtonänderung ist Schatten und wird verworfen. Längliche
Zusammenhangskomponenten sind Schaftkandidaten.

**Stufe 6 — Einschusspunkt.** Der Pfeil steht als Stab zur Kamera hin aus der
Scheibe; im Bild wird daraus ein Streifen. Aus der Homographie folgt der
Fluchtpunkt der Scheibennormalen. Ein Punkt über der Ebene wird stets *zu diesem
Fluchtpunkt hin* verschoben abgebildet. Also gilt:

> **Der Einschusspunkt ist das vom Fluchtpunkt weiter entfernte Ende des Streifens.**

Das erklärt auch, warum Pfeile auf Scheibenfotos nach außen zu spreizen scheinen,
und macht die Zuordnung berechenbar statt heuristisch. Ein Pfeil genau im
Fluchtpunkt bildet sich als Punkt ab — entartet, aber unschädlich.

**Stufe 7 — Auswahl und Ausgabe.** `Round.shotsPerEnd` ist ein starker
Filter: Bei mehr Kandidaten gewinnen die zuversichtlichsten, bei weniger meldet
die Pipeline die Lücke, statt zu raten. Ringwert über `getZoneFromPoint()`.

### Bekannte Grenzen der Pipeline

- **Pfeile neben der Auflage** sind nicht registrierbar und werden nicht erkannt.
  Sie werden von Hand nachgetragen.
- **Zwei Pfeile im selben Loch** ergeben einen Streifen und damit einen Treffer.
- **Stark überlappende Schäfte** können zu einem Kandidaten verschmelzen.

## Integration in die App

### Einstiegspunkt

Ein zweiter Menüpunkt in `input_end.xml` neben `action_photo`, sichtbar unter
derselben Bedingung (`Utils.hasCameraHardware`). Aufnahme über
`EasyImage.openCameraForImage` — derselbe Weg, den `GalleryActivity` und
`EditWithImageFragmentBase` gehen.

Der Aufruf wird in `try`/`catch (ActivityNotFoundException)` gekapselt mit dem
vorhandenen String `no_camera_app`, wie es `GalleryActivity` seit `a9acbee2` tut.

### Nebenläufigkeit

`lifecycleScope` mit `withContext(Dispatchers.Default)` für die Pipeline, dem
Muster aus `a9acbee2` folgend. Kein `AsyncTask`. Die Erkennung dauert ein bis
drei Sekunden und gehört nicht auf den UI-Thread; währenddessen läuft eine
Fortschrittsanzeige.

### Zustand über den Kamera-Ausflug hinweg

**Dies ist die wichtigste Nebenbedingung der Integration.** Die Kamera-App ist
speicherhungrig; Prozesstod während der Aufnahme ist der Normalfall, nicht die
Ausnahme. `InputActivity.onSaveInstanceState` setzt `data` auf `null` und lädt
nach der Rückkehr aus der Datenbank neu.

Daraus folgt:

1. Der Scan-Zustand darf **nicht** in `data` (`LoaderResult`) gehalten werden.
   Er lebt im `outState` beziehungsweise als Datei auf der Platte.
2. Der Scan trägt die **`endId` der Zielpasse** mit sich. Vor dem Anwenden wird
   geprüft, ob die inzwischen geladene Passe dieselbe ID hat. Passt sie nicht,
   wird **nichts geschrieben** und das Foto lediglich abgelegt.

Ohne Punkt 2 könnten erkannte Treffer nach einem Prozesstod in der falschen
Passe landen. Der zugehörige Fehler in der Positionswiederherstellung ist auf
diesem Branch bereits behoben (`6f1b0b40`), aber die ID-Prüfung bleibt als
zweite Absicherung bestehen — sie kostet nichts und schützt vor Datenverfälschung.

### Anwenden und Korrigieren

Erkannte Treffer sind gewöhnliche `Shot`-Objekte und gehen über
`TargetView.replaceWithEnd(shots, exact = true)` in die Passe. Danach greift der
bestehende Korrekturweg: `selectPreviousShots` und `updateShotToPosition`
erlauben, jeden Treffer anzutippen und zu verschieben — genau wie bei manueller
Eingabe. Es wird **keine neue Korrektur-UI gebaut**.

Dazu eine Snackbar mit dem Ergebnis und einer **Rückgängig**-Aktion, die den
vorherigen Stand der Passe wiederherstellt.

Die Konfidenz steuert intern die Auswahl der besten Kandidaten und den Text der
Snackbar. Sie färbt in v1 nichts ein; eine optische Markierung unsicherer Treffer
ist nachrüstbar, falls sie im Gebrauch vermisst wird.

### Fotoablage

Das **Originalfoto** wird als `EndImage(fileName, endId)` gespeichert — dieselbe
Ablage, die der vorhandene Galerieweg nutzt, mit Cascade-Delete an der Passe.
**Keine Schemaänderung, keine Migration.**

Bewusst das Original und nicht das entzerrte Bild: Liegt die Homographie daneben,
wäre das entzerrte Bild auf dieselbe Weise falsch wie die Treffer. Beides sähe
stimmig aus, obwohl beides verschoben ist — das entzerrte Bild kann den eigenen
Fehler nicht aufdecken. Das Original kann es.

Das Foto wird **unabhängig vom Erkennungserfolg** abgelegt. Schlägt die Erkennung
fehl, lässt sich die Passe später vom Bild abtippen.

### Fehlerfälle

Jeder Fall bekommt eine eigene Antwort, keine Sammelmeldung.

| Fall | Verhalten |
|---|---|
| Auflage nicht gefunden | Nichts wird geschrieben. Meldung mit Ursache, Angebot zur Neuaufnahme. Foto wird gespeichert. |
| Spot-Zahl passt nicht zur Auflage | Hinweis, dass das Bild nicht zur eingestellten Auflage passt, mit deren Namen. |
| Weniger Pfeile als `shotsPerEnd` | Gefundene werden gesetzt, Rest bleibt offen, Snackbar nennt die Zahl. |
| Mehr Kandidaten als `shotsPerEnd` | Die zuversichtlichsten gewinnen, Rest verworfen. |
| Passe hat schon Treffer | Rückfrage vor dem Überschreiben. |
| `endId` passt nicht mehr | Nichts wird geschrieben, Foto wird abgelegt, Hinweis an den Nutzer. |

Leitsatz: **Bei Unsicherheit lieber nichts eintragen als etwas Falsches.** Ein
nicht erkannter Pfeil kostet zwei Sekunden, ein falsch erkannter verfälscht die
Statistik dauerhaft.

## Test und Genauigkeitsmessung

Der schwierige Teil ist nicht, die Pipeline zu schreiben, sondern zu wissen, ob
sie funktioniert. Ohne Messung wird Schwellwert-Tuning zum Blindflug.

### Testkorpus

Echte Fotos mit je einer JSON-Datei, die Auflage und erwartete Trefferkoordinaten
festhält. Bilder auf 1600 px skaliert — mehr verarbeitet Stufe 1 ohnehin nicht.

Abzudecken sind die Fälle, an denen die Pipeline realistisch scheitert:
Hallenlicht und Sonne mit harten Schatten, schräge Winkel, verschiedene
Befiederungsfarben, Pfeile nah am Zentrum (stark verkürzt) und am Rand (lang),
dicht beieinander steckende Pfeile, beide Auflagentypen.

### Kennzahlen

| Metrik | Bedeutung |
|---|---|
| Erkennungsrate | Anteil der Pfeile, die gefunden wurden |
| Falsch-Positive | Erfundene Treffer — teurer als übersehene |
| Ringtreue | Anteil der Treffer mit korrektem `scoringRing`. **Die entscheidende Zahl** |
| Positionsfehler | Median und 95. Perzentil in Scheibenradien |

Diese Werte werden als Regressionsschranke festgeschrieben, **nachdem** sie das
erste Mal gemessen wurden. Eine Zielgenauigkeit vorab festzulegen wäre geraten.

### Zwei Teststufen

Im Modul stecken zwei Arten von Code, und sie brauchen unterschiedliche Verfahren.

**Deterministische Geometrie** — Homographie aus konzentrischen Ellipsen, die
Fluchtpunkt-Regel, Koordinatenumrechnung, die `shotsPerEnd`-Auswahl. Schnelle
Unit-Tests ohne Bild und ohne Android, klassisch testgetrieben entwickelt.

**Wahrnehmungsstufen** — Segmentierung, Residuum, Schaftfindung. Diese lassen
sich nicht sinnvoll rot-grün treiben; sie werden am Korpus gemessen. Das wird
hier festgehalten, damit später niemand rot-grün erwartet, wo es nicht hingehört.

### Debug-Ansicht

Ein Bildschirm im Debug-Build, der ein Foto durch die Pipeline schickt und jede
Stufe als Bild zeigt: Segmentierung, gefittete Ellipsen, entzerrtes Bild,
Residuum, Schaftkandidaten, gewählte Einschusspunkte. Das ist **Pflicht, kein
Extra** — ohne die Ansicht lässt sich ein Fehler nicht lokalisieren, nur erraten.

## Reihenfolge der Umsetzung

1. **Build-Umgebung herstellen.** Auf der Entwicklungsmaschine fehlen derzeit
   JDK und Android SDK. Ohne sie läuft weder Gradle noch ein Test.
2. **Fotos sammeln.** Bevor genug Korpusbilder da sind, ist jede Zeile
   Pipeline-Code unüberprüfbar.
3. Modul `:detection` anlegen, Schnittstelle und Datentypen.
4. Geometrie testgetrieben: Ellipsenfit, Homographie, Fluchtpunkt, Umrechnung.
5. Debug-Ansicht, sobald Stufe 3 ein Bild liefert.
6. Wahrnehmungsstufen gegen den Korpus, Kennzahlen festschreiben.
7. Integration in `InputActivity` samt Fotoablage und Fehlerfällen.

## Offene Risiken

**Drehung um die Scheibenachse.** Konzentrische Kreise legen die Homographie nur
bis auf eine Drehung fest, weil die Auflage rotationssymmetrisch ist. Für die
Trefferbildauswertung ist „links" gegen „oben" aber wesentlich. Bei
Spiegelauflagen lösen die drei Spot-Positionen das; bei der Vollauflage fixiert
die Bildaufrechte die Drehung. Das ist eine **Annahme über die Handhaltung** und
gehört ausdrücklich in den Testkorpus — mit bewusst verkantet aufgenommenen
Bildern.

**APK-Größe.** OpenCV bringt native Bibliotheken mit. Vor der Integration ist zu
messen, wie viel je ABI dazukommt, und zu entscheiden, ob ABI-Splits genügen.

**Unverifizierter Fremdcode.** Die Basis enthält 35 Commits aus einem fremden
Fork, davon breite maschinelle Umbauten. Vier Befunde wurden geprüft, drei
behoben (`79823331`, `6f1b0b40`, `8e3a8b7b`); ein vierter ist dokumentiert und
offen (stiller Datenverlust in `EditRoundFragment.onSaveRound`, wenn
`selectedItem` null ist — `finish()` läuft dort bereits vor dem Speichern). Nicht
alle 163 geänderten Dateien wurden gelesen.

**Die drei Korrekturen sind nicht kompiliert.** Sie entstanden ohne verfügbares
JDK und sind bislang nur sorgfältig gelesen, nicht gebaut und nicht getestet.

## Upgrade-Pfad zu Ansatz C

Ansatz A (klassisches CV) liefert nebenbei, was ein gelerntes Modell brauchte:
entzerrte Bilder in Modellkoordinaten plus die Korrekturen des Nutzers im
`TargetView` sind fertig annotierte Trainingsdaten. Bleibt die Schaftfindung
klassisch zu wackelig, wird sie hinter der `ArrowDetector`-Schnittstelle gegen
einen TFLite-Detektor getauscht — Geometrie und App-Integration bleiben, wie sie
sind. Nichts von der Arbeit an v1 ist dabei verloren.

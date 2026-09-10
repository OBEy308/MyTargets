# Bilderkennung geschossener Pfeile — Design

Datum: 2026-09-09 (überarbeitet nach Review am selben Tag)
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
| Nahaufnahme aus 1–3 m, **leicht schräg** (etwa 20–40° zur Scheibennormalen) | Der Schütze geht ohnehin zum Ziel, um die Pfeile zu ziehen. Aufnahmen von der Schießlinie lösen die Pfeilspitzen nicht auf. Die Schrägstellung ist gewollt: Erst sie macht jeden Schaft zu einem eindeutigen Streifen (siehe Stufe 6). Frontale Aufnahmen funktionieren, sind aber der schwächere Fall. |
| Ein Foto pro Passe, Pfeile stecken noch | Eindeutige Zuordnung ohne Vergleich mit Vorbildern. |
| Auflagen: WA Vollauflage und WA 3-Spot-Auflage (vertikal und Vegas) | Beide sind farblich und geometrisch exakt im Modell beschrieben. Feldbogen und 3D bleiben außen vor. |
| Vollständig offline auf dem Gerät | Bogenplätze haben oft kein Netz. MyTargets ist GPLv2 und F-Droid-freundlich. |
| Kamera wird aufrecht gehalten | Konzentrische Kreise legen die Drehung um die Scheibenachse nicht fest (siehe *Offene Risiken*). |
| Die Auflage darf **angeschnitten** sein | Aus 1–3 m passt eine 122-cm-Auflage oft nicht ganz ins Bild. Die Registrierung darf sich deshalb nicht auf die äußeren Übergänge verlassen, sondern muss aus den innersten zwei sichtbaren Farbübergängen auskommen — bei `WAFull` also Gelb→Rot bei 0.2 und Rot→Blau bei 0.4. Bestätigt an den geerbten Fotos. |
| Bei 3-Spot-Auflagen stecken je Spot höchstens `ceil(shotsPerEnd / faceCount)` Pfeile | Das Datenmodell ordnet Treffer über den Schussindex einem Spot zu. Bei drei Pfeilen auf drei Spots ist das einer pro Spot, bei sechs Pfeilen sind es zwei. Mehr kann das Modell nicht darstellen (siehe *Koordinatensystem*). |

## Umfang

**In v1:**

- Erkennung von Trefferlage (`x`/`y`), Spot und Ringwert (`scoringRing`)
- Eintragen in die laufende Passe über den bestehenden `TargetView`
- Speichern des **unverzerrten Fotos** als `EndImage` der Passe
- Nachscannen eines bereits gespeicherten Passenfotos aus der Galerie
- Fehlerbehandlung mit unterscheidbaren Meldungen
- Debug-Ansicht der Pipelinestufen im Debug-Build
- Testkorpus mit Wahrheitsdaten und vier Kennzahlen

**Ausdrücklich nicht in v1:**

- Erkennung von Pfeilnummern (auf dem Foto meist unlesbar)
- Optische Markierung unsicherer Treffer im `TargetView`
- Feldbogen-, IFAA- und 3D-Auflagen sowie 5- und 6-Spot-Auflagen
- Aufnahmen von der Schießlinie
- Mehrere Passen auf einem Bild

## Architektur

### Modulschnitt

```
:wearable ──> :shared                    (unverändert)
:app ──> :detection ──> :shared          (neu)
```

`:detection` ist ein eigenes Gradle-Modul (Android-Library, weil `Bitmap` und
die Modellklassen aus `:shared` Android-Typen wie `PointF` verwenden). Der
ausschlaggebende Grund für das eigene Modul ist die **Iterationsgeschwindigkeit**:
`:app` umfasst 255 Kotlin-Dateien und 60 Layouts mit kapt, Data Binding,
Navigation-Safe-Args und Crashlytics in der Build-Kette. CV-Arbeit bedeutet
dutzende Durchläufe pro Sitzung; jeder davon hinge sonst am vollständigen
App-Build.

Zweitens erzwingt die Modulgrenze, was sonst Disziplin wäre: In `:app` lägen
Room-DAOs, `SharedPreferences` und `InputActivity` in Reichweite, und die
Erkennung würde über kurz oder lang selbst darauf zugreifen — womit sie ohne
Datenbank nicht mehr testbar wäre.

`:detection` verwendet aus `:shared` ausschließlich `Target` und
`TargetModelBase` (Zonenradien, Farben, `faceRadius`, `facePositions`). Es kennt
weder Room noch UI noch Kontext. `TargetDrawable` wird **nicht** verwendet, siehe
Stufe 4.

Abhängigkeit: OpenCV, ab 4.9 als Maven-Artefakt `org.opencv:opencv` verfügbar
(Apache-2.0, mit GPLv2 verträglich). Die nativen Bibliotheken vergrößern das
APK spürbar; über ABI-Splits beziehungsweise ein App Bundle bekommt der einzelne
Nutzer nur seine Architektur. Die Alternative — Segmentierung, Kegelschnittfit
und Warp in reinem Kotlin — ist machbar und würde APK und F-Droid-Build
einfacher halten. Sie wird bewusst zurückgestellt: Erst wenn die Pipeline am
Korpus funktioniert, lohnt sich die Frage, ob sich der Nachbau der genutzten
Funktionen rechnet. Die Schnittstelle unten macht den Tausch möglich.

### Schnittstelle

```kotlin
interface ArrowDetector {
    fun detect(request: DetectionRequest): DetectionResult
}

data class DetectionRequest(
    val layout: FaceLayout,             // Spot-Anordnung, android-frei aus TargetModelBase
    val zoneRadii: List<Double>,        // Ringradien, spot-lokal
    val expectedShots: Int,             // Round.shotsPerEnd
    val intrinsics: CameraIntrinsics    // Brennweite und Hauptpunkt, siehe Stufe 6
) {
    val maxArrowsPerSpot: Int           // ceil(expectedShots / faceCount), abgeleitet
}

data class DetectionResult(
    val shots: List<DetectedShot>,      // x/y spot-lokal, siehe Koordinatensystem
    val faceConfidence: Float,          // Güte der Auflagenregistrierung
    val reason: SelectionReason?,       // warum die Liste kürzer ist als erwartet
    val failure: DetectionFailure?      // null bei Erfolg
)

data class DetectedShot(
    val faceIndex: Int,                 // Spot, 0 bei Vollauflage
    val x: Float,
    val y: Float,
    val confidence: Float
)

enum class DetectionFailure { FACE_NOT_FOUND, FACE_MISMATCH }
enum class SelectionReason { COMPLETE, FEWER_THAN_EXPECTED, AMBIGUOUS_SURPLUS, SPOT_OVERFLOW }
```

Das Bild selbst kommt über eine Unterschnittstelle mit `Bitmap` dazu, die erst
mit den Wahrnehmungsstufen entsteht. `DetectionRequest` bleibt bewusst frei von
Android-Typen, damit die Verträge in JVM-Tests prüfbar sind. `FaceLayout` und
`CameraIntrinsics` sind die Typen aus dem Geometrieplan
(`docs/plans/2026-09-09-detection-geometry-core.md`), der diese Schnittstelle
festlegt.

Eine Anfrage hinein, eine Trefferliste heraus. Kein Kamerazugriff, kein
Kontext, keine Nebenwirkungen. Genau diese Enge macht die Pipeline gegen einen
Testkorpus prüfbar und erlaubt später den Tausch der Pfeilerkennung (Ansatz C),
ohne die App anzufassen.

### Koordinatensystem

`Shot.x`/`Shot.y` sind **spot-lokale** Koordinaten: Mittelpunkt des Spots `(0,0)`,
äußerster Ring des Spots Radius `1.0`. Bei der Vollauflage ist der Spot die ganze
Auflage (`WAFull.kt`, Zonen bis `1.0`). Bei 3-Spot-Auflagen gilt dasselbe je
Spot: `WA5Ring` definiert seine Zonen ebenfalls bis Radius `1.0`, obwohl der
Spot auf der Gesamtauflage nur `faceRadius = 0.32` groß ist. `faceRadius` und
`facePositions` beschreiben nur die Anordnung der Spots auf der Auflage. Der
Ringwert folgt aus den spot-lokalen Koordinaten über
`TargetModelBase.getZoneFromPoint()`.

**Welcher Spot zu einem Treffer gehört, steht nicht im `Shot`.** Es ergibt sich
aus `shot.index % faceCount` (`TargetImpactDrawable.kt:103`, `TargetView.kt:123`).
Der Schütze schießt Spot 0, 1, 2 der Reihe nach. Daraus folgt für die Pipeline:

1. Stufe 3 entzerrt in **Auflagenkoordinaten** (Vollauflage: Radius `1.0`;
   3-Spot: der Bereich, der alle drei Spots umfasst).
2. Für jeden Kandidaten wird der Spot bestimmt, dessen Kreis um
   `facePositions[i]` mit Radius `faceRadius` den Einschusspunkt enthält. Liegt
   er in keinem, ist der Pfeil neben der Auflage.
3. Die Koordinaten werden **spot-lokal umgerechnet**:
   `(p − facePositions[i]) / faceRadius`. Bei der Vollauflage ist das die
   Identität.
4. Die Integration setzt `Shot.index` so, dass `index % faceCount == faceIndex`
   gilt. Bei drei Pfeilen auf drei Spots ist das eindeutig; bei sechs Pfeilen
   teilen sich Index 0 und 3 den Spot 0.

**Mehr Pfeile in einem Spot, als die Passe dort vorsieht,** kann das Modell
nicht abbilden: Ein Spot fasst `ceil(shotsPerEnd / faceCount)` Treffer, jeder
weitere würde auf dem falschen Spot gezeichnet. In diesem Fall werden pro Spot
nur die zuversichtlichsten Treffer bis zu dieser Grenze gesetzt, der Rest bleibt
offen, und die Snackbar nennt den Grund. Das folgt dem Leitsatz unter
*Fehlerfälle*.

## Die Erkennungspipeline

Der Kern des Ansatzes: Die App muss die Auflage nicht *verstehen* — sie kennt
sie bereits. Ringradien, Farben und Spot-Positionen stehen in den Modellklassen.
Aus einer offenen Erkennungsaufgabe wird damit eine **Registrierung**.

**Stufe 1 — Vorverarbeitung.** EXIF-Rotation anwenden (`androidx.exifinterface`
ist bereits Abhängigkeit). Für die Lokalisierung (Stufe 2) wird eine auf 1600 px
lange Kante verkleinerte Kopie verwendet; nach HSV/Lab wandeln. **Das Warpen in
Stufe 3 liest aus dem Originalbild**, nicht aus der verkleinerten Kopie. Grund:
Eine 40-cm-Auflage aus 3 m mit Handy-Weitwinkel füllt bei 1600 px grob 150 px.
Der Zehner hätte dann etwa 15 px Durchmesser, ein Schaft unter 2 px. Das reicht
nicht.

**Stufe 2 — Auflage lokalisieren.** Gelbe Blobs segmentieren; ihre Anzahl muss zu
`facePositions.size` passen, sonst `FACE_MISMATCH`. An den Farbübergängen mit
bekannten Radien Kegelschnitte fitten (RANSAC gegen Ausreißer durch Schäfte und
Schatten). Die Farbübergänge je Modell:

| Modell | Übergänge (Radius, spot-lokal) |
|---|---|
| `WAFull` | 0.2 gelb→rot, 0.4 rot→blau, 0.6 blau→schwarz, 0.8 schwarz→weiß, 1.0 weiß→Scheibe |
| `WA5Ring` (Vertical/Vegas 3-Spot) | 0.4 gelb→rot, 0.8 rot→blau, 1.0 blau→Scheibe |
| `WA3Ring` (`WA3Ring3Spot`) | 0.666 gelb→rot, 1.0 rot→Scheibe |

Der Übergang bei 1.0 ist auf heller Scheibe (weiß→Scheibe bei `WAFull`) unzuverlässig
und zählt nur als Zusatz. `WA3Ring3Spot` hat je Spot nur zwei brauchbare
Kreise; dort muss die Registrierung aus allen drei Spots zusammen kommen.

**Von Kegelschnitten zur Homographie.** Unter Perspektive sind die Bilder
konzentrischer Kreise weder konzentrisch, noch ist der Mittelpunkt einer
Bildellipse das Bild des Kreiszentrums. Ein naiver Fit "konzentrischer
Ellipsen" baut einen systematischen Fehler ein, der in der Scheibenmitte am
größten ist — genau dort, wo Ringe eng sind. Der Weg ist daher:

1. Im Büschel `C1 − λ·C2` zweier Kegelschnitte desselben Zentrums hat das
   Mitglied zur einfachen Nullstelle von `det(C1 − λ·C2)` Rang 2; sein
   Nullvektor ist das Bild des gemeinsamen Zentrums. Die Polare dieses Punkts
   ist die Fluchtlinie. Daraus folgt die affine und, über den Ellipsenblock,
   die metrische Rektifizierung bis auf Ähnlichkeit (Hartley/Zisserman, Kap. 2
   und 8). Der Weg über die Kreispunkte oder über das Rang-1-Mitglied an der
   Doppelnullstelle ist numerisch fragil und wird nicht verwendet; Begründung
   im Geometrieplan, Task 5.
2. Maßstab und Translation folgen aus den bekannten Radien und dem Bild des
   Zentrums.
3. Die Drehung ist damit noch offen (siehe *Offene Risiken*): Bei der
   Triangel-Auflage legen die drei Spot-Zentren sie vollständig fest. Bei der
   vertikalen Auflage liegen die Zentren auf einer Linie und legen nur die
   Achse fest, nicht oben gegen unten. Dort und bei der Vollauflage entscheidet
   die Bildaufrechte.
4. Alle weiteren Kreise und Spots gehen als Beobachtungen in eine
   Ausgleichung; damit ist die Homographie überbestimmt.

Diese Geometrie ist deterministisch und wird testgetrieben entwickelt (siehe
*Zwei Teststufen*).

**Stufe 3 — Entzerren.** Per Homographie aus dem Originalbild in ein kanonisches
Quadrat warpen, das die Auflage mit Rand abdeckt (Vollauflage `[-1.1, 1.1]²`,
3-Spot entsprechend der Spot-Anordnung). Die Auflösung des Quadrats richtet sich
nach der im Original verfügbaren Pixeldichte, nicht nach einem festen Wert. Ab
hier arbeitet alles in Auflagenkoordinaten.

**Stufe 4 — Farbabgleich.** Eine Referenzmaske derselben Auflösung wird direkt
aus den Zonenradien und `facePositions` gerechnet: Für jedes Pixel ist bekannt,
welche **Farbklasse** (Gelb, Rot, Blau, Schwarz, Weiß) dort liegen muss. Die
Maske wird bewusst nicht mit `TargetDrawable` gezeichnet: Das braucht einen
Android-`Canvas` und wäre in JVM-Tests ohne Robolectric nicht lauffähig. Die
Modellfarben sind außerdem Anzeigefarben, keine Druckfarben; geschätzt wird
deshalb je Klasse die tatsächliche Farbe im Bild, nicht ein Abgleich gegen feste
RGB-Werte. Daraus folgen Belichtung und Weißabgleich, die herausgerechnet werden,
bevor die Segmentierung sie sieht.

**Die Korrektur muss räumlich veränderlich sein, nicht global.** Die geerbten
Fotos zeigen Auflagen, deren eine Hälfte im tiefen Schatten liegt und deren
andere hell ausgeleuchtet ist. Ein einziger Gewinn- und Offsetwert je Kanal
richtet dort nichts aus. Geschätzt wird deshalb ein langsam veränderliches Feld —
je Farbklasse über die Fläche, danach interpoliert. Wie fein es sein muss, sagt
der Korpus.

**Stufe 5 — Pfeile als Residuum.** Referenzklasse vom abgeglichenen Foto
abziehen. Übrig bleiben Schäfte, Befiederung, Schatten und Altlöcher. Reine
Abdunklung ohne Farbtonänderung ist Schatten und wird verworfen. Längliche
Zusammenhangskomponenten sind Schaftkandidaten; kompakte kleine Flecken sind
Altlöcher.

**Stufe 6 — Einschusspunkt.** Der Pfeil steht als Stab zur Kamera hin aus der
Scheibe; im Bild wird daraus ein Streifen. Aus der Fluchtlinie `l` der
Scheibenebene und der Kameramatrix `K` folgt der Fluchtpunkt der
Scheibennormalen, `v = (K·Kᵀ)·l`. `K` wird genähert: quadratische Pixel,
Hauptpunkt in der Bildmitte, Brennweite aus `FocalLengthIn35mmFilm` in EXIF
(`f_px = f35 / 36 · lange Kante`), ohne EXIF `0.75 · lange Kante`, was 27 mm
entspricht.

Ein Punkt `X + t·d` bildet sich ab als `K·X + t·K·d`. Wächst die Tiefe, wandert
das Bild **zum** Fluchtpunkt; bewegt sich der Punkt zur Kamera hin, wandert es
**vom Fluchtpunkt weg** und geht gegen unendlich, sobald die Tiefe null erreicht.
Der Nock steht zur Kamera hin aus der Scheibe, liegt also näher an der Kamera als
das Einschussloch. Also gilt:

> **Der Einschusspunkt ist das dem Fluchtpunkt nähere Ende des Streifens.**

Das deckt sich damit, dass Pfeile auf Scheibenfotos nach außen zu spreizen
scheinen: Was nach außen zeigt, sind die Nocks — sie liegen vom Fluchtpunkt
weiter entfernt. Damit ist die Zuordnung berechenbar statt heuristisch.

*Korrektur vom 2026-09-09.* Bis zur Gesamtprüfung des Geometrieplans stand hier
die umgekehrte Regel, und der Absatz über das Spreizen widerlegte sie bereits.
Der Plan hat die falsche Regel übernommen, und ihr Test hat sie wiederholt statt
sie zu prüfen — er wäre unter beiden Vorzeichen grün gewesen. Gefunden wurde es
erst durch eine Simulation mit echter Lochkamera-Projektion.

Zwei Einschränkungen, die der Spec ausdrücklich behandelt:

- **Unsicherer Fluchtpunkt bei frontaler Aufnahme.** Bei gegebener Fluchtlinie
  ist der Fluchtpunkt eindeutig, es gibt keine Vorzeichenwahl. Unsicher ist die
  *Entfernung* der Fluchtlinie: Je frontaler die Aufnahme, desto weiter liegt
  sie weg und desto schlechter ist sie aus den Ringen bestimmt. Der Fluchtpunkt
  liegt dann nahe dem Hauptpunkt, seine genaue Lage wackelt, und die Regel oben
  kippt für Pfeile nahe der Mitte. Absicherung: Alle Streifen zeigen vom selben
  Fluchtpunkt weg. Ist die Mehrheit der Streifen mit dem gerechneten Punkt
  inkonsistent, wird der Fluchtpunkt aus den Streifen selbst geschätzt oder die
  Zuordnung als unsicher gemeldet. Als zweites, unabhängiges Merkmal dient das
  Befiederungsende: Es ist farbig und dicker als der Schaft und muss am
  **nahen** Ende liegen.
- **Frontale Aufnahme, Pfeil nahe der Mitte.** Ein Pfeil nahe dem Fluchtpunkt
  bildet sich als kurzer Streifen oder Punkt ab und ist dann von einem Altloch
  kaum zu unterscheiden — ausgerechnet im Zehner, wo jeder Millimeter zählt.
  Deshalb die Annahme *leicht schräg*: Sie macht jeden Schaft lang. Der Korpus
  enthält frontale Mittentreffer als bekannten Schwachpunkt, damit die
  Kennzahlen ihn sichtbar machen.

**Stufe 7 — Auswahl und Ausgabe.** Spot je Kandidat bestimmen und spot-lokal
umrechnen (siehe *Koordinatensystem*). `Round.shotsPerEnd` ist ein starker
Filter: Bei weniger Kandidaten meldet die Pipeline die Lücke, statt zu raten.
Bei mehr Kandidaten gewinnen die zuversichtlichsten, **aber nur mit Abstand**:
Angenommen wird das größte `k ≤ shotsPerEnd`, bei dem Kandidat `k` deutlich
über Kandidat `k+1` liegt. Liegen alle Kandidaten dicht beieinander, bleibt die
ganze Passe offen, denn der erste Platz ist dann genauso ein Münzwurf wie der
letzte. Ringwert über `getZoneFromPoint()`.

### Bekannte Grenzen der Pipeline

- **Pfeile neben der Auflage** sind nicht registrierbar und werden nicht erkannt.
  Sie werden von Hand nachgetragen.
- **Zwei Pfeile im selben Loch** ergeben einen Streifen und damit einen Treffer.
- **Stark überlappende Schäfte** können zu einem Kandidaten verschmelzen.
- **Mehr Pfeile in einem Spot, als die Passe dort vorsieht:** nur die
  zuversichtlichsten bis zur Grenze werden gesetzt (siehe *Koordinatensystem*).

## Integration in die App

### Einstiegspunkte

**Aus der Eingabe.** Ein zweiter Menüpunkt in `input_end.xml` neben
`action_photo`, sichtbar unter derselben Bedingung (`Utils.hasCameraHardware`).
Das Menü zeigt bereits vier Icons mit `ifRoom`; der neue Eintrag bekommt
`showAsAction="never"` und landet im Überlauf, statt auf Telefonen ein
bestehendes Icon zu verdrängen. Aufnahme über `EasyImage.openCameraForImage` —
derselbe Weg, den `GalleryActivity` und `EditWithImageFragmentBase` gehen.
`InputActivity` übernimmt dafür `EasyImage.handleActivityResult`.

Der Aufruf wird in `try`/`catch (ActivityNotFoundException)` gekapselt mit dem
vorhandenen String `no_camera_app`, wie es `GalleryActivity` seit `a9acbee2` tut.

**Aus der Galerie.** Ein Passenfoto, das bereits als `EndImage` gespeichert ist,
lässt sich aus `GalleryActivity` nachscannen. Das ist der Weg, wenn die
Erkennung am Platz fehlgeschlagen ist oder das Foto ohne Scan gemacht wurde, und
im Debug-Build der natürliche Ort für die Debug-Ansicht. Das Ergebnis geht
denselben Weg wie aus der Eingabe (`endId`-Prüfung, Rückfrage bei vorhandenen
Treffern).

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
   Er lebt als *pending scan* (Fotopfad plus `endId` der Zielpasse) im
   `outState`. EasyImage merkt sich den Fotopfad seinerseits in
   `SharedPreferences` und übersteht den Prozesstod.
2. Nach Prozesstod kommt `onActivityResult` **vor** dem Loader. Der pending scan
   wird deshalb nur abgelegt und erst verbraucht, wenn der Loader `data`
   geliefert hat. Erst dann läuft die Erkennung.
3. Vor dem Anwenden wird geprüft, ob die inzwischen geladene Passe dieselbe
   `endId` hat. Passt sie nicht, wird **nichts geschrieben** und das Foto
   lediglich abgelegt.

Ohne Punkt 3 könnten erkannte Treffer nach einem Prozesstod in der falschen
Passe landen. Der zugehörige Fehler in der Positionswiederherstellung ist auf
diesem Branch bereits behoben (`6f1b0b40`), aber die ID-Prüfung bleibt als
zweite Absicherung bestehen — sie kostet nichts und schützt vor Datenverfälschung.

### Anwenden und Korrigieren

Erkannte Treffer sind gewöhnliche `Shot`-Objekte mit `index`, `endId`, `x`, `y`
und `scoringRing`. Die Liste hat immer `shotsPerEnd` Einträge; nicht erkannte
Plätze bleiben `NOTHING_SELECTED`. Sie geht über
`TargetView.replaceWithEnd(shots, exact = true)` in die Passe.

`TargetView.replaceWithEnd` (`TargetView.kt:164`) wählt bei gesetzten Ringwerten
`SettingsManager.inputMethod`. Steht der Nutzer auf Tastatureingabe, landete er
nach dem Scan im Tastaturmodus, wo sich Treffer nicht verschieben lassen.
Deshalb wird nach einem Scan **explizit Plotting** aktiviert. Danach greift der
bestehende Korrekturweg: `selectPreviousShots` und `updateShotToPosition`
erlauben, jeden Treffer anzutippen und zu verschieben — genau wie bei manueller
Eingabe. Es wird **keine neue Korrektur-UI gebaut**.

Dazu eine Snackbar mit dem Ergebnis und einer **Rückgängig**-Aktion. Da die
Eingabe die Passe fortlaufend speichert, stellt Rückgängig den vorherigen Stand
nicht nur in der View, sondern über denselben Speicherweg **auch in der
Datenbank** wieder her.

Die Konfidenz steuert intern die Auswahl der besten Kandidaten und den Text der
Snackbar. Sie färbt in v1 nichts ein; eine optische Markierung unsicherer Treffer
ist nachrüstbar, falls sie im Gebrauch vermisst wird.

### Fotoablage

Das Foto wird als `EndImage(fileName, endId)` gespeichert — dieselbe Ablage,
die der vorhandene Galerieweg nutzt (`filesDir`, Cascade-Delete an der Passe).
**Keine Schemaänderung, keine Migration.**

Gespeichert wird das **unverzerrte** Bild, nicht das entzerrte: Liegt die
Homographie daneben, wäre das entzerrte Bild auf dieselbe Weise falsch wie die
Treffer. Beides sähe stimmig aus, obwohl beides verschoben ist — das entzerrte
Bild kann den eigenen Fehler nicht aufdecken. Das unverzerrte kann es.

Dafür braucht es nicht die volle Kameraauflösung. Originale moderner Handys
haben 3 bis 12 MB; pro Passe gespeichert wäre das für Speicher und Backup zu
viel. Das Bild wird auf **2048 px lange Kante** verkleinert abgelegt; das
Original dient nur der Erkennung und wird danach gelöscht.

Das Foto wird **unabhängig vom Erkennungserfolg** abgelegt. Schlägt die Erkennung
fehl, lässt sich die Passe später vom Bild abtippen oder aus der Galerie
nachscannen.

### Fehlerfälle

Jeder Fall bekommt eine eigene Antwort, keine Sammelmeldung.

| Fall | Verhalten |
|---|---|
| Auflage nicht gefunden | Nichts wird geschrieben. Meldung mit Ursache, Angebot zur Neuaufnahme. Foto wird gespeichert. |
| Spot-Zahl passt nicht zur Auflage | Hinweis, dass das Bild nicht zur eingestellten Auflage passt, mit deren Namen. |
| Weniger Pfeile als `shotsPerEnd` | Gefundene werden gesetzt, Rest bleibt offen, Snackbar nennt die Zahl. |
| Mehr Kandidaten als `shotsPerEnd` | Die zuversichtlichsten gewinnen, sofern der Abstand zum nächsten Kandidaten deutlich ist; sonst bleiben die unsicheren Plätze offen, notfalls alle. |
| Mehr Pfeile in einem Spot als vorgesehen (3-Spot) | Nur die zuversichtlichsten bis zur Grenze `ceil(shotsPerEnd / faceCount)` werden gesetzt, Snackbar nennt den Grund. |
| Passe hat schon Treffer | Rückfrage vor dem Überschreiben. |
| `endId` passt nicht mehr | Nichts wird geschrieben, Foto wird abgelegt, Hinweis an den Nutzer. |

Leitsatz: **Bei Unsicherheit lieber nichts eintragen als etwas Falsches.** Ein
nicht erkannter Pfeil kostet zwei Sekunden, ein falsch erkannter verfälscht die
Statistik dauerhaft.

## Test und Genauigkeitsmessung

Der schwierige Teil ist nicht, die Pipeline zu schreiben, sondern zu wissen, ob
sie funktioniert. Ohne Messung wird Schwellwert-Tuning zum Blindflug.

### Testkorpus

Echte Fotos in **Originalauflösung** mit je einer JSON-Datei, die Auflage und
erwartete Treffer festhält. Die Wahrheitsdaten verwenden dasselbe Format wie
`Shot`: `faceIndex`, spot-lokale `x`/`y`, `scoringRing`. So lassen sich
korrigierte Passen aus der App direkt als Korpuseinträge exportieren, und der
Korpus wächst mit dem Gebrauch.

**Teilwahrheit ist zulässig.** Ein Eintrag darf die Ringwerte ohne Positionen
angeben. Er zählt dann für Erkennungsrate, Falsch-Positive und Ringtreue, aber
nicht für den Positionsfehler. Ohne diese Regel wären die geerbten Fotos unten
erst nach vollständiger Nachannotation brauchbar, und die drei Kennzahlen, die
sie schon jetzt tragen können, blieben ungemessen.

**`positionTolerance` ist Annotationsunsicherheit, kein Erkennerbudget.** Der
Wert je Treffer gibt an, wie genau die Annotation den Einschusspunkt platzieren
konnte — nicht, wie ungenau der Erkenner sein darf. Das Zuordnungskriterium im
Modul ist deshalb das eigene Budget des Erkenners (0,05 Spot-Radien) **zuzüglich**
dieser Annotationstoleranz des jeweiligen Treffers, nicht die Toleranz allein.
Eine Vermischung beider Werte hat schon einmal eine Erkennungsrate von 2,5 %
für einen auf vier Millimeter genauen Erkenner erzeugt.

**16 geerbte Fotos.** Der Branch `feature/249_auto_detect_arrows` (Florian
Dreier, Dezember 2017, nie gemergt) enthält einen OpenCV-Prototyp derselben
Funktion samt 16 Testfotos, rund 40 MB. Die Wahrheit steckt im Dateinamen:
`a6_998877` sind sechs Pfeile mit 9,9,8,8,7,7, `a8_xxx99988_front` acht Pfeile
mit drei X, frontal. Die Marker `dark`, `noise`, `overlap`, `front` und
`multiple_targets` benennen die jeweilige Erschwernis.

Sie liegen unter `<DETECTION_CORPUS_DIR>/inherited-249/`, nicht im Repo — im
Prototyp lagen sie in `res/drawable-nodpi` und damit im APK, was dort ProGuard
schon für Debug-Builds nötig machte.

**Zwei Arten von Ringwert, und eine bindende Anforderung an Plan 3.** Die 16
geerbten Fotos tragen ihre Wahrheit als **gedruckte Ringwert-Zeichen** (`X`,
`9`, `M`, aus dem Dateinamen). Die 4 bisher annotierten Fotos tragen ihre
Wahrheit als **Zonenindex**. Der Vergleich verlangt auf beiden Seiten dieselbe
Art von Ringwert (siehe *Kennzahlen*). Ein Erkenner, der nur Zonenindizes
ausgibt, kann deshalb gegen die 100 geerbten Treffer nie zugeordnet werden:
Erkennungsrate ist auf 16,7 % gedeckelt, Falsch-Positiv-Rate auf 83 % nach
unten begrenzt — dauerhaft, unabhängig davon, wie gut der Erkenner ist. Die
Umrechnung von Zonenindex auf gedruckten Wert ist bewusst nicht Teil dieser
Spec, weil sie die Zonentabellen aus `:shared` braucht, die erst Plan 3
bereitstellt. Das ist eine **bindende Anforderung an den Plan-3-Runner**: Jeder
erkannte Treffer muss den gedruckten Wert zusätzlich zum Zonenindex tragen,
umgerechnet über das Zielscheibenmodell des Eintrags — sonst lassen sich die
geerbten Fotos nicht zuordnen. Wer auf eine Erkennungsrate von 16,7 % oder eine
Falsch-Positiv-Rate von 83 % stößt, sollte hier nachsehen, bevor er den
Erkenner verdächtigt.

Abzudecken sind die Fälle, an denen die Pipeline realistisch scheitert:
Hallenlicht und Sonne mit harten Schatten, frontale und schräge Winkel,
bewusst verkantete Aufnahmen, verschiedene Befiederungsfarben, Pfeile nah am
Zentrum (stark verkürzt) und am Rand (lang), dicht beieinander steckende
Pfeile, viele Altlöcher, kleine Auflage aus großer Distanz, alle drei
Auflagenformen: Vollauflage (`WAFull`), 3-Spot vertikal (`WAVertical3Spot`)
und 3-Spot Triangel (`WAVegas3Spot`, `WA3Ring3Spot`).

### Kennzahlen

| Metrik | Bedeutung |
|---|---|
| Erkennungsrate | Anteil der Pfeile, die gefunden wurden |
| Falsch-Positive | Erfundene Treffer — teurer als übersehene |
| Ringtreue | Anteil der **zugeordneten** Treffer mit korrektem Spot **und** `scoringRing`, gemessen unter den zugeordneten Treffern, deren Wahrheit und Erkennung eine vergleichbare Art von Ringwert tragen (siehe oben, *Zwei Arten von Ringwert*). **Die entscheidende Zahl** |
| Positionsfehler | Median und 95. Perzentil in Spot-Radien |

Ringtreue zählt damit nicht über alle geschossenen Pfeile, sondern nur über die,
bei denen überhaupt etwas zugeordnet wurde und bei denen Wahrheit und Erkennung
vergleichbar sind — alles andere hieße, einen übersehenen Pfeil als falschen
Ring zu zählen, was eine Lüge wäre. Der Nenner kann dadurch sehr klein werden:
Gemessen am jetzigen Korpus liegt die Ringtreue bei 100 %, während die
Erkennungsrate bei 0,8 % liegt — ein Erkenner, der einen von hundertzwanzig
Pfeilen gefunden und richtig geringt hat. **Die Ringtreue ist ohne ihren Nenner
und ohne die Erkennungsrate daneben bedeutungslos und darf nie ohne beide
zitiert oder abgebildet werden.**

Diese Werte werden als Regressionsschranke festgeschrieben, **nachdem** sie das
erste Mal gemessen wurden. Eine Zielgenauigkeit vorab festzulegen wäre geraten.

### Zwei Teststufen

Im Modul stecken zwei Arten von Code, und sie brauchen unterschiedliche Verfahren.

**Deterministische Geometrie** — Kegelschnittfit, Zentrum und Fluchtlinie aus
dem Büschel, Rektifizierung, Fluchtpunkt, Spot-Zuordnung und spot-lokale
Umrechnung, die `shotsPerEnd`-Auswahl mit Abstandsregel. Schnelle JVM-Unit-Tests
ohne Bild, klassisch testgetrieben entwickelt. Die Tests verwenden synthetische
Eingaben: bekannte Homographie auf bekannte Kreise anwenden, Rückrechnung
prüfen — exakt **und mit Pixelrauschen**, weil exakte Tests einen numerisch
fragilen Rechenweg nicht von einem robusten unterscheiden können. Android-Typen
wie `PointF` bleiben aus dem Geometriecode heraus; das Modul führt eigene
Vektortypen und ein eigenes `FaceLayout`, die Umwandlung aus `TargetModelBase`
liegt in der App.

**Wahrnehmungsstufen** — Segmentierung, Farbabgleich, Residuum, Schaftfindung.
Diese lassen sich nicht sinnvoll rot-grün treiben; sie werden am Korpus
gemessen. OpenCV braucht dafür native Bibliotheken, also laufen diese Messungen
als Instrumentierungstests auf dem Gerät oder Emulator, nicht als
JVM-Unit-Tests. Ein eigenes JVM-Werkzeug dafür gibt es im Projekt nicht;
`tools/` enthält nur Gradle-Skripte. Ob sich ein solches Modul lohnt,
entscheidet Plan 2. Das wird hier festgehalten, damit später niemand rot-grün
erwartet, wo es nicht hingehört.

### Debug-Ansicht

Ein Bildschirm im Debug-Build, erreichbar aus der Galerie, der ein Foto durch
die Pipeline schickt und jede Stufe als Bild zeigt: Segmentierung, gefittete
Kegelschnitte, entzerrtes Bild, Farbklassenmaske, Residuum, Schaftkandidaten,
gerechneter Fluchtpunkt samt Streifenrichtungen, gewählte Einschusspunkte mit
Spot. Das ist
**Pflicht, kein Extra** — ohne die Ansicht lässt sich ein Fehler nicht
lokalisieren, nur erraten.

## Reihenfolge der Umsetzung

1. ~~**Build-Umgebung herstellen.**~~ Erledigt: Android Studio mit JBR und SDK.
   Die drei lokal beizusteuernden Dateien sind in `BUILDING.md` beschrieben.
2. **Fotos sammeln.** 16 geerbte Fotos liegen bereits unter
   `<DETECTION_CORPUS_DIR>/inherited-249/`, mit Ringwerten im Dateinamen und
   ohne Positionen. Sie decken dunkel, verrauscht, überlappend, frontal und
   mehrere Auflagen ab — nicht aber die von dieser Spec bevorzugte leicht
   schräge Aufnahme, verkantete Bilder oder 3-Spot-Auflagen. Eigene Fotos
   ergänzen genau diese Lücken; Originalauflösung behalten.
3. **APK-Zuwachs durch OpenCV messen** (siehe *Offene Risiken*), bevor die
   Abhängigkeit festgezurrt wird.
4. Modul `:detection` anlegen, Schnittstelle und Datentypen.
5. Geometrie testgetrieben: Kegelschnittfit, Zentrum und Fluchtlinie,
   Rektifizierung, Fluchtpunkt, Spot-Zuordnung, Umrechnung, Auswahl. Plan:
   `docs/plans/2026-09-09-detection-geometry-core.md`.
6. Debug-Ansicht, sobald Stufe 3 ein Bild liefert.
7. Wahrnehmungsstufen gegen den Korpus, Kennzahlen festschreiben.
8. Integration in `InputActivity` und `GalleryActivity` samt Fotoablage,
   pending scan und Fehlerfällen.

## Offene Risiken

**Drehung um die Scheibenachse.** Konzentrische Kreise legen die Homographie nur
bis auf eine Drehung fest, weil die Auflage rotationssymmetrisch ist. Für die
Trefferbildauswertung ist „links" gegen „oben" aber wesentlich. Bei der
Triangel-Auflage lösen die drei Spot-Zentren das vollständig. Bei der
vertikalen 3-Spot-Auflage liegen die Zentren auf einer Linie; das legt die
Achse fest, aber nicht, welcher Spot der obere ist, und damit hängt die
Zuordnung zu Spot 0 bis 2 an der Bildaufrechten. Bei der Vollauflage fixiert
die Bildaufrechte die Drehung ganz. Das ist eine **Annahme über die
Handhaltung** und gehört ausdrücklich in den Testkorpus — mit bewusst verkantet
aufgenommenen Bildern der Vollauflage und der vertikalen Auflage.

**Unsicherer Fluchtpunkt.** Die Konsistenzprüfung der Streifen (Stufe 6) ist
bei einem einzelnen Pfeil nahe der Mitte keine Prüfung. Dann entscheidet allein
das Befiederungsmerkmal. Ob das reicht, zeigt der Korpus.

**Der Prototyp von 2017 als Vorarbeit.** `feature/249_auto_detect_arrows` löst
dieselbe Aufgabe mit OpenCV 3.1 in Java. Zwei Befunde daraus gehören
festgehalten.

Erstens bestätigt er Stufe 6: Er steht vor derselben Frage, welches Schaftende
der Einschuss ist, und beantwortet sie mit einem Boolean
`isFromLeftViewpoint()`, das den linkesten oder rechtesten Endpunkt wählt. Das
ist unsere Regel auf eine Achse reduziert und versagt bei Aufnahmen von schräg
oben oder unten. Der Fluchtpunkt behandelt jede Kipprichtung.

Zweitens bestätigt er die Warnung in Stufe 2: Seine `PerspectiveDetection`
fittet je Farbzone eine Ellipse und legt dann eine **lineare Regression durch
die Ellipsenmittelpunkte über dem Radius**, extrapoliert auf Radius 0. Das ist
genau ein Pflaster für die Drift, die dieser Abschnitt beschreibt — eine
Näherung erster Ordnung, wo unser Weg über Büschel und Kreispunkte exakt rechnet.
Dass jemand dieses Pflaster brauchte, ist der beste Beleg, dass der Fehler real
ist.

Zu übernehmen wäre `ColorUtils.getDistinctColorTargetZones` und `getColorMask` —
die Abbildung vom `Target`-Modell auf Farbmasken, die Stufe 2 und 4 brauchen.
Der Code selbst ist von 2017, nutzt `android.support.*` und die längst
verschwundene Maven-Koordinate `org.opencv:OpenCV-Android:3.1.0`; er baut heute
nicht. Als Referenz taugt er, als Grundlage nicht. Seine beiden
Pfeilerkennungs-Strategien sind die Messlatte: Schlägt unsere klassische
Pipeline einen acht Jahre alten Prototyp nicht, ist das ein Signal.

**Zwei Fälle im geerbten Korpus, die außerhalb des Umfangs liegen.** Ein Foto
zeigt drei Auflagen nebeneinander (`multiple_targets`) — die Pipeline nimmt eine
an; hier muss sie sauber `FACE_MISMATCH` melden statt eine beliebige zu wählen.
Und mindestens eines zeigt eine **FITA 80 cm 6-Ring**-Auflage (`WA6Ring`), die
v1 nicht abdeckt. Welche Auflage je Foto zu sehen ist, klärt sich bei der
Annotation; Fotos außerhalb des Umfangs bleiben im Korpus, aber außerhalb der
Kennzahlen.

Dafür gibt es inzwischen einen Mechanismus: `out-of-scope.json` im
Korpus-Wurzelverzeichnis führt solche Fotos auf, Dateiname auf Begründung
(siehe die `README.md` des Korpus, Abschnitt *Fotos außerhalb des Umfangs*).
Ein dort eingetragenes Foto wird weiterhin geladen und bleibt für
Registrierungstests verfügbar; es zählt nur für keine Trefferkennzahl.
`multiple_targets` lässt sich so bereits eintragen. Der `WA6Ring`-Fall lässt
sich erst eintragen, wenn die Annotation geklärt hat, welches Foto es ist.

**APK-Größe.** OpenCV bringt native Bibliotheken mit. Vor der Integration ist zu
messen, wie viel je ABI dazukommt, und zu entscheiden, ob ABI-Splits genügen
oder ob die Kotlin-Alternative aus *Modulschnitt* doch vorzuziehen ist.

**Unverifizierter Fremdcode.** Die Basis enthält 35 Commits aus einem fremden
Fork, davon breite maschinelle Umbauten. Vier Befunde wurden geprüft, drei
behoben (`79823331`, `6f1b0b40`, `8e3a8b7b`). Der vierte — stiller Datenverlust
in `EditRoundFragment.onSaveRound`, wenn `selectedItem` null ist, weil
`finish()` in `onSave` vor dem Speichern läuft — ist unabhängig von dieser
Funktion und wird als eigenes Ticket geführt. Nicht alle 163 geänderten Dateien
wurden gelesen.

**Die drei Korrekturen sind kompiliert, aber nicht zur Laufzeit geprüft.**
`:app:assembleDevDebug` läuft durch und erzeugt ein APK. Das belegt, dass sie
übersetzen — nicht, dass sie sich richtig verhalten. `Migration27` an einer
echten Altdatenbank und die Positionswiederherstellung nach Prozesstod sind noch
auf dem Gerät nachzustellen.

**Die Kameramatrix ist eine Näherung.** Stufe 6 braucht `K`; der Plan führt sie
als expliziten Parameter mit quadratischen Pixeln, Hauptpunkt in der Bildmitte
und Brennweite aus EXIF, Rückfall `0.75 · lange Kante`. Fehlt EXIF und weicht
das Objektiv stark von 27 mm ab (Ultraweitwinkel, Tele), liegt der Fluchtpunkt
entsprechend daneben. Wie stark die Näherung trägt, muss der Korpus zeigen.

## Upgrade-Pfad zu Ansatz C

Ansatz A (klassisches CV) liefert nebenbei, was ein gelerntes Modell brauchte:
entzerrte Bilder in Auflagenkoordinaten plus die Korrekturen des Nutzers im
`TargetView` sind fertig annotierte Trainingsdaten. Bleibt die Schaftfindung
klassisch zu wackelig, wird sie hinter der `ArrowDetector`-Schnittstelle gegen
einen TFLite-Detektor getauscht — Geometrie und App-Integration bleiben, wie sie
sind. Nichts von der Arbeit an v1 ist dabei verloren.

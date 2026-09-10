# Registrierung aus dem Bild — Design (Plan 3a)

Datum: 2026-09-10
Status: abgestimmt, Grundlage für Plan 3a
Bezug: `docs/design/2026-09-09-arrow-detection-design.md` (im Folgenden
*Haupt-Spec*), Stufen 1 bis 3

## Ziel

Die Pipeline findet in einem Foto die Auflage und rechnet die Homographie vom
Originalbild in Auflagenkoordinaten aus: die Registrierung aus den Stufen 1 bis
3 der Haupt-Spec. Gemessen wird sie am Korpus, dessen Sidecars für jedes Foto
eine Referenz-Homographie tragen (`registration.imageToTarget`). Die
Registrierung lässt sich damit für sich prüfen, bevor es einen einzigen Pfeil
gibt.

Plan 3 ist dafür geteilt: **3a** registriert (dieses Dokument), **3b** findet
Pfeile (Stufen 4 bis 6) und baut auf einer gemessenen Registrierung auf. Jeder
Teil bekommt eigenes Design, eigenen Plan und eigenen PR. Im Gesamtplan wären
Registrierungs- und Pfeilfehler erst am Ende und nur vermischt sichtbar.

**Fertig ist 3a,** wenn alle 16 Fotos im Umfang durch die Registrierung laufen
und der Fehler gegen die Referenz je Foto und gesamt berichtet wird, mit
benannten Fehlschlägen. Schranken werden erst an diesen Zahlen festgelegt, wie
die Haupt-Spec es für alle Kennzahlen verlangt.

## Umfang

**In 3a:**

- Stufen 1 bis 3 für `WAFull`
- `FACE_MISMATCH` für das Foto mit drei Auflagen
  (`a6_x99999_multiple_targets.jpg`), wie die Haupt-Spec es verlangt
- Registrierungsfehler als Kennzahl in `:detection-corpus`
- Korpuslauf mit Bericht und Debug-Bildern je Stufe

**Nicht in 3a:**

- Pfeile, Farbabgleich über die Fläche, Residuum (Plan 3b)
- 3-Spot-Auflagen: Die Übergangstabelle ist Daten, gemessen wird aber nur, wofür
  es Fotos gibt
- Registrierung von `WA6Ring`; die drei einzelnen WA6Ring-Fotos werden nur
  berichtet
- Alles in `:app`: das OpenCV-AAR, die Umwandlung `Bitmap` → `Mat`, der
  Debug-Bildschirm (Integrationsplan)
- Eine Formel für `faceConfidence` (siehe *Offene Punkte*)

## Entscheidungen

| Frage | Entscheidung | Grund |
|---|---|---|
| Umfang von Plan 3 | 3a Registrierung, 3b Pfeile | Die Registrierung hat im Korpus eine eigene Wahrheit; die Fehlerquellen bleiben getrennt. |
| Ziel | messen, nicht schranken | Haupt-Spec: erst messen, dann festschreiben. |
| Auflagen | nur `WAFull` | Nur dafür gibt es Fotos im Umfang. |
| Debug-Ansicht | Bilder aus dem Korpuslauf | Der Bildschirm in der App setzt die Integration voraus. |
| Laufort | OpenCV-Java-API, Tests auf der Desktop-JVM | Probe am 2026-09-10: 12-MP-Foto in 0,1 s geladen, ein Korpuslauf dauert Sekunden. Kein Emulator eingerichtet. |
| OpenCV-Version | App 4.14.0, Tests 4.9.0 (`org.openpnp:opencv`) | openpnp endet bei 4.9; die Entscheidung für die App (Haupt-Spec, *APK-Größe*) bleibt. |

## Aufbau

### Module

- **`:detection`** bekommt das Paket
  `de.dreier.mytargets.detection.registration`. Der Hauptcode kompiliert per
  `compileOnly` gegen `org.opencv:opencv:4.14.0`, also gegen das Artefakt, das
  die App ausliefert. Lehnt AGP das AAR als `compileOnly` ab, kompiliert er
  gegen `org.openpnp:opencv:4.9.0-0`. Die Tests laufen mit
  `testImplementation org.openpnp:opencv:4.9.0-0`, das die nativen
  Bibliotheken für Windows, Linux und macOS mitbringt. Nutzt der Code eine API,
  die 4.9 nicht kennt, fällt das im Test auf. Dazu kommt
  `testImplementation project(':detection-corpus')`.
- Die Geometrie in `geometry/` bleibt reines Kotlin. Die Registrierung übersetzt
  an genau einer Stelle zwischen OpenCV-`Mat` und `Vec2`/`Mat3`.
- **`:detection-corpus`** bleibt ohne Bildverarbeitung und bekommt
  `RegistrationError` (siehe *Messung*).
- **`:app`** bleibt unberührt.

### Schnittstelle

```kotlin
enum class ColourClass { YELLOW, RED, BLUE, BLACK, WHITE, OTHER }

/** A colour transition at a known spot-local radius. */
class RingTransition(
    val radius: Double,
    val inside: ColourClass,
    val outside: ColourClass
)

class RegistrationRequest(
    val layout: FaceLayout,
    val transitions: List<RingTransition>
)

class RingFit(
    val radius: Double,
    val conic: Conic,
    val points: Int,
    val radialRms: Double
)

sealed interface RegistrationOutcome {
    class Registered(
        val imageToTarget: Mat3,
        val imagedCentre: Vec2,
        val rings: List<RingFit>
    ) : RegistrationOutcome

    class Failed(
        val failure: DetectionFailure,
        val detail: String
    ) : RegistrationOutcome
}

interface FaceRegistrar {
    fun register(
        image: Mat,
        request: RegistrationRequest,
        debug: DebugSink = DebugSink.NONE
    ): RegistrationOutcome
}

fun interface DebugSink {
    fun image(stage: String, image: Mat)

    companion object {
        val NONE = DebugSink { _, _ -> }
    }
}
```

- `image` ist das Original als 8-Bit-BGR-`Mat`, bereits nach EXIF gedreht. Auf
  dem Desktop leistet das `Imgcodecs.imread`, in der App später die Umwandlung
  aus `Bitmap`.
- `imageToTarget` bildet Pixel dieses Bildes auf spot-lokale
  Auflagenkoordinaten ab, dieselbe Konvention wie `registration.imageToTarget`
  im Sidecar. `imagedCentre` ist das Bild des Scheibenzentrums in denselben
  Pixeln.
- `RingTransitions.WA_FULL` steht in `:detection` und entspricht der Tabelle
  der Haupt-Spec: 0,2 Gelb→Rot, 0,4 Rot→Blau, 0,6 Blau→Schwarz, 0,8
  Schwarz→Weiß. Der Übergang bei 1,0 fehlt, weil die Haupt-Spec ihn auf heller
  Scheibe unzuverlässig nennt. Die Übersetzung aus `TargetModelBase` gehört zur
  Integration.
- `DebugSink.NONE` tut nichts; der Korpuslauf schreibt die Bilder als PNG.
- `ArrowDetector` bleibt unverändert. Die Unterschnittstelle mit Bild entsteht
  in 3b, wenn Registrierung und Pfeilfindung zusammenkommen.

## Verfahren

Grundlage ist `tools/register.py` im Korpus. Es hat genau diese Fotos
registriert, und die Referenz in den Sidecars stammt daraus. Übernommen wird
das Verfahren, nicht der Code. Seine Grenzwerte gelten für Bilder mit 2000 px
langer Kante und werden auf 1600 px umgerechnet.

1. **Vorverarbeitung.** Die lange Kante wird mit `INTER_AREA` auf 1600 px
   verkleinert, vergrößert wird nie. Danach HSV per `cvtColor`.
2. **Farbklassen.** Gelb und Rot über Farbton-Bänder mit Mindestsättigung.
   Blau, Schwarz und Weiß über ein k-means in Sättigung und auf die
   Bildhelligkeit normierter Helligkeit, mit festen Startwerten und in Kotlin,
   damit das Ergebnis deterministisch ist. Ein zweiter Durchgang nimmt die
   Schwellen für Gelb und Rot aus der gefundenen Scheibe selbst.
3. **Gelbe Scheiben.** Dichtespitzen der Gelbklasse auf mehreren Maßstäben
   (`boxFilter`), nur wo Rot in der Nähe ist. Jede Kandidatin wird über 72
   Strahlen am Übergang Gelb→Rot vermessen. Gezählt wird jede Scheibe mit Rot
   ringsum und mindestens halb so großem Radius wie die größte.
4. **Randpunkte.** 720 Strahlen vom Scheibenzentrum, für jeden Übergang der
   Tabelle nach außen fortschreitend; das Suchfenster folgt aus dem
   Kegelschnitt des vorigen Übergangs. Liegen auf einem Strahl mehr als 10 px
   zwischen Innen- und Außenklasse, gilt er als verdeckt und liefert keinen
   Punkt.
5. **Robuster Fit.** Ab 40 Randpunkten: `Conic.fit`, dann schrittweises
   Entfernen von Ausreißern nach dem Sampson-Abstand, mit der Schwelle
   2,5 · 1,4826 · Median. Ein Ring wird verwendet, wenn mindestens 60
   Innenpunkte bleiben und ihr Median-Abstand höchstens 2,4 px beträgt
   (`register.py`: 3 px bei 2000 px).
6. **Homographie.** Den Startwert liefert `Rectification.fromConcentricCircles`
   aus den zwei am besten gestützten Ringen mit Radienverhältnis höchstens
   0,75. Levenberg-Marquardt über alle Ringpunkte minimiert den radialen Rest
   `|H·p| − r`. Ein Ring mit radialem RMS über 0,012 Radien fällt heraus,
   solange mehr als zwei bleiben. Danach werden Spiegelung und Bildaufrechte
   wie in `Rectification` festgelegt; dessen private Hilfen werden dafür
   geteilt. Zum Schluss wird die Homographie mit `diag(s, s, 1)` auf das
   Original umgerechnet.
7. **Entzerren (Stufe 3).** `warpPerspective` aus dem Original in das Quadrat
   `[-1.1, 1.1]²`. Die Kantenlänge folgt aus der Pixeldichte am Scheibenzentrum
   im Original, höchstens 3000 px zum Schutz des Speichers. In 3a dient das
   Ergebnis den Debug-Bildern; 3b arbeitet darauf.

**Abweichungen:**

| Wo | Vorlage | 3a | Grund |
|---|---|---|---|
| Robuster Fit | Haupt-Spec: RANSAC | schrittweises Entfernen nach Sampson-Abstand | deterministisch und an diesem Korpus erprobt |
| Arbeitsauflösung | `register.py`: 2000 px | 1600 px | Haupt-Spec, Stufe 1; ob es reicht, zeigt die Messung |
| Übergang bei 1,0 | Haupt-Spec: als Zusatz | entfällt | auf heller Scheibe unzuverlässig |

## Messung

`RegistrationError` in `:detection-corpus` vergleicht zwei Homographien, beide
als neun Werte zeilenweise, beide vom EXIF-gedrehten Original in spot-lokale
Auflagenkoordinaten:

1. Punkte auf den Ringen 0,2, 0,4, 0,6, 0,8 und 1,0, je 72 Winkel.
2. Mit der Referenz ins Bild. Es zählen nur Punkte, die im Bild liegen; eine
   angeschnittene Auflage wird so nur dort gemessen, wo sie zu sehen ist. Die
   Bildgröße gibt der Runner aus dem geladenen Bild.
3. Mit der vorhergesagten Homographie zurück. Der Abstand zum Ausgangspunkt ist
   der Fehler, in Spot-Radien.

Je Foto: Median und Maximum über die sichtbaren Punkte und deren Anzahl. Beide
Seiten richten die Drehung an der Bildaufrechten aus, ein Drehfehler zählt also
als Fehler.

## Korpuslauf, Bericht und Debug-Bilder

`RegistrationCorpusRun` ist ein JVM-Test in `:detection`. Er läuft nur, wenn
`DETECTION_CORPUS_DIR` gesetzt ist, und der Pfad wird nur in
`testDevDebugUnitTest` verdrahtet; sonst liefe der Korpus in allen sechs
Varianten.

Ablauf je Foto: `CorpusLoader` → `imread` → `register` → `RegistrationError`.

**Bericht** `detection/build/reports/detection/registration.md`:

- Kopf: Fotos gesamt, im Umfang, registriert, gescheitert nach Grund
- Tabelle je Foto im Umfang, das schlechteste zuerst: Ergebnis, verwendete
  Ringe, größter radialer RMS, Fehler als Median und Maximum, sichtbare Punkte.
  Radialer RMS und echter Fehler stehen nebeneinander; das ist das Material für
  eine Güte in 3b.
- Zusammenfassung: Median und Maximum der Foto-Maxima, ohne Bewertung
- Fotos außerhalb des Umfangs: Begründung aus `out-of-scope.json` und Ergebnis,
  ohne Fehlerwert, weil ihre Referenz in WA6Ring-Koordinaten steht

**Debug-Bilder** unter `detection/build/reports/detection/registration/<foto>/`:

1. `1-farbklassen.png`: die Klassenkarte
2. `2-scheiben.png`: Kandidatinnen und gezählte gelbe Scheiben
3. `3-ringe.png`: Randpunkte, verwendet und verworfen, mit den gefitteten
   Kegelschnitten auf dem verkleinerten Foto
4. `4-entzerrt.png`: das entzerrte Bild mit den Sollringen in Grün und den
   Ringen, wie die Referenz sie sieht, in Magenta. Liegen beide übereinander,
   stimmt die Registrierung; der Abstand zwischen ihnen ist der Fehler.

**Harte Prüfung:** nur `a6_x99999_multiple_targets.jpg` → `FACE_MISMATCH`.
Scheitert ein Foto im Umfang, bleibt der Test grün; das Ergebnis steht im
Bericht.

## Fehlerfälle

| Ergebnis | Wann |
|---|---|
| `FACE_MISMATCH` | Die Anzahl gelber Scheiben weicht von `faceCount` ab. |
| `FACE_NOT_FOUND` | Keine gelbe Scheibe mit Rot ringsum, weniger als zwei brauchbare Ringe, oder kein Ringpaar liefert einen Startwert. |
| `IllegalArgumentException` | Das Bild ist leer oder kein 8-Bit-BGR. Das ist ein Programmierfehler, kein Ergebnis der Erkennung. |

`Failed.detail` nennt den Grund für Bericht und Debug-Bilder, etwa „Ring 0,4:
23 Punkte“. Es wird dem Nutzer nie angezeigt. `Registered` gibt es nur mit
mindestens zwei Ringen.

## Tests

1. **Logik ohne Bild**, testgetrieben:
   - der robuste Fit mit eingestreuten Ausreißern, etwa strahlenförmigen
     Punkthaufen wie von Schäften
   - Levenberg-Marquardt aus exakten Ringpunkten, ohne Spiegelung und ohne
     Drehung
   - die Übergangssuche mit Lückenregel auf einem synthetischen Klassenbild
   - die Zählung der Scheiben und das deterministische k-means
   - `RegistrationError`: Gleiche Homographien ergeben null, eine bekannte
     Verschiebung und eine Drehung um 5° den erwarteten Fehler; Punkte
     außerhalb des Bildes zählen nicht.
2. **Synthetische Bilder**, testgetrieben. `SyntheticFace` zeichnet eine
   `WAFull`-Auflage in druckähnlichen Farben und bildet sie über eine bekannte
   Homographie in ein Foto ab: frontal, 30°, 45°, angeschnitten bis auf die
   Übergänge 0,2 und 0,4, dunkel, mit Farbstich, mit dunklen Streifen als
   Schäften. `register` muss die Homographie jeweils mit einem maximalen Fehler
   von höchstens 0,005 Radien zurückfinden, gemessen wie unter *Messung*. Das
   ist eine Prüfung gegen exakt bekannte Wahrheit, keine Schranke für den
   Korpus. Drei Scheiben ergeben `FACE_MISMATCH`, keine Scheibe
   `FACE_NOT_FOUND`. Eine Test-Regel lädt die nativen Bibliotheken einmal je
   Lauf.
3. **Korpuslauf** wie oben.

Die Grenzwerte der Wahrnehmung, also Farbbänder und Mindestpunktzahlen, sind
nicht testgetrieben. Sie kommen aus `register.py` und werden am Korpusbericht
nachgestellt (Haupt-Spec, *Zwei Teststufen*).

## Änderungen an der Haupt-Spec

Mit diesem Dokument geändert:

- *Stufe 2:* „RANSAC“ ist durch einen Verweis auf dieses Dokument ersetzt.
- *Zwei Teststufen:* Wahrnehmungsstufen laufen als JVM-Tests mit der
  Desktop-OpenCV. Die Annahme, das gehe nur auf dem Gerät, hat eine Probe
  widerlegt.
- *Debug-Ansicht:* zuerst als Bilder aus dem Korpuslauf, der Bildschirm in der
  App mit der Integration.
- *Reihenfolge der Umsetzung:* Die Schritte 6 und 7 werden zu Plan 3a und 3b.

## Offene Punkte

- **Zwei OpenCV-Versionen.** Getestet wird 4.9, ausgeliefert 4.14, und die App
  bekommt das Bild über `Bitmap` statt über `imread`, also mit einem anderen
  JPEG-Decoder. Beides fängt ein kleiner Test auf dem Gerät im
  Integrationsplan ab.
- **Die Referenz ist nicht unabhängig.** Sie stammt aus `register.py`, und 3a
  übernimmt dessen Verfahren. Ein kleiner Fehler heißt deshalb auch „nahe am
  Prototyp“, nicht nur „nahe an der Wahrheit“. Die Sidecars wurden am Bild
  geprüft, aber eine gemeinsame Schwäche beider Verfahren sähe diese Messung
  nicht.
- **Verkantete Aufnahmen.** Referenz und Pipeline setzen oben im Bild mit oben
  auf der Scheibe gleich. Ein Drehfehler durch ein verkantetes Handy bleibt
  deshalb unsichtbar, bis der Korpus verkantete Fotos mit eigener Bezugslinie
  hat.
- **Laufzeit auf dem Gerät** ist nicht gemessen. Die Haupt-Spec gibt der
  ganzen Erkennung ein bis drei Sekunden; Zeiten auf dem Desktop sagen darüber
  wenig.
- **`faceConfidence`.** 3a liefert je Ring Punktzahl und radialen RMS, und der
  Bericht stellt sie dem echten Fehler gegenüber. Eine Formel für die Güte
  folgt in 3b aus diesen Zahlen.

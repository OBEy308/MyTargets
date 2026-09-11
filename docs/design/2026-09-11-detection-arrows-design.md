# Pfeilfindung — Design (Plan 3b)

Datum: 2026-09-11
Status: abgestimmt, Grundlage für Plan 3b
Bezug: `docs/design/2026-09-09-arrow-detection-design.md` (im Folgenden
*Haupt-Spec*), Stufen 4 bis 7; `docs/design/2026-09-10-detection-registration-design.md`
(im Folgenden *Registrierungsdesign*, Plan 3a)

## Ziel

Die Pipeline findet in einem registrierten Foto die steckenden Pfeile,
bestimmt ihre Einschusspunkte und liefert ein vollständiges
`DetectionResult`: Registrierung aus 3a, Pfeilfindung, Spot-Zuordnung und
Auswahl nach Stufe 7 der Haupt-Spec. Gemessen wird mit den vier Kennzahlen der
Haupt-Spec, gesamt und je Aufnahmewinkel.

**Fertig ist 3b,** wenn alle 28 Fotos im Umfang durch die Erkennung laufen,
der Bericht vorliegt und die Kennzahlen der schrägen Fotos als
Regressionsschranken festgeschrieben sind. Die Schranken entstehen aus dem
ersten vollständigen Bericht, wie die Haupt-Spec es verlangt: erst messen, dann
festschreiben.

**Der Korpus am 2026-09-11**, Fotos im Umfang:

| Gruppe | Fotos | gelistete Treffer | unaufgelöste Pfeile | Fußpunkt der Kamera, Abstand zum Zentrum |
|---|---|---|---|---|
| schräg (`leicht-schraeg`, `stark-schraeg`) | 15 | 86 | 4 | bekannt bei elf Fotos: neunmal außerhalb der Auflage (1,01 bis 1,64 Radien), zweimal darauf (0,46 und 0,67) |
| frontal | 13 | 74 | 4 | bekannt bei zwölf Fotos: immer auf der Auflage, 0,10 bis 0,43 Radien |

Der Fußpunkt ist dort bekannt, wo das Sidecar einen Block `registration.view`
trägt. Was er für die Pfeilfindung bedeutet, steht unter *Verfahren*.

## Umfang

**In 3b:**

- Stufen 6 und 7 der Haupt-Spec für `WAFull`: Schaftsuche durch den Fußpunkt
  der Kamera, Einschuss am Ende des Flankenkontrasts, Spot-Zuordnung und
  Auswahl
- das Bild in der Schnittstelle `ArrowDetector`
- Korpuslauf mit Bericht, Kandidatendiagnose und Debug-Bildern
- Regressionsschranken für die schrägen Fotos, als letzte Aufgabe

**Nicht in 3b:**

- Stufen 4 und 5 der Haupt-Spec, Farbfeld und Residuum. Sie bleiben in der
  Haupt-Spec als möglicher Weg für frontale Aufnahmen (siehe *Änderungen an
  der Haupt-Spec*).
- Frontale Fotos als Ziel. Sie laufen mit, werden als eigene Gruppe berichtet
  und bekommen keine Schranke.
- Das Befiederungsende als zweites Merkmal und die Schätzung des Fluchtpunkts
  aus den Streifen (Haupt-Spec, Stufe 6). In 3b liegt jeder Kandidat per
  Konstruktion auf einer Geraden nahe dem Fußpunkt. Ein falscher Fußpunkt oder
  ein geneigter Pfeil zeigt sich deshalb als fehlender oder verkürzter
  Kandidat, nicht als falsch gewähltes Ende; der Bericht misst beides am
  Abstand der Kandidatengeraden von `Q` (siehe *Verfahren*, Schritt 3, und
  *Offene Punkte*, *Geneigte Pfeile*).
- 3-Spot-Auflagen und `WA6Ring`
- Alles in `:app`: die Umwandlung `Bitmap` → `Mat`, das Lesen der Brennweite
  aus EXIF, die Linienregel mit Pfeilradius für den Ringwert
- Laufzeit auf dem Gerät

## Entscheidungen

| Frage | Entscheidung | Grund |
|---|---|---|
| Frontale Fotos | laufen mit, eigene Gruppe, keine Schranke | Die Haupt-Spec nimmt leicht schräge Aufnahmen an und nennt frontale den schwächeren Fall. Bei frontalen Fotos liegt der Fußpunkt zwischen den Pfeilen, und Schäfte nahe daran schrumpfen zu Stummeln. |
| Verfahren | `tools/radial.py` und `tools/tips.py` des Korpus portieren | Sie haben Kandidaten und Einschüsse der schrägen Fotos geliefert. Die Schaftrichtung folgt aus der Geometrie, statt gesucht zu werden. |
| Geneigte Pfeile | nur die seitlichen Versätze der Suche, keine Schätzung des Fluchtpunkts aus den Streifen | Die Versätze decken eine Neigung bis `0,04 / d` gegen die Normale ab, auf dem Korpus 0,7° bis 1,8°. Der Korpus ist indoor auf der Achse aufgenommen und hält das. Der Bericht misst den Abstand jeder Kandidatengeraden von `Q`, damit ein Folgeplan weiß, ob die Schätzung aus den Streifen nötig ist (siehe *Offene Punkte*). |
| Stufen 4 und 5 | entfallen für 3b | Der Flankenkontrast ist lokal und braucht keine Lichtkorrektur. Farbfeld und Residuum wären der größte Teil von 3b, ohne Vorlage und ungetestet, und jeder Ringübergang hinterließe nach dem Entzerren einen Saum im Residuum. |
| Registrierung im Korpuslauf | die eigene aus 3a, nicht die Referenz aus dem Sidecar | Ihr größter Fehler auf dem Korpus liegt bei 0,019 Radien, der Median der Foto-Maxima bei 0,0015; das Budget des Erkenners ist 0,05. Gemessen wird so die ganze Kette, und der Bericht zeigt je Foto den Registrierungsfehler daneben. |
| Arbeitsbild | das entzerrte Bild aus 3a (`FaceWarp`) | Es hat auf dem Korpus 706 bis 1364 px je Radius, die Werkzeuge arbeiteten bei 1000. Ihre Schwellen stehen in Auflageneinheiten und gelten unverändert. |
| Bild in der Schnittstelle | `Mat`, keine Unterschnittstelle mit `Bitmap` | Eine Schnittstelle ohne Bild hat keine Implementierung. Mit `Mat` bleibt der Vertrag in JVM-Tests prüfbar, und ein gelernter Detektor (Haupt-Spec, *Upgrade-Pfad zu Ansatz C*) nimmt ebenfalls ein Bild. |
| Schranken | letzte Aufgabe, aus dem ersten vollständigen Bericht, nur schräge Fotos, ein Pfeil Spielraum | Erst messen, dann festschreiben (Haupt-Spec). Der Spielraum fängt kleine Unterschiede der nativen OpenCV-Bibliotheken zwischen den Plattformen ab. |

## Aufbau

### Module

- **`:detection`** bekommt das Paket `de.dreier.mytargets.detection.arrows`.
  Es gelten die Regeln des Registrierungsdesigns: Der Hauptcode importiert aus
  OpenCV nur `org.opencv.core` und `org.opencv.imgproc`, Pixel werden einmal
  per `Mat.get(0, 0, array)` geholt, und jede selbst erzeugte `Mat` wird in
  `try`/`finally` freigegeben. Der vorhandene Test über die Importe deckt
  `arrows/` mit ab.
- **Der Fußpunkt der Kamera** kommt nach `geometry/`, reines Kotlin (siehe
  *Verfahren*, Schritt 2).
- **Einheiten in `arrows/`:**

| Einheit | Aufgabe | Vorlage |
|---|---|---|
| Arbeitsbild | Helligkeitskanal des entzerrten Bildes, Maske gültiger Pixel, Umrechnung zwischen Auflageneinheiten und Pixeln, Abtastung | `V = max(R, G, B)` in beiden Werkzeugen |
| Flankenkontrast | um wie viel eine Linie dunkler oder heller ist als beide Flanken | `valley` in `tips.py`, die Profile in `radial.py` |
| Schaftsuche | Strahlen durch den Fußpunkt, Läufe, Filter, Doppelte, Zusammenlegen | `radial.py` |
| Schaftlauf | Richtung und Versatz nachstellen, Einschuss am Ende des Kontrasts | `refine` in `tips.py` |
| Kandidaten | Zusammenlegen nach dem Verfeinern, Zuversicht | neu |
| Detektor | Registrierung, Entzerren, Fußpunkt, Suche, Lauf, Spot, Auswahl | neu |
| Debug-Bilder | Bilder 5 und 6 | neu |

- **Vorhandener Code** wird genutzt, nicht nachgebaut: `OpenCvFaceRegistrar`,
  `FaceWarp`, `NormalVanishingPoint`, `SpotMapping`, `CandidateSelection`.
- **`:detection-corpus`** bekommt den Pfeilbericht mit der Tabelle je Foto und
  der Kandidatendiagnose und liest aus dem Sidecar zusätzlich
  `registration.view.cameraPositionFaceUnits`, das der Test *Korpusgeometrie*
  braucht; `SidecarTruth` kennt bisher nur `imageToTarget` und `imagedCentre`.
  Die README des Korpus führt das Feld in ihrer Feldtabelle nach. Das Modul
  bleibt ohne Bildverarbeitung. Die Kennzahlen rendert der vorhandene
  `MetricsReport`.
- **`:app`** bleibt unberührt.

### Schnittstelle

```kotlin
interface ArrowDetector {
    /** @param image the original as 8-bit BGR, already turned by its EXIF orientation */
    fun detect(
        image: Mat,
        request: DetectionRequest,
        debug: DebugSink = DebugSink.NONE
    ): DetectionResult
}

data class DetectionRequest(
    val layout: FaceLayout,
    val zoneRadii: List<Double>,
    val transitions: List<RingTransition>,
    val expectedShots: Int,
    val intrinsics: CameraIntrinsics
)

enum class TipRefinement { REFINED, NO_SHAFT, RAN_OUT }

class ArrowCandidate(
    val tip: Vec2,                  // Einschuss, Auflagenkoordinaten
    val far: Vec2,                  // anderes Ende, zur Nocke hin, auf der Geraden des Kandidaten
    val contrast: Double,           // Kontrast des Laufs aus der Suche
    val shaftContrast: Double,      // Schaftkontrast aus dem Schaftlauf
    val score: Double,              // Länge mal Kontrast, wie radial.py
    val refinement: TipRefinement,
    val offsetFromFoot: Double,     // Abstand der Geraden tip–far von Q, mit Vorzeichen
    val confidence: Double,         // siehe Verfahren, Schritt 5
    val located: Candidate?         // Spot und spot-lokale Lage, null neben der Auflage
)

sealed interface ArrowAnalysis {
    class NotRegistered(val registration: RegistrationOutcome.Failed) : ArrowAnalysis

    class Analysed(
        val registration: RegistrationOutcome.Registered,
        val footPoint: Vec2?,
        val candidates: List<ArrowCandidate>,   // nach score absteigend
        val selection: SelectionOutcome
    ) : ArrowAnalysis
}

class OpenCvArrowDetector(
    private val registrar: FaceRegistrar = OpenCvFaceRegistrar()
) : ArrowDetector {
    fun analyse(image: Mat, request: DetectionRequest, debug: DebugSink = DebugSink.NONE): ArrowAnalysis
    override fun detect(image: Mat, request: DetectionRequest, debug: DebugSink): DetectionResult
}
```

- `image` wie im Registrierungsdesign: das Original als 8-Bit-BGR, nach EXIF
  gedreht. Auf dem Desktop leistet das `Imgcodecs.imread`, in der App die
  Umwandlung aus `Bitmap`.
- `transitions` braucht die Registrierung je Auflage. Bisher gab der Aufrufer
  sie dem Registrar direkt; mit einem Detektor, der selbst registriert, gehören
  sie in die Anfrage. `RingTransitions.WA_FULL` bleibt die einzige Tabelle, die
  Übersetzung aus `TargetModelBase` gehört zur Integration.
- `DebugSink` wandert aus `registration` ins Hauptpaket, weil beide Stufen ihn
  nutzen. Der Detektor reicht ihn an den Registrar weiter.
- `analyse` liefert alles, was der Korpuslauf braucht. `detect` ist `analyse`
  plus Umwandlung in `DetectionResult`. So gibt es einen einzigen Rechenweg,
  und das Ergebnis für die App bleibt frei von Diagnosedaten.
- **`faceConfidence`** ist 1 minus größter radialer RMS der verwendeten Ringe
  geteilt durch 0,012, begrenzt auf 0 bis 1. Das ist eine Definition, keine
  Eichung: Ab 0,012 Radien wirft die Registrierung einen Ring hinaus, eine
  Registrierung an dieser Grenze bekommt also 0. Auf dem Korpus ergibt das rund
  0,5 bis 0,8. Eichen lässt sich die Güte nicht, weil die Registrierung im
  Umfang kein einziges Mal scheitert.
- **`DetectedShot.confidence`** ist die Zuversicht des Kandidaten.
- `ArrowDetectorContractTest` folgt der neuen Signatur. Weil auch der Stub ein
  `Mat` entgegennimmt, lädt der Test die native Bibliothek über `OpenCvRule`.

## Verfahren

Grundlage sind `tools/radial.py` und `tools/tips.py` im Korpus. Übernommen wird
das Verfahren mit seinen Schwellen, nicht der Code. Alle Längen stehen wie in
den Werkzeugen in Auflageneinheiten, der Radius der Vollauflage ist 1; in Pixel
rechnet die Kantenlänge des entzerrten Bildes um.

1. **Arbeitsbild.** `FaceWarp.warp` aus dem Original, die Kantenlänge wie in 3a
   nach der Pixeldichte am Scheibenzentrum, höchstens 3000 px. Davon der
   Helligkeitskanal `V = max(B, G, R) / 255` als `FloatArray`. Dazu eine Maske
   gültiger Pixel, auf dem entzerrten Raster aus `H⁻¹` gerechnet: Ein Pixel
   ist gültig, wenn sein Urbild im Rechteck des Originals liegt. Das braucht
   keine zweite `Mat` in Originalgröße und keinen zweiten Warp. Eine Probe, an
   der ein ungültiges Pixel beteiligt ist, liefert keinen Kontrast. Die
   Werkzeuge hielten jedes Pixel mit `R + G + B = 0` für ungültig, was auf dem
   schwarzen Ring falsch sein kann.

2. **Fußpunkt der Kamera.** Das Lot von der Kamera auf die Auflagenebene
   trifft sie im Fußpunkt `Q`. Im entzerrten Bild zeigt jedes Pixel, wo der
   Sehstrahl durch dieses Pixel die Ebene trifft. Für einen senkrecht
   steckenden Pfeil mit Einschuss `P` und Nocke in der Höhe `h` über der Ebene,
   bei einer Kamera im Abstand `d` von ihr, trifft der Sehstrahl durch die
   Nocke die Ebene in `P + (P − Q) · h / (d − h)`. Weil die Nocke zwischen
   Ebene und Kamera liegt, ist `h < d`. **Der Schaft liegt deshalb auf der
   Geraden durch `Q` und `P`, und der Einschuss ist sein Ende näher an `Q`.**
   Das ist die Regel aus Stufe 6 der Haupt-Spec, im entzerrten Bild
   ausgedrückt.

   `Q` ist das Bild des Fluchtpunkts der Scheibennormalen unter der
   Homographie: Die Fluchtlinie ist `l = Hᵀ · (0, 0, 1)`, der Fluchtpunkt
   `v = (K·Kᵀ) · l` (`NormalVanishingPoint.compute`), und `Q = H · v`, mit
   `H = imageToTarget`. `register.py` rechnet denselben Punkt über eine
   Zerlegung der Pose (`registration.view.cameraPositionFaceUnits`). Auf den
   23 Fotos im Umfang mit diesem Block weichen beide Wege um höchstens 0,028 Radien ab,
   auf den frontalen um höchstens 0,005. `register.py` normiert die beiden
   Spalten der Pose einzeln, und wo die genäherte Brennweite nicht ganz zur
   Homographie passt, wird die Drehung dadurch leicht schief. Die Suche
   verkraftet die Abweichung, ihre seitlichen Versätze reichen bis 0,04.

3. **Schaftsuche** (`radial.py`).
   - Strahlen von `Q` in Schritten von 0,25°, also 1440 Richtungen. Jeder
     Strahl wird alle 0,002 auf dem Stück abgetastet, das im Quadrat
     `|x|, |y| < 1,05` liegt, und das für 17 seitliche Versätze von −0,04 bis
     +0,04 in Schritten von 0,005. Die Versätze nehmen Pfeile auf, die nicht
     genau senkrecht stecken, aber nur wenig: Ein um `α` gegen die Normale
     geneigter Pfeil bildet einen Streifen, dessen Gerade `Q` um `d · tan α`
     verfehlt, mit `d` dem Abstand der Kamera von der Ebene in Radien, gleich
     wo der Pfeil steckt. Alle gleich geneigten Pfeile laufen durch denselben
     Punkt `Q − d · t`, mit `t` dem Anteil der Pfeilrichtung in der Ebene je
     Einheit Höhe. Das Fenster ±0,04 verlangt also `tan α ≤ 0,04 / d`. Auf
     dem Korpus liegt `d` zwischen 1,3 und 3,3 (`cameraPositionFaceUnits`,
     dritter Wert), das sind 1,8° bis 0,7°. Ein stärker geneigter Schaft wird
     nur noch als Sehne getroffen, und sein Einschuss aus der Suche rutscht
     entlang des Schafts (siehe *Offene Punkte*, *Geneigte Pfeile*).
   - **Kontrast je Probe.** Die Mitte ist das Minimum dreier Linien im
     Abstand 0,003 um den Strahl, die Flanken liegen bei ±0,016.
     `dunkler = min(Flanken) − Mitte` für einen dunklen Schaft,
     `heller = max(Mittellinien) − max(Flanken)` für einen grauen Schaft auf
     Schwarz. Der Kontrast ist das größere von beiden. Abgetastet wird am
     nächsten Pixel.
   - **Läufe.** Ein Lauf besteht aus Proben mit Kontrast über 0,15, Lücken bis
     0,02 werden überbrückt. Er bleibt, wenn er mindestens 0,08 lang ist, zu
     mindestens 60 % aus solchen Proben besteht, nicht ganz außerhalb von
     Radius 1,02 liegt und höchstens 40 % seiner Proben näher als 0,009 an
     einer Ringlinie liegen. Die Ringlinien sind die Zonenradien der Anfrage;
     die Werkzeuge hatten sie als Konstante, für `WAFull` mit denselben Werten.
   - **Bewertung.** Der Kontrast des Laufs ist der Median seiner Proben über
     der Schwelle, die Bewertung ist Länge mal Kontrast. Einschuss ist das
     Ende näher an `Q`.
   - **Doppelte.** In der Reihenfolge der Bewertung fällt ein Lauf weg, wenn
     ein behaltener fast dieselbe Richtung hat (unter 6°), beide Enden des
     neuen näher als 0,025 an dessen Gerade liegen und sich beide entlang der
     Geraden überlappen, mit 0,05 Spielraum.
   - **Zusammenlegen.** Stücke desselben Schafts, getrennt etwa vom schwarzen
     Ring oder einem kreuzenden Schaft, werden eins: Richtung unter 2,5°, beide
     Enden näher als 0,02 an der Geraden, Lücke entlang der Geraden höchstens
     0,3. Einschuss ist dann das Ende mit der kleinsten Projektion von `Q` aus,
     das andere Ende das mit der größten; die Bewertungen addieren sich.
   - Es bleiben die 20 am besten bewerteten.

4. **Schaftlauf** (`refine` in `tips.py`). Die Saat ist der Einschuss aus der
   Suche mit der Richtung vom anderen Ende zum Einschuss.
   - Der Kontrast wird wie oben gebildet, aber bilinear abgetastet, mit sieben
     Mittellinien von −0,003 bis +0,003 und Flanken bei ±0,012.
   - **Güte einer Geraden:** das Mittel der oberen drei Viertel der Kontraste
     zwischen 0,10 und 0,012 hinter der Saat, zur Nocke hin, alle 0,0015. Das
     untere Viertel fällt weg, damit Lücken und kreuzende Schäfte die Güte
     nicht drücken.
   - **Nachstellen:** grob Richtung ±12° in 1°-Schritten und Versatz ±0,012 in
     Schritten von 0,002, dann zweimal fein, ±1° in 0,25°-Schritten und
     ±0,002 in Schritten von 0,0005.
   - **Lauf:** Proben alle 0,0005 von 0,10 hinter der Saat bis 0,035 davor.
     Der Schaftkontrast ist der Median der Proben mehr als 0,03 hinter der
     Saat. Ab 0,03 hinter der Saat geht der Lauf nach vorn. Der Einschuss ist
     die letzte Probe vor einer Lücke von mindestens 0,012, deren Kontrast
     unter 35 % des Schaftkontrasts liegt. Erreicht eine kürzere Lücke das
     Ende des Fensters, endet der Lauf ebenfalls vor ihr; so macht es
     `tips.py`, und das ist kein `RAN_OUT`.
   - Liegt der Schaftkontrast unter 0,08, gibt es keinen Schaft zu verfolgen
     (`NO_SHAFT`). Hat noch die letzte Probe des Fensters Kontrast
     (`RAN_OUT`), hat der Lauf kein Ende gefunden. In beiden Fällen behält der
     Kandidat den Einschuss aus der Suche. Die Werkzeuge haben diese Fälle dem
     Annotator gemeldet, der Bericht zählt sie.
   - Nach dem Lauf liegt `far` auf der verfeinerten Geraden, als Projektion
     des Suche-Endes darauf, und `offsetFromFoot` ist der Abstand dieser
     Geraden von `Q` mit Vorzeichen. Bei `NO_SHAFT` und `RAN_OUT` bleibt die
     Gerade der Suche, weil der Einschuss aus der Suche auf ihr liegt.

5. **Zusammenlegen und Zuversicht.** Beides ist neu, weil es in den Werkzeugen
   der Annotator von Hand erledigt hat.
   - Zwei Kandidaten sind derselbe Pfeil, wenn ihre verfeinerten Einschüsse
     näher als 0,01 beieinander liegen oder wenn ihre Geraden zusammenfallen:
     Richtung unter 2° und jeder Einschuss näher als 0,01 an der Geraden des
     anderen. Der zweite Fall fängt zwei Sehnen desselben Schafts ab, von
     denen eine im Lauf gescheitert ist und deshalb den Einschuss aus der
     Suche behalten hat; ohne ihn wäre das ein Fehlfund. Es bleibt der mit
     `REFINED` vor `RAN_OUT` vor `NO_SHAFT`, bei gleichem Ausgang der besser
     bewertete.
   - **Zuversicht.** Die Bewertung der Suche taugt zum Ordnen, nicht zum
     Auswählen: Sie wächst mit der Länge, und ein kurzer echter Schaft, nahe
     `Q` oder halb verdeckt, läge damit bei einem Ringfragment. Die
     Abstandsregel aus Stufe 7, 0,15 der Skala, braucht dagegen echte Pfeile
     nahe 1 und falsche nahe 0. Die Zuversicht ist deshalb Länge mal Kontrast
     mit drei Änderungen: Die Länge sättigt bei 0,3, also
     `min(Länge, 0,3) / 0,3`, weil ein Schaft ab dieser Länge nicht mehr
     wahrscheinlicher ein Schaft ist. Der Kontrast ist der Schaftkontrast aus
     dem Lauf, der das Merkmal „dunkle Linie mit zwei hellen Flanken“ auf der
     nachgestellten Geraden misst. `NO_SHAFT` und `RAN_OUT` halbieren das
     Produkt, weil ihr Einschuss nur aus der Suche stammt. Geteilt wird durch
     den höchsten Wert aller Kandidaten des Fotos nach dem Zusammenlegen,
     damit die Abstandsregel einen Maßstab hat, ohne dass der Kontrast
     absolut geeicht sein muss. Die 0,3 und die Halbierung sind Startwerte.
     Die Zuversicht bleibt der wichtigste Stellknopf von 3b: Die
     Kandidatendiagnose zeigt sie für echte und falsche Kandidaten
     nebeneinander, und vor den Schranken wird sie daran nachgestellt.

6. **Spot und Auswahl.** `SpotMapping.locate` ordnet jeden Einschuss einem
   Spot zu und rechnet spot-lokal um. Ein Einschuss neben der Auflage fällt weg
   (Haupt-Spec, *Bekannte Grenzen der Pipeline*). `CandidateSelection.select`
   wählt mit `expectedShots` und `maxArrowsPerSpot` aus, und jeder angenommene
   Kandidat wird ein `DetectedShot`.

**Abweichungen von den Werkzeugen:**

| Wo | Werkzeug | 3b | Grund |
|---|---|---|---|
| Fußpunkt | Zerlegung der Pose aus Homographie und EXIF-Brennweite | Fluchtpunkt der Normalen unter der Homographie | vorhandene Geometrie; die Abweichung liegt innerhalb der seitlichen Versätze |
| Auflösung | 1000 px je Radius | 706 bis 1364 px je Radius | das entzerrte Bild aus 3a; die Schwellen stehen in Auflageneinheiten |
| ungültige Pixel | `R + G + B = 0` | Urbild unter `H⁻¹` außerhalb des Originals | ein schwarzes Pixel auf dem schwarzen Ring ist gültig |
| Saatpunkte | Suche, dann von Hand geprüft und ergänzt | nur die Suche | die Pipeline hat keinen Annotator |
| Einschuss bei `RAN_OUT` | letzte Probe des Fensters | Einschuss aus der Suche | ein Lauf ohne Ende hat keinen Endpunkt, das Ende des Fensters ist willkürlich |
| Auswahl | Annotator | Zusammenlegen, relative Zuversicht, Stufe 7 | neu |

## Korpuslauf und Bericht

`ArrowCorpusRun` ist ein JVM-Test in `:detection` wie `RegistrationCorpusRun`.
Er läuft nur mit `DETECTION_CORPUS_DIR`, nur in `testDevDebugUnitTest`, und
schreibt nach `detection.report.dir`.

Er läuft über die annotierten Fotos im Umfang. Fotos außerhalb des Umfangs
führt der Bericht nur mit ihrer Begründung auf: Ihre Wahrheit steht zum Teil
in WA6Ring-Koordinaten, und sie zählen für keine Kennzahl.

**Anfrage je Foto:**

- `layout` ist `FaceLayout.singleSpot()`, `zoneRadii` sind die Zonen von
  `WAFull` (0,05, 0,1, 0,2 bis 1,0), `transitions` ist `RingTransitions.WA_FULL`.
- `expectedShots` ist `end.shotsPerEnd` aus dem Sidecar, nicht die Zahl der
  gelisteten Treffer. Die Pipeline weiß nicht, wie viele Pfeile der Annotator
  auflösen konnte.
- `intrinsics` kommt aus `CameraIntrinsics.from35mmEquivalent` mit
  `camera.focalLength35mm` und der Größe des gedrehten Bildes. Für das eine
  Foto im Umfang ohne Brennweite, `a6_x99765_noise.jpg`, gilt der Rückfall der
  Haupt-Spec.

Der **Ringwert eines Funds** ist der Zonenindex aus dem reinen Radius, wie die
Sidecars rechnen. Die Linienregel mit Pfeilradius
(`TargetModelBase.getZoneFromPoint`) wendet die App an; `DetectedShot` trägt
keinen Ringwert.

Zuordnung, Budget (0,05 plus `positionTolerance`), Nachsicht für unaufgelöste
Pfeile und Kennzahlen kommen unverändert aus `:detection-corpus`
(`ShotMatching`, `Metrics`).

**Bericht** `detection/build/reports/detection/arrows.md`:

1. Die Kennzahlen aus `MetricsReport`: gesamt, je Winkel und Licht, die
   schlechtesten Fotos.
2. Eine Tabelle je Foto, nach Gruppe, das schlechteste zuerst: Winkel, Abstand
   von `Q` zum Zentrum, größter Registrierungsfehler gegen die Referenz,
   Abstand des eigenen `Q` zum `Q` aus der Referenz-Homographie mit denselben
   Intrinsics, größter Abstand einer zugeordneten Kandidatengeraden von `Q`,
   gelistete und unaufgelöste Pfeile, Kandidaten, angenommene und zugeordnete
   Funde, Fehlfunde, Grund der Auswahl, größter Positionsfehler, Zahl der
   Kandidaten mit `NO_SHAFT` und `RAN_OUT`, Laufzeit je Stufe (Registrierung,
   Entzerren, Suche, Lauf). Der Registrierungsfehler aus 3a misst auf den
   Ringen; `Q` hängt an der dritten Zeile der Homographie, die die Ringe kaum
   festlegen. Ein Foto mit kleinem Ringfehler kann deshalb ein deutlich
   verschobenes `Q` haben, und nur diese Spalte zeigt das.
3. **Kandidatendiagnose.** Sie trennt Finden von Auswählen. Dieselbe Zuordnung
   wie für die Kennzahlen läuft über alle Kandidaten statt nur über die
   angenommenen. Für jeden gelisteten Treffer steht dort, ob vor der Auswahl ein
   Kandidat im Budget lag, auf welchem Rang, mit welcher Zuversicht und mit
   welchem Abstand seiner Geraden von `Q`, und ob die Auswahl ihn angenommen
   hat. Je Foto kommt die höchste Zuversicht eines Kandidaten dazu, der zu
   keinem Treffer passt. Je Gruppe fasst sie zusammen, welcher Anteil der
   Treffer einen Kandidaten hatte, welchen Anteil erst die Auswahl verloren
   hat, und wie sich die Abstände der zugeordneten Kandidatengeraden von `Q`
   verteilen: Median und Anteil über 0,03. Häufen sie sich am Rand des
   Fensters, 0,04, ist das Fenster der Suche zu eng; ein gemeinsames
   Vorzeichen auf einem Foto heißt, dass die Pfeile dort gemeinsam geneigt
   stecken.

**Debug-Bilder** unter `detection/build/reports/detection/arrows/<foto>/`: die
Bilder 1 bis 3 vom Registrar und Bild 4 vom Korpuslauf wie in 3a, mit dem
Helfer aus `RegistrationCorpusRun` geteilt, dazu

- `5-kandidaten.png`: das entzerrte Bild mit `Q`, oder einem Pfeil zu ihm,
  wenn er außerhalb liegt, und allen Kandidaten als Strecken, nummeriert nach
  Rang
- `6-einschuesse.png`: die verfeinerten Einschüsse, grün angenommen, gelb von
  der Auswahl verworfen, `NO_SHAFT` und `RAN_OUT` eigens markiert
- `7-wahrheit.png`, vom Korpuslauf gezeichnet, weil nur er die Wahrheit kennt:
  angenommene Funde und gelistete Treffer mit ihrem Budget als Kreis,
  zugeordnete Paare verbunden

## Schranken

Letzte Aufgabe des Plans, nachdem der erste vollständige Bericht vorliegt und
die Zuversicht daran nachgestellt ist. Die Schranken gelten nur für die schrägen
Fotos, Winkel `leicht-schraeg` oder `stark-schraeg`, gemessen mit
`Metrics.over` über diese Gruppe. Jede Zählung lässt einen Pfeil Spielraum,
der Positionsfehler ein bis zwei Pixel:

| Kennzahl | Schranke |
|---|---|
| zugeordnete Treffer | ≥ gemessen − 1 |
| Fehlfunde | ≤ gemessen + 1 |
| richtige Ringwerte | ≥ gemessen − 1 |
| vergleichbare Treffer, der Nenner der Ringtreue | ≥ gemessen − 1 |
| Positionsfehler, Median und 95. Perzentil | ≤ gemessen + 0,002 |

Festgeschrieben werden Zähler, nicht Raten: Bei festgeschriebener Zahl der
gelisteten Treffer sind die Raten damit bestimmt, und der Spielraum ist genau
ein Pfeil statt eines Bruchteils. Zähler und Nenner der Ringtreue sind einzeln
gehalten, weil ein Verhältnis steigen kann, wenn beide fallen. Der Spielraum
der Positionsfehler, 0,002 Radien, sind ein bis zwei Pixel des entzerrten
Bildes; das linear interpolierte Perzentil aus `Metrics` hat keine „nächste
Stelle“, an der sich ein Spielraum festmachen ließe.

Dazu werden die Zahl der Fotos und der gelisteten Treffer der Gruppe
festgeschrieben. Wächst der Korpus, schlägt der Lauf mit dieser Begründung
fehl, und die Schranken werden an einem neuen Bericht bewusst neu gesetzt,
statt sich still zu verschieben. Geprüft wird nach dem Schreiben des Berichts,
damit ein roter Lauf seinen Bericht hinterlässt.

## Fehlerfälle

| Ergebnis | Wann |
|---|---|
| `DetectionResult.failed(FACE_MISMATCH)` oder `failed(FACE_NOT_FOUND)` | Die Registrierung scheitert, wie in 3a. |
| keine Treffer, `FEWER_THAN_EXPECTED` | Registriert, aber kein Kandidat auf der Auflage. Ebenso, wenn der Fußpunkt nicht bestimmbar ist; das kommt bei einer sichtbaren Auflage nicht vor. |
| gekürzte Liste mit `FEWER_THAN_EXPECTED`, `AMBIGUOUS_SURPLUS` oder `SPOT_OVERFLOW` | Stufe 7, wie vorhanden |
| `IllegalArgumentException` | Das Bild ist leer oder kein 8-Bit-BGR. Das ist ein Programmierfehler, kein Ergebnis der Erkennung. |

## Tests

1. **Logik ohne Bild**, testgetrieben:
   - der Fußpunkt aus einer bekannten Pose: Er ist das Lot der Kamera auf die
     Ebene, frontal unter der Kamera, bei 30° außerhalb der Auflage auf der
     Seite der Kamera
   - der Flankenkontrast: ein dunkler Streifen auf hellem Grund und ein grauer
     Streifen auf Schwarz haben Kontrast, eine Kante zwischen zwei Farbflächen
     hat keinen
   - die Läufe: Lücken bis 0,02 werden überbrückt, größere trennen,
     Mindestlänge, Füllung und Ringfilter
   - Doppelte und Zusammenlegen: Zwei Stücke eines Schafts beiderseits einer
     Lücke werden einer mit dem Einschuss näher an `Q`; zwei parallele Schäfte
     im Abstand 0,03 bleiben zwei, im Abstand 0,02 werden sie einer, was die
     Grenze der Doppelten-Regel festhält
   - der Schaftlauf auf einem Streifen mit bekanntem Ende: Einschuss auf eine
     Probe genau, ohne Streifen `NO_SHAFT`, mit Streifen über das Fenster
     hinaus `RAN_OUT`, mit einer kurzen Lücke am Fensterende der Einschuss
     davor
   - der Abstand einer Geraden von `Q` mit Vorzeichen
   - Zusammenlegen nach dem Verfeinern: zwei Sehnen desselben Schafts, eine
     `REFINED`, eine `NO_SHAFT`, werden eine, und die `REFINED` bleibt; die
     Zuversicht mit Sättigung und Halbierung; die Umwandlung von
     `ArrowAnalysis` in `DetectionResult` samt `faceConfidence`
2. **Synthetische Bilder**, testgetrieben. `SyntheticFace` bekommt eine Kamera
   aus Brennweite, Drehung und Lage, aus der sich die Homographie der Auflage
   ergibt, und Pfeile als Stäbe im Raum: Einschuss auf der Auflage, Nocke in
   der Höhe `h` zur Kamera hin, Schaft mit Breite, Befiederung nahe der Nocke.
   Gezeichnet werden sie mit derselben Lochkamera. Bei 30° und 45° muss jeder
   Einschuss auf 0,01 Radien stimmen, gemessen gegen den gesetzten Punkt. Das
   Einschussende ergibt sich dabei aus der Projektion, nicht aus einer Annahme
   des Tests. Ein Vorzeichenfehler wie der vom 2026-09-09 (Haupt-Spec, Stufe 6)
   scheitert so, statt unter beiden Vorzeichen grün zu bleiben. Weitere Fälle:
   Ein Schlagschatten, der nicht durch `Q` zeigt, wird nicht gefunden; ein
   grauer Schaft auf dem schwarzen Ring und ein Schaft, der den schwarzen Ring
   kreuzt, werden gefunden. Ein Pfeil, der bei `d` = 2 um 1° geneigt steckt,
   liegt innerhalb des Fensters der Suche, und sein Einschuss muss auf 0,01
   stimmen. Bei 3° liegt seine Gerade weiter als 0,04 von `Q`: Ein Kandidat
   muss auf der Geraden des Schafts liegen und in `offsetFromFoot` den
   Abstand `d · tan α` auf 0,01 tragen, damit die Diagnose die Neigung
   ausweist; ob sein Einschuss stimmt, verlangt der Test nicht. Ein Bild, das
   die Auflage anschneidet, sodass ein Schaft zur Nocke hin aus dem Bild
   läuft, ergibt einen Kandidaten mit richtigem Einschuss und keinen
   Kandidaten entlang des Bildrands. Das ist eine Prüfung gegen exakt
   bekannte Wahrheit, keine Schranke für den Korpus.
3. **Korpusgeometrie.** Für jedes Foto mit `registration.view` stimmt `Q` aus
   Referenz-Homographie und Brennweite mit `cameraPositionFaceUnits` auf 0,04
   Radien überein, dem Fenster der Suche (siehe *Verfahren*, Schritt 2).
   Gemessen sind höchstens 0,028; eine Grenze bei 0,03 wäre eine Rundung vom
   Rot entfernt. Ohne Korpus überspringt sich der Test.
4. **Korpuslauf** wie oben.

Die Grenzwerte der Wahrnehmung, also Kontrastschwellen, Längen und Winkel,
sind nicht testgetrieben. Sie kommen aus den Werkzeugen und werden am Bericht
nachgestellt (Haupt-Spec, *Zwei Teststufen*).

## Änderungen an der Haupt-Spec

Mit diesem Dokument geändert:

- *Schnittstelle:* `ArrowDetector.detect` nimmt das Bild als `Mat` samt
  `DebugSink`. Die Unterschnittstelle mit `Bitmap` entfällt, die Umwandlung
  gehört zur Integration. `DetectionRequest` trägt die Übergangstabelle.
- *Stufen 4 und 5:* für schräge Aufnahmen ersetzt durch die Schaftsuche durch
  den Fußpunkt. Farbfeld und Residuum bleiben beschrieben, als möglicher Weg
  für frontale Aufnahmen.
- *Stufe 6:* ergänzt um die Form der Regel im entzerrten Bild und um die
  Grenze der Suche durch `Q`: Sie setzt bis auf `0,04 / d` senkrecht
  steckende Pfeile voraus; für geneigte bleibt die Schätzung des Fluchtpunkts
  aus den Streifen der Weg, den 3b noch nicht geht.
- *Debug-Ansicht:* die Bilder 5 bis 7 aus dem Korpuslauf.
- *Reihenfolge der Umsetzung:* Schritt 2 nennt die Serie vom 2026-09-10.
  Schritt 7 verweist auf dieses Dokument; festgeschrieben werden die Kennzahlen
  der schrägen Fotos.

## Offene Punkte

- **Die Wahrheit ist nicht unabhängig.** Die Einschüsse der Serie vom
  2026-09-10 stammen aus `radial.py` und `tips.py`, die der älteren Fotos aus
  von Hand gesetzten Saaten und `tips.py`. Wo das Werkzeug zu früh oder zu
  spät stoppte, hat der Annotator am Bild korrigiert. 3b portiert genau diese
  Werkzeuge, ein kleiner Positionsfehler heißt deshalb auch „nahe an den
  Werkzeugen“. Wo der Annotator korrigiert hat, zeigt der Bericht den Fehler,
  den das Werkzeug gemacht hätte; das ist gewollt.
- **Geneigte Pfeile.** Die Suche findet einen Schaft nur ganz, wenn seine
  Gerade höchstens 0,04 von `Q` entfernt liegt, also bis zu einer Neigung von
  `0,04 / d` gegen die Normale, auf dem Korpus 0,7° bis 1,8° (siehe
  *Verfahren*, Schritt 3). Indoor auf 10 m von der Achse aus hält das, und der
  Korpus ist so aufgenommen. Outdoor auf 70 m stecken alle Pfeile um den
  Fallwinkel von rund 5° geneigt, ein Schütze einen halben Meter neben der
  Achse gibt bei 10 m 3°. Dort laufen die Streifen durch einen gemeinsamen
  Punkt neben `Q`, und die Haupt-Spec sieht vor, ihn aus den Streifen zu
  schätzen. 3b tut das nicht. Damit ein Folgeplan weiß, ob es nötig ist,
  trägt jeder Kandidat den Abstand seiner Geraden von `Q`, und die
  Kandidatendiagnose fasst die Verteilung zusammen. Der naheliegende Weg
  danach: nach dem ersten Lauf den Punkt, der die Abstandsquadrate zu den
  verfeinerten Geraden minimiert, und liegt er weiter als 0,04 von `Q`, die
  Suche von dort wiederholen. Das ist eine Grenze von 3b, nicht der
  Haupt-Spec.
- **Frontale Fotos** bleiben der Schwachpunkt. Wege für einen Folgeplan sind
  die Stufen 4 und 5 der Haupt-Spec oder Befiederung und Nocke als Merkmal,
  wo der Schaft zum Stummel wird.
- **Auflagen unter 80 cm.** Die Flanken bei ±0,016 in der Suche und ±0,012 im
  Lauf passen zu einem Schaft von etwa 5,5 mm, der auf der 122-cm-Auflage
  0,009 Radien breit ist und auf der 80er 0,014. Auf einer 40-cm-Auflage wären
  es rund 0,028, und die Flanken lägen auf dem Schaft selbst. Die Anfrage kennt
  den Durchmesser nicht, und bei der Serie vom 2026-09-10 war in der App die
  60-cm-Auflage eingestellt, fotografiert wurde die 80er. Woher der Maßstab
  kommt, entscheidet die Integration.
- **Laufzeit.** Die Suche tastet je Foto rund 1440 · 17 · 1000 · 5, also etwa
  1,2 · 10⁸ Pixel ab. Der Bericht nennt die Zeit je Stufe auf dem Desktop; auf
  dem Gerät ist nichts gemessen, und die Haupt-Spec gibt der ganzen Erkennung
  ein bis drei Sekunden. Ein naheliegender Hebel ist die Suche auf einer
  gröberen Kopie des entzerrten Bildes, der Lauf bliebe auf dem vollen. Das
  wird mit der Integration entschieden.
- **Relative Zuversicht.** Auf einem Foto ohne einen einzigen Pfeil bekommt der
  beste falsche Kandidat die Zuversicht 1, dann schützt nur die
  Kontrastschwelle der Suche. Der Korpus hat kein solches Foto. Ob Sättigung
  bei 0,3 und Halbierung für `NO_SHAFT` und `RAN_OUT` echte von falschen
  Kandidaten trennen, zeigt erst die Kandidatendiagnose; die Werte sind
  Startwerte.
- **Dicht beieinander steckende Pfeile.** Das Verwerfen von Doppelten legt zwei
  sich berührende Schäfte zusammen. Das ist die bekannte Grenze *Stark
  überlappende Schäfte* der Haupt-Spec; die Kandidatendiagnose zeigt die
  betroffenen Treffer als nicht gefunden.

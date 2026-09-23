# Roll-Anker im Registrar — Design

Datum: 2026-09-23
Status: Design, abgestimmt am 23.9. (Ansatz A); Plan folgt in `docs/plans/`
Basis: Master `2a5305ed` (Korrekturebene im Haupt-Design), Korpus `ac64539`
Vorgänger: `2026-09-16-roll-in-the-measurement.md` (Rolle gemessen, Metrik
umgestellt), Haupt-Design `2026-09-09-arrow-detection-design.md` (Schritt 8
und Nachtrag vom 23.9.)

## Ziel

Konzentrische Ringe legen die Drehung um den Scheibenmittelpunkt nicht fest.
`Orientation.orient` setzt sie heute mit der Annahme „oben im Bild ist oben
auf der Auflage“. Wer das Telefon gerollt hält, verdreht damit die ganze
Registrierung. Auf dem Korpus sind es bis 36 Grad, 17 von 51 schrägen
Ansichten um mindestens 10 Grad. Für den Ringwert ist das harmlos. Für die
Gruppenanzeige der App nicht: Eine um 30 Grad verdrehte Aufnahme schiebt die
Gruppe von 12 Uhr nach 1 Uhr.

Der Anker legt die Drehung aus dem Bild selbst fest, sodass alle Aufnahmen
derselben Auflage dieselbe, ungefähr aufrechte Richtung bekommen.

## Entscheidungen

Abgestimmt am 23.9.2026:

1. **Beständigkeit statt Lot.** Ob „oben“ die Schwerkraft oder die
   Papieroberkante meint, ist bei wenigen Grad unerheblich. Gefordert ist,
   dass alle Ansichten einer Passe dieselbe Richtung liefern, auf etwa
   5 Grad genau.
2. **Ansatz A: Kantenrichtung im entzerrten Bild.** Papierrand und
   Strohkante liegen in der Ebene der Ringe und werden durch die Entzerrung
   zu geraden Linien, deren Winkel modulo 90 Grad die Rollung ist. Verworfen:
   der Lagesensor beim Auslösen (nur für App-Fotos, am Korpus nicht messbar;
   als spätere Vorrangquelle denkbar) und die Suche des Papiervierecks als
   Ganzes (genauer, aber empfindlicher gegen Pfeile, Schatten und
   angeschnittene Ecken, für 5 Grad nicht nötig).
3. **„Oben im Bild“ bleibt der Startwert.** Der Anker wählt von den vier
   Lagen die, die der heutigen Annahme am nächsten liegt, korrigiert also um
   höchstens 45 Grad.
4. **Der Anker lässt die Registrierung nie scheitern.** Ohne klaren Befund
   bleibt die Homographie wie heute und das Ergebnis sagt es offen.

## Architektur

### Baustein

`RollAnchor` im Paket `registration`, ein `object` wie `Orientation` und
`FaceWarp`:

```kotlin
object RollAnchor {
    /** Null when the image shows no clear straight edges around the face. */
    fun find(workingImage: Mat, imageToTarget: Mat3, debug: DebugSink = DebugSink.NONE): Anchor?

    class Anchor(
        val radians: Double,   // rotation to apply in target coordinates, in (-pi/4, pi/4]
        val strength: Double   // histogram peak over its mean
    )
}
```

`find` bekommt das Arbeitsbild und die orientierte Homographie in dessen
Pixeln. Es liefert den Winkel nur, wenn die Stärke die Schwelle erreicht;
die Schwelle ist eine Konstante in `RollAnchor`, gesetzt am Korpus (siehe
*Messung*).

### Einbau in `OpenCvFaceRegistrar`

Direkt nach `Orientation.orient`, noch im Arbeitsbild:

```
oriented  = Orientation.orient(refined.homography, rectified.imagedCentre)
anchor    = RollAnchor.find(small, oriented, debug)
anchored  = anchor?.let { Mat3.rotation(-it.radians) * oriented } ?: oriented
```

Danach wie bisher die Rückrechnung auf das Original. Die Drehung erfolgt um
den Ursprung der Auflagenkoordinaten, also um die Scheibenmitte; Radien und
damit Ringwerte bleiben exakt erhalten. `Orientation` selbst bleibt
unverändert.

### Ergebnis

`RegistrationOutcome.Registered` bekommt ein Feld:

```kotlin
class Roll(
    val anchored: Boolean,
    val correctionDegrees: Double,   // 0.0 when not anchored
    val strength: Double?            // null when no edges were found at all
)
```

Berichte und Debugbilder zeigen es; später kann die App bei nicht
verankerten Aufnahmen die Gruppenanzeige mit einem Hinweis versehen. Die
Pfeilfinder brauchen keine Änderung, sie arbeiten mit jeder Homographie.

## Das Verfahren

1. **Entzerren bis Radius 1,6.** Das Arbeitsbild wird als Graubild in
   Auflagenkoordinaten gezogen, Ausdehnung 1,6, etwa 640 px Kantenlänge
   (0,005 Radien je Pixel). `FaceWarp` bekommt dafür die Ausdehnung als
   Parameter mit Vorgabe 1,1, damit alle heutigen Aufrufer unverändert
   bleiben.
2. **Gültigkeitsmaske.** Eine Maske aus Einsen wird mit derselben
   Homographie entzerrt und um einige Pixel erodiert. Pixel außerhalb zählen
   nicht; sonst erzeugt der schwarze Rand des Entzerrens bei angeschnittenen
   Aufnahmen eine künstliche, perfekt gerade Kante.
3. **Gradienten im Kreisring 1,02 bis 1,6.** Sobel in x und y. Die
   aufgedruckten Ringe enden bei 1,0; außerhalb liegen Papierrand, Stroh,
   Rahmen und Hintergrund. Pixel unter einer Mindeststärke des Gradienten
   fallen weg.
4. **Querregel.** Gezählt wird ein Pixel nur, wenn sein Gradient höchstens
   30 Grad von der Radiusrichtung abweicht, die Kante also ungefähr quer zum
   Radius liegt. Der Papierrand erfüllt das auf dem Stück, das der Mitte am
   nächsten liegt. Pfeilschäfte laufen von ihrem Einschuss, meist im Gold,
   nach außen, also fast in Radiusrichtung, und fallen heraus. Das ist der
   Schutz dagegen, dass sechs parallele Schäfte (14.9., `stark-schraeg_08`)
   einen falschen Winkel vorgeben.
5. **Histogramm modulo 90 Grad.** Die Kantenrichtung jedes gezählten Pixels
   wird auf [0, 90) gefaltet, mit dem Betrag des Gradienten gewichtet, in
   90 Fächer gezählt und zyklisch leicht geglättet. Die Spitze, per Parabel
   durch die Nachbarfächer verfeinert, ist die Rollung des Papiers.
6. **Korrektur.** Der Winkel wird auf (−45, 45] Grad gebracht und als
   `radians` zurückgegeben; `Mat3.rotation(-radians)` dreht die Kanten auf
   die Achsen.
7. **Stärke.** Spitze durch Mittel des geglätteten Histogramms. Unter der
   Schwelle, oder wenn weniger als eine Mindestzahl Pixel gezählt wurde,
   gibt `find` null zurück.

Warum das trägt: Das Papier liegt in der Ebene der Ringe, seine Kanten werden
durch die Entzerrung exakt gerade und behalten ihren Winkel. Die Strohkante
liegt fast in der Ebene. Der Rahmen liegt nicht in der Ebene; seine Balken
erscheinen entzerrt schief, fallen aber großteils durch die Querregel und die
Begrenzung auf 1,6. Die aufgedruckten Kreise außerhalb 1,0 gibt es nicht; wo
Texturen (Gras, Stroh) keine Vorzugsrichtung haben, heben sie nur den Mittelwert.

## Fehlerfälle und Grenzen

| Fall | Verhalten |
|---|---|
| Auflage stark angeschnitten, Kreisring kaum im Bild | Zu wenige Pixel, `anchored = false`, Homographie wie heute |
| Kein klarer Winkel (unruhiger Hintergrund, Papier füllt das Bild) | Stärke unter der Schwelle, ebenso |
| Rollung nahe 45 Grad | Die Wahl zwischen zwei Lagen wird zufällig, das Ergebnis kann um 90 Grad springen. Kein Sonderfall: der Winkel steht in `Roll`, im Korpus kommt es nicht vor (höchstens 36 Grad), und das Papier nicht grob schräg zu fotografieren ist zumutbar. |
| Auflage schief aufgesteckt | Die Richtung folgt dem Papier, nicht dem Lot. Gewollt (Entscheidung 1). |
| Mehrspot-Auflagen | Der Anker läuft um den Spot, den der Registrar nimmt. Ob das trägt, zeigen erst annotierte 3-Spot-Fotos; offen wie bei der Korrekturebene. |

Laufzeit: ein Entzerren von rund 640² Pixeln und ein Sobel, einige
Millisekunden neben einer halben Sekunde für den Pfeilfinder.

## Messung und Tests

### Unit-Tests

`SyntheticFace.photograph` bekommt ein optionales Papierquadrat: weiß,
Halbkante 1,1 Radien, um einen gegebenen Winkel gedreht, vor dem heutigen
Hintergrund. Die Leinwand wächst dafür auf Ausdehnung 1,7. Ohne Papier bleibt
das Bild wie heute, und alle bestehenden Registrar-Tests bleiben unverändert
grün: Der gleichförmige Hintergrund liefert keinen Anker.

- Papier um −30, 0 und +30 Grad gedreht, schräg gesehen: Der Anker gewinnt
  den Winkel auf 1 Grad zurück, die Registrierung ist danach um höchstens
  1 Grad gegen die Wahrheit verdreht.
- Dasselbe mit sechs dunklen, radial laufenden Linien vom Gold nach außen:
  Der Winkel ändert sich nicht (Querregel).
- Ohne Papier: `anchored = false`, Homographie wie ohne Anker.
- Papier und Auflage an den Bildrand geschoben: kein Winkel aus dem
  Entzerrungsrand (Maske).
- Papier um 60 Grad gedreht: Korrektur um −30 Grad, also die nächste Lage.

### Registrierungsfehler ohne Rolle

`RegistrationError.between` vergleicht Punkt für Punkt mit der
Referenz-Homographie des Sidecars, und die ist mit „oben im Bild“
orientiert. Der Anker erschiene dort als Fehler, bei 10 Grad rund 0,17
Radien am Rand. `RegistrationError` bekommt deshalb zusätzlich den Fehler
nach der besten Drehung um die Mitte und diese Drehung in Grad.
`RegistrationCorpusRun`, `LearnedArrowCorpusRun` und die synthetischen Tests
messen die Form mit dem drehfreien Fehler; die Drehung erscheint im Bericht
als eigene Spalte. Die Schwellen der synthetischen Tests bleiben, wo sie sind.

### Korpusmessung: das Kriterium

Ein neuer Lauf `RollAnchorCorpusRun` für die schrägen und frontalen Ansichten
mit `shots[i].tipPx`. Die Ansichten einer Passe erkennt er an der
übernommenen Wahrheit: Alle Ansichten derselben Passe tragen dieselben
Einschüsse.

Je Ansicht: die geklickten Spitzen durch die Homographie des Laufs in
Auflagenkoordinaten, dagegen die übernommene Wahrheit, dazwischen die beste
Drehung um die Mitte. Je Passe: die Spannweite dieser Drehungen über ihre
Ansichten. Ohne Anker ist das die heutige relative Rollung, bis 36 Grad; mit
Anker muss sie verschwinden.

- **Kriterium: höchstens 5 Grad zwischen je zwei verankerten Ansichten
  derselben Passe.**
- Dazu der Anteil „nicht verankert“ und für jede Ansicht Winkel, Stärke und
  ein Debugbild: das entzerrte Bild bis 1,6 mit Maske, gezählten Pixeln und
  gefundener Richtung, daneben das Histogramm.
- Die Stärke-Schwelle wird an diesem Lauf gesetzt: die niedrigste, bei der
  keine verankerte Ansicht das Kriterium verletzt. Der Wert und die Zahl
  der verankerten Ansichten werden als Pins festgeschrieben.

Die Ansichten ohne `tipPx` (geerbte Fotos) und die Passen mit nur einer
Ansicht gehen in den Anteil „nicht verankert“ und in die Winkeltabelle ein,
nicht ins Kriterium.

### Folgemessung

`ArrowCorpusRun` und `LearnedArrowCorpusRun` laufen einmal neu, die Pins
werden neu gesetzt. Zwei Verschiebungen sind zu erwarten und werden im
Nachtrag dieser Spec beziffert:

- Treffer mit eigener Spitze gleichen schon heute gegen die Ablesung der
  Ansicht ab und sind vom Anker unberührt.
- Treffer ohne eigene Spitze liegen im Rahmen der steilsten Ansicht, also
  „oben im Bild“. Gegen die verankerte Registrierung verschieben sie sich um
  den Anker jener Ansicht. Die Zahlen können sich dadurch in beide
  Richtungen bewegen.
- Das gelernte Modell sieht entzerrte Bilder jetzt anders gedreht als beim
  Training. Es hat Rollungen bis 36 Grad gesehen, der Anker verringert sie
  eher.

## Risiken

- **Parallele Schäfte außerhalb der Querregel.** Ein Pfeil weit außen,
  flach gesehen, kann ein Stück Schaft quer zum Radius zeigen. Die
  Korpusmessung und die Debugbilder zeigen, ob das vorkommt; dann wird die
  Querregel enger oder die Maske schließt die Kandidaten des Pfeilfinders
  aus.
- **Hintergründe mit Vorzugsrichtung** (Latten, Zaun, Hallenwand) können
  eine falsche Spitze erzeugen, wenn das Papier kaum zu sehen ist. Die
  Querregel und die Begrenzung auf 1,6 dämpfen das; die Stärke-Schwelle
  fängt den Rest.
- **Die Messung ist relativ.** Sie zeigt, dass die Ansichten einer Passe
  übereinstimmen, nicht, dass „oben“ absolut stimmt. Das ist nach
  Entscheidung 1 gewollt; eine grobe Fehlrichtung um 90 Grad wäre in den
  Debugbildern sofort sichtbar.

## Was nicht Teil dieses Plans ist

Der Lagesensor als Vorrangquelle, der Einbau in die App (Schritt 8 des
Haupt-Designs, samt Anzeige von `Roll`), Mehrspot-Auflagen und neue Fotos.

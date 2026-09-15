# Gelernte Pfeilfindung — Befunde des PoC

Datum: 2026-09-15
Status: Befund, keine Umsetzung
Basis: Master `1d9ae659` (Pfeilfindung 3b, Schranken vom 2026-09-14), Korpus `c11af1d` (Spitzen je Ansicht)
Code: `../MyTargets-corpus/learn/` (Wegwerfcode, README dort mit allen Zahlen), Export-Test `ArrowCandidateExport` in `:detection`

## Frage

Ist klassisches CV der richtige Weg für die Pfeilfindung, oder soll ein
gelerntes Modell her? Anlass: Die Trefferquote der schrägen Fotos fiel von
34,9 % auf 22,9 %, als der Korpus wuchs, ohne dass sich am Finder eine Zeile
änderte. Das ist die Signatur handgemachter Schwellwerte.

Die Haupt-Spec sieht den Wechsel als *Upgrade-Pfad zu Ansatz C* vor: nur die
Pfeilsuche hinter `ArrowDetector` tauschen, Registrierung, Geometrie und
App-Integration behalten. Der PoC lief parallel zur klassischen Spur, außerhalb
der App, gemessen mit den Kennzahlen des Korpuslaufs.

## Was gebaut wurde

Zwei Teile, beide mit **Kreuzvalidierung nach Passe**: Die Serie vom
2026-09-14 zeigt jede Passe aus drei bis fünf Seiten, alle Ansichten einer
Passe liegen deshalb im selben Fold. Ein Split nach Foto hätte dieselben
Pfeile im Training und im Test. Die Schwelle jedes Folds wird auf dessen
Trainingsaufnahmen gewählt, nie auf den zurückgehaltenen.

**Wahrheit je Ansicht.** Sieben von 25 schrägen Ansichten tragen eine um 19
bis 34 Grad gedrehte Homographie (Roll der Kamera, siehe Korpus
`Fotoliste.md`). Beide Teile messen deshalb gegen die je Ansicht geklickten
Spitzen (`shots[i].tipPx`), durch die Homographie der eigenen Ansicht
gerechnet; der Roll hebt sich so auf. Einzelansichten nutzen die
Sidecar-Wahrheit, die in derselben Ansicht gemessen wurde. Die acht
frontalen Ansichten vom 14.9. mit übernommener Wahrheit bleiben draußen.

1. **Heatmap-Modell** (U-Net auf ResNet-18) auf dem entzerrten Auflagenbild:
   sagt je Pixel, ob dort ein Einschuss ist. 53 Ansichten, 301 Pfeile,
   37 Passen. Augmentierung mit Drehung über 360 Grad, Spiegelung,
   Farbjitter.
2. **Gelernter Bewerter** über den Kandidaten des klassischen Finders:
   `ArrowCandidateExport` schreibt jeden Kandidaten mit seinen Merkmalen aus
   Suche und Lauf; eine logistische Regression (und zum Vergleich ein
   Baum-Ensemble) lernt, echte von falschen zu trennen. Labels durch die
   Zuordnungsregel des Korpus (0,05 Radien plus Toleranz, nächste Paare
   zuerst).

## Ergebnisse, schräge Ansichten

### Heatmap-Modell

| Lauf | gefunden | Fehlfunde je Ansicht | Ring richtig | Positionsfehler Median |
|---|---|---|---|---|
| klassischer Finder (Schranken vom 14.9., geteilte Wahrheit) | 52 / 227 = 22,9 % | 0,65 | 30 / 32 | 0,013 |
| Heatmap, 512 px, 400 Schritte | 116 / 227 = 51,1 % | 2,8 | 99 / 116 | 0,009 |
| Heatmap, 768 px, 1300 Schritte, Fold 0 | 36,2 % (wie bei 512 px) | 1,5 (halbiert) | 17 / 17 | 0,006 |

Wo das Modell einen Pfeil findet, sitzt es genau. Was es verpasst, verpasst
es, weil es solche Fälle im Training kaum sah: Pfeile im Blau und Schwarz
(dunkler Schaft auf dunklem Grund), das einzige Foto mit Sonne und
Schlagschatten. Auch mit Schwelle 0,01 antwortet die Heatmap für fast die
Hälfte der Pfeile gar nicht. Längeres Training ändert die Quote nicht.
**Mit 300 Pfeilen ist der Datensatz die Grenze, nicht das Modell.**

### Gelernter Bewerter

38 Ansichten, 216 Pfeile, 611 georte Kandidaten, davon 203 echt.

| Auswahl | gefunden | Fehlfunde je Ansicht |
|---|---|---|
| heutige `CandidateSelection` (Lückenregel 0,15) | 59 / 216 = **27,3 %** | 0,74 |
| Orakel: jeder zugeordnete Kandidat, Obergrenze der Suche | 175 / 216 = **81,0 %** | 0 |
| reine Zuversicht ≥ 0,5, höchstens `expectedShots` | 58,8 % | 2,26 |
| logistische Regression, Schwelle nach F1 | 64,4 % | 1,42 |
| logistische Regression, Schwelle mit Fehlfund-Grenze 0,65 | **56,5 %** | **0,61** |
| Gradient Boosting, beide Schwellen | 60,2 % / 69,0 % | 0,82 / 1,24 |
| logistische Regression **ohne Radius**, Fehlfund-Grenze | 43,1 % | 0,66 |

Ring richtig bei allen um 85 %, Positionsfehler im Median 0,008. Die Kurve
der logistischen Regression, out-of-fold: Schwelle 0,7 gibt 57 % bei 0,58
Fehlfunden je Ansicht, 0,6 gibt 62 % bei 1,0, 0,5 gibt 66 % bei 1,3.

Merkmale: Kontrast, Schaftkontrast, Score, Zuversicht, Abstand der Geraden
von `Q`, Verfeinerung (`REFINED`/`RAN_OUT`/`NO_SHAFT`), Länge, Radius der
Spitze, Rang und Anteil am besten Score, Kandidatenzahl, Fußpunktabstand,
und zwei Paar-Merkmale: ob die Spitze auf dem Segment eines anderen
Kandidaten liegt, und der Abstand zur nächsten anderen Spitze. Stärkste
Gewichte: Radius (negativ), Abstand zur nächsten Spitze (positiv), auf
fremdem Schaft (negativ), `NO_SHAFT` (negativ), Schaftkontrast (positiv).

## Befunde

1. **Die Suche ist nicht der Engpass, die Auswahl ist es.** Gegen die
   Wahrheit je Ansicht hat die klassische Suche für 81 % der schrägen Pfeile
   den richtigen Kandidaten. Die Lückenregel behält 27 %. Die 22,9 % der
   Schranken sind zusätzlich durch die gedrehten Ansichten gedrückt.
2. **Ein Bewerter aus den vorhandenen Merkmalen verdoppelt die Ausbeute bei
   gleicher Fehlfundrate** (56,5 % gegen 27,3 %). Eine gewichtete Summe von
   rund zehn Zahlen reicht; Bäume holen nichts Wesentliches mehr heraus. Die
   Merkmale sind die Grenze, nicht das Modell. In Kotlin ist das eine
   Handvoll Zeilen ohne neue Abhängigkeit.
3. **Der Radius als Merkmal ist eine Falle.** 116 der 315 falschen Kandidaten
   der schrägen Ansichten liegen im Weiß (Radius 0,8 bis 1,0), wo der Korpus
   genau einen echten Pfeil hat: gedruckte Ringlinien und der Papierrand. Ein
   Bewerter mit Radius lernt "Weiß ist falsch" und benachteiligt jeden
   Schützen, der das Weiß trifft. Ohne Radius fällt die Quote auf 43 %. Die
   Quelle gehört klassisch beseitigt (steht als "gedruckte Ringlinien nahe am
   Auflagenrand" in den *Offenen Punkten* von 3b), der Radius bleibt aus dem
   Bewerter heraus.
4. **51 der 315 falschen Kandidaten sind Schaftstücke**, deren Spitze auf
   dem Segment eines anderen Kandidaten liegt. Das Paar-Merkmal fängt sie;
   die Verschmelzungsregel aus den *Offenen Punkten* täte dasselbe.
5. **Frontal wird mit dem Bewerter nicht besser** (14 bis 22 %). Die
   Geometrie frontaler Kandidaten ist anders, und die Schwelle ist auf
   schräge Ansichten eingestellt. Bleibt der schwächere Fall der Haupt-Spec.
6. **Das Heatmap-Modell lohnt erst, wenn es die 81 % der Suche übertrifft.**
   Das braucht mehr Daten: Copy-Paste-Augmentierung annotierter Schäfte
   (Nocken liegen für 141 Pfeile vor) und die Fotos der Fotoliste, vor allem
   Sonne mit Schlagschatten und Pfeile im Schwarz.

## Empfehlung

Klassisch bleibt Suche und Geometrie; **gelernt wird die Auswahl**, in der
kleinsten Form, die dort hilft. Reihenfolge:

1. Die falschen Kandidaten im Weiß klassisch beseitigen (3c).
2. Den Bewerter ohne Radius neu messen. Kommt er an die 56 % heran, ersetzt
   er die Lückenregel in `CandidateSelection`: gewichtete Summe plus
   Schwelle, Gewichte auf dem ganzen Korpus gelernt, die
   Kreuzvalidierungszahlen als Beleg in den Bericht. Die Paar-Merkmale
   brauchen die ganze Kandidatenliste, die `select` ohnehin bekommt.
3. Der Korpuslauf der App misst gegen die Spitzen je Ansicht
   (`tipPx` durch `registration.imageToTarget` des Sidecars), sonst bleiben
   die Schranken durch die gedrehten Ansichten gedrückt. Bis dahin sind 22,9 %
   eine Untergrenze.
4. Das Heatmap-Modell bleibt liegen, bis der Korpus gewachsen ist. Es ist die
   Option, wenn auch die Suche an ihre Grenze kommt.

## Was der PoC nicht beantwortet

- Laufzeit und Größe eines Netzes auf dem Telefon; kein TFLite-Export.
- Die Registrierung: Der PoC nutzt die Homographie aus dem Sidecar, die App
  rechnet ihre eigene; beide stimmen laut Korpus auf 0,01 Radien überein.
- Pfeile, die niemand annotieren konnte (die vier im Schwarz).
- F-Droid und vortrainierte Gewichte, falls je ein Netz in die App kommt.

## Wo liegt was

| Ort | Inhalt |
|---|---|
| `../MyTargets-corpus/learn/` | `prepare.py`, `train.py`, `evaluate.py`, `scorer.py`, `metrics.py`, README mit allen Zahlen |
| `../MyTargets-learn/` | alles Erzeugte: entzerrte Bilder, Modelle, Heatmaps, `candidates.json`, `scorer_report.md`. **Bewusst außerhalb des Korpus:** `CorpusLoader` durchsucht jeden Unterordner und nimmt jedes Bild als Korpusfoto |
| `detection/src/test/.../arrows/ArrowCandidateExport.kt` | schreibt `candidates.json` in den Berichtsordner, läuft über `testDevDebugUnitTest --tests "*ArrowCandidateExport*"` |

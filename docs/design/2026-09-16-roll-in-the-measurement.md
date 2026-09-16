# Was die Rollverdrehung die Messung kostet

Stand 16.9.2026, gemessen auf beiden Serien mit je Ansicht geklickten Spitzen.
Gehört zu den offenen Punkten aus `2026-09-11-detection-arrows-design.md` und
zum Fund *Abweichende Ansichten* in der `Fotoliste.md` des Korpus.

## Die Frage

Die Registrierung pinnt die eine Freiheit, die konzentrische Ringe offen
lassen, mit der Annahme „oben im Bild ist oben auf der Auflage“. Hält man das
Telefon gerollt, ist die Homographie um den Scheibenmittelpunkt verdreht. Der
Ringwert bleibt davon unberührt, der Radius ist drehinvariant. Die **Messung**
nicht: Die Wahrheit einer Passe liegt im Rahmen der steilsten Ansicht, der
Pfeillauf registriert jede Ansicht selbst, und wo die beiden Rollen
auseinanderliegen, landet ein richtig gefundener Pfeil verdreht und zählt als
Fehlschlag plus Fehlfund. Wie viel ist das?

## Wie gemessen

Für jede schräge Ansicht mit `shots[i].tipPx` (55 Ansichten, davon 51 vom
App-Registrar registriert) zwei Zielmengen:

- **Wahrheit** T: die Einschüsse des Sidecars, übernommen aus der steilsten
  Ansicht.
- **Eigene Ablesung** O: die in dieser Ansicht geklickte Spitze, durch die
  Homographie des App-Registrars (aus dem Kandidatenexport) in
  Auflagenkoordinaten. Das ist, wo ein perfekter Finder den Pfeil in dieser
  Ansicht melden würde.

Dagegen jeweils die Kandidaten des Pfeillaufs, akzeptierte und alle, mit dem
Tor der Metrik (0.05 plus `positionTolerance`), gierig nächster zuerst.
Die Rolle je Ansicht ist die Drehung um den Mittelpunkt, die T am besten auf
O abbildet. Skript: `tools/roll_effect.py` im Korpus-Repo, Eingabe der
Kandidatenexport (`ArrowCandidateExport` auf dem Branch `poc/candidate-export`)
und die Sidecars.

## Ergebnis

51 Ansichten, 292 Pfeile. 17 Ansichten sind um mindestens 10 Grad gerollt
(14.9.: sieben zwischen 15 und 36 Grad; 15.9.: sieben zwischen 10 und 28
Grad, alle in dieselbe Richtung). 57 der 292 Pfeile liegen in ihrer Ansicht
weiter als das Tor von der übernommenen Wahrheit entfernt.

| | gegen die Wahrheit | gegen die eigene Ablesung |
|---|---|---|
| akzeptierte Kandidaten, die einen Pfeil treffen | 55 (18.8 %) | 65 (22.3 %) |
| Fehlfunde | 57 | 47 |
| irgendein Kandidat trifft den Pfeil (Suchdecke) | 182 (62.3 %) | 216 (74.0 %) |

Nach Serie getrennt:

| Serie | Ansichten | Pfeile | jenseits Tor | Treffer W / E | Fehlfunde W / E | Suchdecke W / E |
|---|---|---|---|---|---|---|
| 14.9. (Sonne, 25–30 m) | 23 | 130 | 49 | 22 / 29 | 21 / 14 | 76 / 107 |
| 15.9. (bedeckt, 10 und 25 m) | 28 | 162 | 8 | 33 / 36 | 36 / 33 | 106 / 109 |

Zwei Dinge sind daran zu lesen.

**Erstens: Die Rolle kostet die Messung rund drei Prozentpunkte Trefferquote
und ein Sechstel der Fehlfunde**, und sie drückt die Suchdecke von 74 % auf
62 %. Das ist real, aber nicht die Hauptsache: Auch gegen die eigene Ablesung
bleiben von 216 gefundenen Pfeilen nur 65 nach der Auswahl übrig. Der Engpass
ist die Auswahl, wie der PoC vom 15.9. sagte, nicht die Registrierung.

**Zweitens: Die Rolle wirkt mit dem Radius.** Eine Drehung um 20 Grad
verschiebt einen Pfeil bei Radius 0.15 um 0.05, das bleibt im Tor; bei
Radius 0.5 um 0.17, das ist weit draußen. Deshalb kostet sie auf der Serie
vom 14.9. (Treffer bis in den Sechser, 49 Pfeile jenseits des Tors) viel und
auf der vom 15.9. (10 m, fast alles im Gold, 8 jenseits) fast nichts, obwohl
dort ebenso viele Ansichten gerollt sind. Bei der Serie vom 15.9. ist
außerdem in 17 der 28 Ansichten die eigene Ablesung per Konstruktion die
Wahrheit, weil dort die Spitzen geklickt und nicht automatisch gemessen
wurden.

Die vollständige Tabelle je Ansicht steht am Ende.

## Folgerung

1. **Die Messung soll gegen die eigene Ablesung der Ansicht laufen, wo es
   eine gibt.** `shots[i].tipPx` durch die Homographie des Laufs ist der
   Ort, an dem der Finder den Pfeil in dieser Ansicht sehen kann; die
   übernommene Wahrheit ist es nur bis auf die Rolle. Das ist eine Änderung
   in der Metrik (Wahrheit je Ansicht ableiten, wo `tipPx` steht, sonst wie
   bisher), keine im Registrar, und sie nimmt die Rolle vollständig aus der
   Messung. Die Pins werden danach einmal neu gesetzt: erwartbar rund 22 %
   statt 21 %, und weniger Fehlfunde.
2. **Der Registrar braucht deshalb keinen Anker gegen die Rolle**, solange
   die App nur Ringwerte zeigt. Erst wenn sie Positionen auf der Auflage
   zeichnet, ist die Drehung sichtbar; dann gehört der Anker (Aufdruck,
   Papierkante, Schaftrichtung) in Plan 3c oder später.
3. **Die Reihenfolge bleibt:** erst die Auswahl, das ist der Faktor drei
   zwischen Suchdecke und Ergebnis; die gelernte Auswahl auf dem gewachsenen
   Korpus ist der nächste Schritt.

## Tabelle je Ansicht

Rolle in Grad, Rest in Radien (Median der Abstände eigene Ablesung zu
Wahrheit, vor und nach Herausrechnen der Drehung), W = gegen die Wahrheit,
E = gegen die eigene Ablesung.

| Ansicht | Pfeile | Rolle | Rest vor / nach | jenseits Tor | akzeptiert | Treffer W / E | Fehlfunde W / E | Suchdecke W / E |
|---|---|---|---|---|---|---|---|---|
| 2026-09-14_sonne_stark-schraeg_22 | 6 | −35.5 | 0.228 / 0.013 | 4 | 0 | 0 / 0 | 0 / 0 | 3 / 4 |
| 2026-09-14_sonne_stark-schraeg_16 | 5 | +32.9 | 0.343 / 0.007 | 5 | 4 | 1 / 3 | 3 / 1 | 2 / 4 |
| 2026-09-14_sonne_stark-schraeg_07 | 6 | +32.0 | 0.152 / 0.040 | 6 | 0 | 0 / 0 | 0 / 0 | 0 / 5 |
| 2026-09-14_sonne_stark-schraeg_09 | 6 | +29.3 | 0.196 / 0.011 | 6 | 0 | 0 / 0 | 0 / 0 | 1 / 5 |
| 2026-09-14_sonne_stark-schraeg_19 | 6 | +28.5 | 0.119 / 0.014 | 6 | 0 | 0 / 0 | 0 / 0 | 2 / 6 |
| 2026-09-15_bedeckt_stark-schraeg_02 | 6 | −27.5 | 0.076 / 0.023 | 4 | 0 | 0 / 0 | 0 / 0 | 2 / 3 |
| 2026-09-14_sonne_stark-schraeg_13 | 6 | +25.9 | 0.134 / 0.024 | 5 | 0 | 0 / 0 | 0 / 0 | 2 / 6 |
| 2026-09-15_bedeckt_leicht-schraeg_11 | 6 | −19.2 | 0.044 / 0.008 | 1 | 4 | 2 / 2 | 2 / 2 | 3 / 3 |
| 2026-09-14_sonne_stark-schraeg_23 | 5 | +14.5 | 0.084 / 0.042 | 3 | 1 | 0 / 1 | 1 / 0 | 2 / 5 |
| 2026-09-15_bedeckt_leicht-schraeg_05 | 6 | −13.8 | 0.042 / 0.019 | 1 | 1 | 0 / 0 | 1 / 1 | 3 / 4 |
| 2026-09-15_bedeckt_leicht-schraeg_06 | 6 | −12.0 | 0.031 / 0.005 | 0 | 1 | 0 / 0 | 1 / 1 | 4 / 3 |
| 2026-09-15_bedeckt_leicht-schraeg_03 | 6 | −11.5 | 0.028 / 0.005 | 1 | 2 | 1 / 2 | 1 / 0 | 4 / 3 |
| 2026-09-15_bedeckt_leicht-schraeg_14 | 6 | −11.1 | 0.031 / 0.008 | 0 | 2 | 1 / 1 | 1 / 1 | 4 / 3 |
| 2026-09-15_bedeckt_leicht-schraeg_01 | 6 | −10.1 | 0.030 / 0.008 | 0 | 5 | 1 / 1 | 4 / 4 | 4 / 5 |
| 2026-09-15_bedeckt_leicht-schraeg_02 | 6 | −8.9 | 0.021 / 0.011 | 0 | 2 | 1 / 1 | 1 / 1 | 5 / 5 |
| 2026-09-14_sonne_stark-schraeg_02 | 5 | −8.3 | 0.058 / 0.025 | 2 | 0 | 0 / 0 | 0 / 0 | 2 / 4 |
| 2026-09-14_sonne_stark-schraeg_05 | 6 | −7.7 | 0.033 / 0.035 | 2 | 0 | 0 / 0 | 0 / 0 | 4 / 6 |
| 2026-09-15_bedeckt_leicht-schraeg_04 | 6 | −7.0 | 0.017 / 0.003 | 1 | 2 | 0 / 2 | 2 / 0 | 3 / 5 |
| 2026-09-14_sonne_stark-schraeg_11 | 6 | −5.6 | 0.036 / 0.028 | 1 | 4 | 3 / 4 | 1 / 0 | 4 / 5 |
| 2026-09-14_sonne_stark-schraeg_14 | 5 | −5.6 | 0.028 / 0.038 | 1 | 4 | 0 / 0 | 4 / 4 | 3 / 3 |
| 2026-09-14_sonne_stark-schraeg_12 | 6 | +5.4 | 0.033 / 0.026 | 1 | 4 | 2 / 3 | 2 / 1 | 4 / 5 |
| 2026-09-15_bedeckt_leicht-schraeg_17 | 6 | −5.1 | 0.015 / 0.010 | 0 | 1 | 1 / 1 | 0 / 0 | 3 / 3 |
| 2026-09-14_sonne_stark-schraeg_04 | 5 | +3.6 | 0.058 / 0.048 | 1 | 5 | 2 / 3 | 3 / 2 | 3 / 3 |
| 2026-09-14_sonne_stark-schraeg_21 | 6 | −3.5 | 0.022 / 0.017 | 1 | 0 | 0 / 0 | 0 / 0 | 4 / 4 |
| 2026-09-15_bedeckt_stark-schraeg_07 | 6 | −3.5 | 0.010 / 0.003 | 0 | 6 | 3 / 3 | 3 / 3 | 5 / 5 |
| 2026-09-14_sonne_stark-schraeg_17 | 6 | −3.2 | 0.032 / 0.020 | 0 | 0 | 0 / 0 | 0 / 0 | 5 / 5 |
| 2026-09-14_sonne_stark-schraeg_24 | 5 | −3.0 | 0.020 / 0.029 | 0 | 0 | 0 / 0 | 0 / 0 | 4 / 4 |
| 2026-09-15_bedeckt_leicht-schraeg_22 | 6 | +2.3 | 0.008 / 0.001 | 0 | 6 | 3 / 3 | 3 / 3 | 5 / 5 |
| 2026-09-14_sonne_stark-schraeg_08 | 6 | −1.8 | 0.017 / 0.012 | 0 | 5 | 3 / 3 | 2 / 2 | 5 / 6 |
| 2026-09-14_sonne_stark-schraeg_20 | 6 | +1.4 | 0.012 / 0.009 | 2 | 3 | 3 / 3 | 0 / 0 | 4 / 4 |
| 2026-09-14_sonne_stark-schraeg_10 | 6 | −0.9 | 0.010 / 0.011 | 0 | 0 | 0 / 0 | 0 / 0 | 4 / 4 |
| 2026-09-14_sonne_stark-schraeg_15 | 5 | +0.8 | 0.007 / 0.003 | 0 | 3 | 2 / 2 | 1 / 1 | 4 / 4 |
| 2026-09-14_sonne_stark-schraeg_06 | 6 | −0.6 | 0.064 / 0.066 | 3 | 6 | 3 / 4 | 3 / 2 | 3 / 4 |
| 2026-09-15_bedeckt_stark-schraeg_04 | 6 | +0.5 | 0.001 / 0.000 | 0 | 3 | 0 / 0 | 3 / 3 | 3 / 3 |
| 2026-09-14_sonne_stark-schraeg_18 | 6 | +0.4 | 0.016 / 0.014 | 0 | 0 | 0 / 0 | 0 / 0 | 6 / 6 |
| 2026-09-15_bedeckt_leicht-schraeg_12 | 6 | +0.3 | 0.001 / 0.001 | 0 | 5 | 0 / 0 | 5 / 5 | 1 / 1 |
| 2026-09-15_bedeckt_leicht-schraeg_13 | 3 | +0.3 | 0.001 / 0.001 | 0 | 1 | 1 / 1 | 0 / 0 | 2 / 2 |
| 2026-09-15_bedeckt_leicht-schraeg_08 | 6 | +0.2 | 0.001 / 0.001 | 0 | 1 | 1 / 1 | 0 / 0 | 4 / 4 |
| 2026-09-15_bedeckt_stark-schraeg_06 | 6 | +0.2 | 0.001 / 0.001 | 0 | 6 | 4 / 4 | 2 / 2 | 5 / 5 |
| 2026-09-14_sonne_stark-schraeg_01 | 5 | +0.1 | 0.006 / 0.006 | 0 | 4 | 3 / 3 | 1 / 1 | 5 / 5 |
| 2026-09-15_bedeckt_leicht-schraeg_07 | 6 | +0.1 | 0.001 / 0.000 | 0 | 3 | 0 / 0 | 3 / 3 | 3 / 3 |
| 2026-09-15_bedeckt_leicht-schraeg_10 | 6 | +0.1 | 0.001 / 0.001 | 0 | 0 | 0 / 0 | 0 / 0 | 4 / 4 |
| 2026-09-15_bedeckt_leicht-schraeg_15 | 3 | +0.1 | 0.001 / 0.001 | 0 | 1 | 1 / 1 | 0 / 0 | 3 / 3 |
| 2026-09-15_bedeckt_leicht-schraeg_19 | 6 | −0.1 | 0.002 / 0.002 | 0 | 0 | 0 / 0 | 0 / 0 | 4 / 4 |
| 2026-09-15_bedeckt_stark-schraeg_08 | 6 | +0.1 | 0.000 / 0.000 | 0 | 3 | 1 / 1 | 2 / 2 | 3 / 3 |
| 2026-09-15_bedeckt_leicht-schraeg_09 | 6 | +0.0 | 0.006 / 0.006 | 0 | 4 | 3 / 3 | 1 / 1 | 3 / 4 |
| 2026-09-15_bedeckt_leicht-schraeg_16 | 6 | +0.0 | 0.001 / 0.001 | 0 | 0 | 0 / 0 | 0 / 0 | 5 / 5 |
| 2026-09-15_bedeckt_leicht-schraeg_18 | 6 | −0.0 | 0.001 / 0.001 | 0 | 1 | 1 / 1 | 0 / 0 | 6 / 6 |
| 2026-09-15_bedeckt_stark-schraeg_01 | 6 | +0.0 | 0.002 / 0.002 | 0 | 0 | 0 / 0 | 0 / 0 | 4 / 4 |
| 2026-09-15_bedeckt_stark-schraeg_03 | 6 | −0.0 | 0.001 / 0.001 | 0 | 5 | 4 / 4 | 1 / 1 | 5 / 5 |
| 2026-09-15_bedeckt_stark-schraeg_09 | 6 | −0.0 | 0.001 / 0.001 | 0 | 4 | 4 / 4 | 0 / 0 | 6 / 6 |

Vier schräge Ansichten mit Spitzen fehlen, weil der App-Registrar dort keine
Scheibe findet: `2026-09-14_sonne_leicht-schraeg_01`, `_stark-schraeg_03`,
`2026-09-15_bedeckt_leicht-schraeg_20`, `_21`.

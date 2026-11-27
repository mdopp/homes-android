# Homes — Design Guideline

Der visuelle Standard für App-Screens **und** Widgets, damit alles wie *ein*
Produkt wirkt. Neue UI/Widgets richten sich hiernach; Abweichungen sind bewusst
zu begründen. Spiegelt das Web-Frontend von Homes.

> **Akzente (#130).** In der App ist der Akzent **orange**. Der Kopf des
> Startbildschirms trägt den festen Look des Assistenten (Besitzer,
> 2026-11-02): **Phosphorgrün `#3DDC84` auf `#0C120E`**, die Farben des
> App-Icons; die Box liefert dafür nur den Namen (`/napi/assistent`, sonst
> „Homes“), keine Farben, kein Symbol. App-Icon-Shortcuts und Widgets werden
> *neben fremden* Icons gesehen; sie behalten das **Markenblau**.

## Palette (`res/values/colors.xml`)
| Rolle | Token | Hex |
|---|---|---|
| Hintergrund | `homes_bg` | `#070709` |
| Erhöht (Statusbar/Nav) | `homes_bg_elevated` | `#0C0C0E` |
| **Akzent (Primär, In-App)** | `homes_accent` | `#F97316` (Orange) |
| Assistent: Akzent / Grund (Startbildschirm, App-Icon) | `terminal_green` / `terminal_ground` | `#3DDC84` / `#0C120E` |
| Akzent gedrückt | `homes_accent_dark` | `#EA580C` |
| Akzent-Tint (12 %) | `homes_accent_faint` | `#1FF97316` |
| **Markenblau** (Icon/Shortcuts/Widgets/Glow) | `homes_brand_blue` | `#3B82F6` |
| Markenblau gedrückt | `homes_brand_blue_dark` | `#2563EB` |
| Text | `homes_text` | `#F2F2F5` |
| Text gedämpft | `homes_text_muted` | `#9AA0AE` |
| Text schwach | `homes_text_faint` | `#6B7280` |
| Energie „liefert"/„verbunden" | `energy_supply` | `#66BB6A` |
| Energie „bezieht" | `energy_draw` | `#EF5350` |
| Batterie | `energy_battery` | `#7E9CFF` |

**Domänen-Akzente** (Geräte-Widgets, aktiver Zustand): Licht `#FFC107`,
Cover/Jalousie `#7E9CFF`, Schalter `#66BB6A`, Klima `#FF8A65`.

> Regel: **In-App-Akzent ist immer `homes_accent`, Akzent auf dem
> Android-Startbildschirm immer `homes_brand_blue`.** `#7E9CFF` ist die
> *Batterie/Cover*-Farbe — nicht als generischer Akzent verwenden.

## Tokens (`res/values/dimens.xml`)
- Radien: `radius_sm 12` (Felder/Chips/Kacheln) · `radius_md 16` (Buttons) · `radius_lg 20` (Cards).
- Abstände: `space_xs 8 · sm 12 · md 16 · lg 24 · xl 32`; Screen-Gutter 24dp.
- Typo: `text_hero 40 · headline 22 · title 17 · body 15 · label 13 · tile 12`.
- Markenblock: `brand_lockup_top · brand_figure_height · brand_rule_bottom ·
  hero_glow_size · text_hero` (Erstauftritt) und dieselben mit `_compact`
  (gekoppelt, #130) — `OnboardingHomeActivity.renderBrand` schaltet um.
- Text-Styles: `Text.Homes.Hero/Headline/Title/Body/Label/Tile` (`res/values/themes.xml`).

## Widgets — allgemein
- Hintergrund immer **`@drawable/widget_bg`** (22dp, dezenter Vertikal-Verlauf, 1dp-Hairline).
- **RemoteViews-sicher:** nur `LinearLayout/FrameLayout/RelativeLayout/GridLayout`,
  `TextView/ImageView/ProgressBar/Button/ImageButton/ListView` — **nie** ein
  nacktes `<View>` (bricht RemoteViews → „Widget kann nicht geladen werden").
- Icons tintbar, aktiver Zustand = Domänen-Akzent, inaktiv = gedämpft.
- Größenadaptiv (tiny/small/wide/medium/large); kein großer leerer Block.

## Auslöser-Kacheln (Kamera, Sprechen, künftige „öffnet X"-Kacheln) — **einheitlich!**
Kacheln, die nur eine Aktion auslösen, folgen **einem** Muster:

1. **Aufbau:** vertikal, zentriert — **Icon oben, einzeiliges Label darunter**. Root
   `widget_bg`, Padding `space_sm` (12dp).
2. **Icon:** gefüllter **Markenblau-Kreis** (`tile_accent_circle`, #3B82F6) mit
   weißem Glyph — signalisiert „antippen". **Gleiche Größe** überall (Kreis **52dp**,
   Glyph-Padding 12dp), **zentriert**. Kein flaches getöntes Glyph, keine
   abweichende Farbe (`#7E9CFF` etc.).
3. **Label:** **einzeilig**, `Text.Homes.Tile`-Größe (12–13sp) **bold**,
   `homes_text`, `maxLines=1`. **Keine zweite/abgeschnittene Zeile** (kein
   „Kamera öffnen"-Untertitel, der zu „Kamer…" trunkiert).
4. **Konsistenz:** gleiche Icon-Größe, gleiche Position, gleiche Label-Zeilenzahl,
   gleiche Abstände über *alle* Auslöser-Kacheln.

> Kurz: Kamera und Sprechen sollen **identisch** aufgebaut sein — nur Icon + Wort
> unterscheiden sich. Ein gemeinsames Kreis-Drawable (`tile_accent_circle`) und
> ein gemeinsames Kachel-Layout verwenden.

## Geräte-Widgets (Licht/Schalter/Cover)
- **Steuertasten füllen die Breite** (gewichtsbasiert, `layout_weight`), damit bei
  **keiner** Größe ein Button abgeschnitten wird — **nie** feste dp-Breiten, die
  überlaufen (sonst wird z.B. der ▼-Button rechts geclippt).
- **1×1 (TINY):** **Name behalten** (einzeilig, oben, ellipsiert) + der **Toggle
  ist ein tippbares, zustandsgetöntes Domänen-Icon** (kein Button-Kasten, kein „○") —
  Tap = Primäraktion, Name-Tap = PWA. Zustandsbalken unten wie gehabt (#57).
- **Nicht mögliche Aktionen ausgrauen:** eine Cover am Anschlag → die unmögliche
  Richtung ist deaktiviert/gedämpft (offen ⇒ ▲ grau & inert, zu ⇒ ▼ grau & inert).
  Ein grauer Button löst nichts aus.
- Zustand als Balken/Prozent unten; Akzent = Domänenfarbe im aktiven Zustand.

## App-Screens
Der Startbildschirm (`activity_home.xml`, Besitzer 2026-11-27: „nichts
versteckt; jeder Bereich spricht nur so laut, wie es gerade wichtig ist“), von
oben: der Kopf (App-Icon, Name des Assistenten, Linie in Phosphorgrün auf
`#0C120E`, der Claim); **Jetzt** — höchstens zwei Hinweise, nur wenn etwas
Aufmerksamkeit will, je mit ×, sonst fehlt der Abschnitt (`ui/Jetzt.kt`);
**Deine Geräte** — was Aufmerksamkeit will zuerst, dann was an ist, dann der
Rest; **Deine Aufgaben** in einer Zeile mit Zahl (grau, orange erst wenn
etwas dringend ist); **Alles auf einen Blick** — jeder Bereich eine Kachel,
drei je Reihe (Bild, Name, eine Zeile Stand, ein grüner Punkt für „neu“,
`item_bereich.xml`, `ui/Ueberblick.kt`), darunter leise die Spuren;
**Dieses Telefon · <Name>** — eine Zeile (orange, wenn eine Sicherung
überfällig ist) zu einer Seite mit „Sichert auf die Box“, „Ist verbunden
mit“, „Meldet an die Box“ und „Kopplung“ (mit der Version,
`ui/TelefonActivity.kt`); zuletzt **Widget hinzufügen** als Liste. Ungekoppelt
nur die Kopplung (Adresse, „Koppeln", QR-Code). Konsistente
Radien/Abstände/Typo aus den Tokens; runde Karten `card_homes`, alle mit
demselben Innenabstand `card_padding`. Karten sagen in **einer** Zeile, was
etwas tut.

## Deine Geräte — Kacheln
Die Favoriten auf dem Startbildschirm sind Kacheln (Besitzer, 2026-11-04,
`ui/FavoritenAnsicht.kt`, `layout/item_device_tile*.xml`), im Bild der Widgets:
- **Das Bild sagt den Zustand** (`DeviceLook.icon`): Lampe an/aus, Garagentor
  auf/zu, Jalousie oben/unten — eine Jalousie dazwischen aus denselben zwei
  Bildern, das geschlossene bis zur Position über das offene gelegt. Dazu ein
  Kreis in der Zustandsfarbe (`DeviceLook.tint`), und was an oder offen ist,
  bekommt einen leichten Farbschimmer der Kachel. **Worte nur, wo das Bild es
  nicht kann:** „öffnet …“, „nicht erreichbar“, „klemmt“, die Temperatur.
- **Zwei Kacheln je Reihe:** das Bild, rechts daneben der Wert („70 %“, ein
  gedimmtes Licht aus: „aus“, Akzent, gepunktet, tippen öffnet das Blatt),
  darunter der Name ohne angehängtes Raumwort (`DeviceLook.shortName`).
- **Eine Abdeckung nimmt die ganze Reihe:** Bild, Name mit Position darunter,
  rechts ▲ ■ ▼ als Bildknöpfe (40 × 48 dp) — drei davon passen auf einem
  360-dp-Handy nicht in eine halbe Kachel. Kachel oder Position antippen öffnet
  das Blatt (Auf, ½, Zu, Stopp, Regler). Eine Kachel ohne Nachbarn wird ebenso
  eine ganze Zeile.
- **Breitere Bildschirme** (Besitzer, 2026-11-04: Foldable, Tablet; gemischt
  „zu unruhig“): ab zwei Zellen von 312 dp — so breit braucht eine Abdeckung
  ihre eine Zeile — ein Raster gleich breiter Zellen, jede Kachel einzeilig in
  einer Zelle, in der Reihenfolge der Favoriten. Darunter (Handy, auch 600 dp)
  bleibt es wie oben.
- **Keine Ränder**, Kachelgrund `#141419`, Ecken 20 dp. „Alle →“ steht im Kopf
  der Sektion. Ist die Box nicht erreichbar, sind alle Kacheln grau unter der
  „Stand …“-Zeile.

## Spuren — was die Box nicht hat
Was die Box nicht kann (homes ADR 0001 §10), bleibt als leise Spur stehen,
nie versteckt und nie wie ein Fehler: Titel in `homes_text_muted`, darunter
der Stand in `homes_text_faint`, Icon in `homes_text_faint`, „Hier
anfordern“ als Geister-Knopf (`Widget.Homes.Button.Control`). Kein Rot,
kein Warnzeichen, keine Fehlerwörter (`layout/item_spur.xml`, `ui/Spuren.kt`).

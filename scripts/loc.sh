#!/usr/bin/env sh
# One line budget per package of the app's production code (CLAUDE.md "Code
# gates"), the idea of laib's scripts/loc.sh: production lines are capped,
# test lines are counted but not capped. Lines are `wc -l` of the .kt files,
# comments and blank lines included.
#
# The caps were set at each package's size on 2026-11-03 plus 5 %. Crossing
# one means raising it in the same PR, with its reason next to the number and
# the owner's approval — or making room in the package first (the proposals
# in the PR that brought this script). The caps are tight on purpose: each
# package is at 95 % or more from the start. A new package needs its own
# line here, else CI fails.
set -e
cd "$(dirname "$0")/.."
src=app/src/main/java/cloud/dopp/homes
bad=0
kt() { find "$@" -name '*.kt' -exec cat {} + 2>/dev/null | wc -l; }
budget() { # budget <package> <cap>  — "." is the files beside the packages
  name=$1 cap=$2
  if [ "$name" = . ]; then prod=$(find $src -maxdepth 1 -name '*.kt' -exec cat {} + 2>/dev/null | wc -l)
  else prod=$(kt "$src/$name"); fi
  echo "$name: $prod of $cap"
  [ "$prod" -le "$cap" ] || { echo "error: $name over its cap of $cap lines (scripts/loc.sh)"; bad=1; }
  known="$known $name"
}
known=""
# package    cap     size on 2026-11-03, and what it is
budget widget    4938  # 4,703: the home-screen widgets, their rendering and pinning
budget sicherung 3880  # 3,031: Daten sichern — backups, contacts, SMS and calls, age;
                       # 3183 -> 3199 (owner approved 2026-11-06): „Kontakte übertragen“
                       #   sends each contact's own picture as its PHOTO, ≤256 px, for Homes' „Ist das …?“ in Immich (homes#298);
                       # 3199 -> 3517 (owner approved 2026-11-07: Kontakt prüfen lassen) — the accepted
                       #   corrections written into the person's own phone contacts, Korrektur.kt (the plan) and HandyKorrektur.kt;
                       # 3517 -> 3560 (owner approved 2026-11-08: Kontakt-Formular v2, homes#304): the kinds
                       #   written only when changed, the main number and e-mail (primary flags, both ways), the photo row, the note;
                       # 3560 -> 3600 (owner approved 2026-11-09: Immich names to the owner's phone (new contact, rename)): Homes' own changes, a new
                       #   contact of a person named in Immich made as one raw contact with its rows (HandyKorrektur.neu, konto);
                       # 3600 -> 3660 (owner approved 2026-11-09: Beziehungen aufs Handy, homes#330): a relation Homes asks for written as a ContactsContract Relation row
                       # 3660 -> 3680 (owner approved 2026-11-11: each number once, homes#359): the export sends a number once per contact,
                       #   compared as Homes' caldav.TelSchluessel (Vcard.telSchluessel, einmalJeNummer), and Kontakt prüfen lassen compares the same way
                       # 3680 -> 3880 (owner's request 2026-11-14: „Build the sealed-archive path for the phone's WhatsApp and Signal
                       #   backups“, archiv#64): WhatsApp and Signal age-encrypted for the archive while read, from any offset with
                       #   the kept randomness (Age.ab, Age.Umschlag, Uploader.Inhalt), a part of a forgotten encryption left alone
budget ui        3929  # 3,026: the activities: start screen, pairing, settings screens;
                       # 3177 -> 3200 (owner-approved, homes ADR 0001 plan): the traces and „Hier anfordern“ on the start screen, homes-android#231;
                       # 3200 -> 3220: a trace says what sharing to it would do, homes-android#232;
                       # 3220 -> 3320: the account's and the backup's missing parts as disabled switches with their trace, homes-android#233
                       #   (+12 of it is #237's level sheet, merged meanwhile);
                       # 3320 -> 3500: „Deine Geräte“ as tiles (owner, 2026-11-04): ui/FavoritenAnsicht.kt, the
                       #   widgets' pictures, ▲ ■ ▼ on a cover's tile, the part-way blind; today's rows removed (net +178)
                       # 3500 -> 3513 (owner 2026-11-27: Notizen- und Dokumente-Widget ausgebaut, and shared recipes shown —
                       #   owner approved 2026-11-27 („Bau alles“)): the recipe's „als Rezept gemerkt“ with „Ansehen“ (ShareActivity.gemerkt)
                       # 3513 -> 3897 (owner approved 2026-11-27 („Bau alles“), the start screen redesign the owner asked for: „Bitte umsetzen“): „Jetzt“
                       #   (ui/Jetzt.kt), „Alles auf einen Blick“ (ui/Ueberblick.kt), „Dieses Telefon“ (ui/TelefonActivity.kt)
                       # 3897 -> 3912 (owner approved 2026-11-27 („Bau alles“), mdopp/homes#391): a tile's line and „neu: …“ from Homes' bereiche,
                       #   seen once opened; Jetzt reads a card's achtung instead of guessing from its words (net)
                       # 3912 -> 3929 (owner approved 2026-11-28 („Ja, aber auch Karten die ich ausgelesen habe“)): „Dieses
                       #   Telefon“'s rows for Teilen per Berührung (Was teilen?, Meine Visitenkarte, Behaltene Tags)
budget data      3364  # 2,780: Homes' calls (Napi), clients, models, the token store;
                       # 2919 -> 2940 (owner-approved, homes ADR 0001 plan): /napi/kann and its client, homes-android#231;
                       # 2940 -> 2950 (owner, 2026-11-04: the Hochladen job): POST /napi/musik and the musik share key;
                       # 2950 -> 3007 (owner-approved 2026-11-05: writable Homes-Konto): the four write endpoints, If-Match,
                       #   PUT and DELETE, GemeinsamClient.schreiben (homes#278);
                       # 3007 -> 3120 (owner approved 2026-11-07: Kontakt prüfen lassen) — GET and POST
                       #   /napi/kontakte/korrekturen, their reply shapes and parser;
                       # 3120 -> 3132 (owner approved 2026-11-08: Kontakt-Formular v2, homes#304): the main ones and the photo in that reply
                       # 3132 -> 3145 (owner's request 2026-11-14 „Bitte in der App und auf der Webseite gleich“, to be OK'd in the PR):
                       #   a card's text, Homes' own words for a printer (mdopp/homes#370), its key, field and parse
                       # 3145 -> 3185 (owner's decision 2026-11-18 „ja, baue es so“: Homes in the phone's language, to be OK'd in the PR):
                       #   data/Sprache.kt, the system's languages as Accept-Language on every call, HomesHttp's one client carrying it
                       # 3185 -> 3255 (owner approved 2026-11-26, homes ADR 0004 §1 and §2): the four
                       #   /napi/ausloeser calls, their reply shapes and wire values in Napi; ScreenGate names the charger's exception
                       # 3255 -> 3265 (owner approved 2026-11-26 „Go. Passt alles“, homes ADR 0004 §4): a tag's aktion,
                       #   the scan's text and the box's aktionen in the three reply shapes
                       # 3265 -> 3330 (owner approved 2026-11-26, homes ADR 0004 §5 §8): the five calls of a tag's
                       #   automations and their wish's state, their reply shapes, the scanner's oeffnen
                       # 3330 -> 3335 (owner approved 2026-11-27, „Alles OK. Go ahead“): a tag's zuletzt (§9)
                       # 3335 -> 3351 (owner 2026-11-27: Notizen- und Dokumente-Widget ausgebaut — owner approved 2026-11-27 („Bau alles“)): the two
                       #   tools taken out (ToolDefs.AUSGEBAUT), a kept share's art and titel
                       # 3351 -> 3364 (owner approved 2026-11-27 („Bau alles“), mdopp/homes#391): a card's achtung, a task's dringend, an entry's neu
                       #   and the answer's bereiche, their keys and docs
budget konto     1726  # 1,242: das Homes-Konto, the sync adapters for contacts and calendars;
                       # 1304 -> 1632 (owner-approved 2026-11-05: writable Homes-Konto): the phone's changes sent before
                       #   each pull, the iCalendar writer, alarms, EXDATE, exceptions and versions in the reader
                       # 1632 -> 1726 (owner approved 2026-11-05: contact pictures, homes#287): a card's
                       #   PHOTO read, written as the contact's picture with its hash read back, the person's own sent, at most 256 px
budget standort  1098  # 1,046: Standortverlauf — recording, batching, import
budget kann      469  #   340 on 2026-11-04: what the box can do, /napi/kann kept and read, „Hier anfordern“ (homes ADR 0001);
                       # 360 -> 420 (owner-approved, homes ADR 0001 plan): the share targets switched by teilen, homes-android#232;
                       # 420 -> 460: the widget providers switched by widgets, homes-android#233
                       # 460 -> 469 (owner 2026-11-27: Notizen- und Dokumente-Widget ausgebaut — owner approved 2026-11-27 („Bau alles“)): the tool
                       #   widget's provider leaves the picker while no tool is offered (Anbieter.werkzeug)
budget einrichtung 599 #   599 on 2026-11-27 (owner approved 2026-11-27 („Bau alles“), mdopp/homes#391): bereiche parsed (Bereich), an entry's neu, a task's dringend and the orange count;   571 on 2026-11-27: „Öffnen“ became tiles of ui/Ueberblick, „Deine Aufgaben“ one row;   617 on 2026-11-14, the first three tasks and „Alle N Aufgaben“ (owner: „immer nur die ersten mit Verweis auf alle“); 574 on 2026-11-04, a new package (owner): /napi/einrichtung kept and read, „Öffnen“ and „Deine Aufgaben“
                       # on the start screen, the launcher's four shortcuts and their trampoline
budget hochladen  741  #   555 on 2026-11-04, a new package (owner): the Hochladen job — music and Takeout ZIPs, with the screen off;
                       # 583 -> 741 (owner, 2026-11-04: what shows as a notification shows in the open app): hochladen/Stand.kt, the row
budget archiv     247  #   new package 2026-11-11 (owner's call in its PR): Share → „Ins Archiv“, a Facebook or
                       # LinkedIn export told by its structure, encrypted on the phone to the archive, uploaded as `sozial`
budget realtime   667  #   635: the live stream and its foreground service
budget ausloeser  3329 #   new package 2026-11-26 (owner approved 2026-11-26, homes ADR 0004 step 1): the phone as a
                       # trigger for HA — the touched tag's activity, the Auslöser screen (writing, naming, renaming, removing a tag;
                       # §2 owner 2026-11-26), the charger's receiver, the client;
                       # 1000 -> 1070 (owner approved 2026-11-26 „Go. Passt alles“, §4): „Was soll der Tag tun?“, the action
                       #   under a tag's name, the scan's sentence on a touched tag, the client's aktion and aktionen;
                       # 1070 -> 1510 (owner approved 2026-11-26, homes ADR 0004 §5 §8): the box's own address as an App
                       #   Link (a link acts on „Auslösen“), the scanner's question opening Homes, a tag's automations
                       #   (TagAutomationen.kt: link, loose, a new one, each confirmed; the admins' approval asked after
                       #   while the screen is open) and the client's five calls;
                       # 1510 -> 1620 (owner approved 2026-11-27, „Alles OK. Go ahead“): the new-tag form closed
                       #   behind the list's last row, opened by a blank tag too; a tag's last run under it (§9), Homes'
                       #   time read and said as „heute 18:12“;
                       # 1620 -> 1765 (owner's wish and field finding 2026-11-27, owner approved 2026-11-27 „OK“): a VIEW
                       #   from Android's NFC service is a touch (TagActivity.weg); a blank tag touched anywhere opens the
                       #   new-tag form (LeerTagActivity, TECH_DISCOVERED) and is written while Android still holds it;
                       # 1765 -> 2035 (owner approved 2026-11-27 (WLAN-Tag)): „+ WLAN-Tag beschreiben“, the WSC credential
                       #   built byte by byte (WlanTag.kt) and its form (WlanFormular.kt), the writer's record of any kind
                       # 2035 -> 2475 (owner approved 2026-11-27 („Bau alles“), Tag-Inhalt anzeigen): what is on a written
                       #   tag held to the screen (TagInhalt.kt: Homes, WLAN read back, any record in plain words), its card
                       #   (TagInhaltAnsicht.kt), „Bearbeiten“, „Auslösen“, „Ins Telefon übernehmen“ (WlanUebernahme.kt),
                       #   and „Überschreiben“ only after one question (TagSchreiber.darf, erlauben)
                       # 2475 -> 2894 (owner approved 2026-11-28 („Mach das. Aber zeige mir die Daten.“), a field case): every
                       #   tag read before it counts as blank, a card with data of its own never written (TagBefund.kt), what
                       #   reads of it without keys (TagLeser.kt: Ultralight/NTAG pages, MIFARE Classic with the public keys,
                       #   ISO-DEP applications by name, NFC-V blocks) on the screen with „Kopieren“
                       # 2894 -> 3246 (owner approved 2026-11-27 („Bau alles“), WLAN anbieten): the phone as a read-only
                       #   Type 4 Tag (Typ4Tag.kt), its bound HCE service (WlanAnbietenDienst.kt), the offer held in memory
                       #   for two minutes (WlanAngebot.kt) and its screen (WlanAnbietenActivity.kt), the form's „Anbieten“
                       # 3246 -> 3329 (owner approved 2026-11-28 („Ja, aber auch Karten die ich ausgelesen habe“), Teilen per Berührung): the service
                       #   listens while unlocked and asks when read with nothing chosen, an offer answered once
                       #   (Typ4Tag.gelesen, WlanAngebot.einmal), „Im Telefon behalten“ on the Tag-Inhalt card
budget teilen     530  #   new package 2026-11-28 (owner approved 2026-11-28 („Ja, aber auch Karten die ich ausgelesen habe“)): Teilen per Berührung — the
                       # question, „Was teilen?“, Meine Visitenkarte, Behaltene Tags, the Keystore Tresor, vCard and QR
budget verbindung 247  #   235 on 2026-11-04: what the app says about its line to Homes, one answer for the
                       # notification, the start screen and the widgets (box not reachable, no network, paused)
budget .           35  #    33: HomesConfig.kt
for d in "$src"/*/; do
  p=$(basename "$d")
  case " $known " in *" $p "*) ;; *) echo "error: package $p has no cap in scripts/loc.sh"; bad=1 ;; esac
done
echo "total production: $(kt $src)  tests: $(kt app/src/test app/src/androidTest)"
exit $bad

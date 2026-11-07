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
budget sicherung 3516  # 3,031: Daten sichern — backups, contacts, SMS and calls, age;
                       # 3183 -> 3199 (owner approved 2026-11-06): „Kontakte übertragen“
                       #   sends each contact's own picture as its PHOTO, ≤256 px, for Homes' „Ist das …?“ in Immich (homes#298);
                       # 3199 -> 3516 (owner's yes asked for in the PR, 2026-11-07): Kontakt prüfen lassen — the accepted
                       #   corrections written into the person's own phone contacts, Korrektur.kt (the plan) and HandyKorrektur.kt
budget ui        3500  # 3,026: the activities: start screen, pairing, settings screens;
                       # 3177 -> 3200 (owner-approved, homes ADR 0001 plan): the traces and „Hier anfordern“ on the start screen, homes-android#231;
                       # 3200 -> 3220: a trace says what sharing to it would do, homes-android#232;
                       # 3220 -> 3320: the account's and the backup's missing parts as disabled switches with their trace, homes-android#233
                       #   (+12 of it is #237's level sheet, merged meanwhile);
                       # 3320 -> 3500: „Deine Geräte“ as tiles (owner, 2026-11-04): ui/FavoritenAnsicht.kt, the
                       #   widgets' pictures, ▲ ■ ▼ on a cover's tile, the part-way blind; today's rows removed (net +178)
budget data      3120  # 2,780: Homes' calls (Napi), clients, models, the token store;
                       # 2919 -> 2940 (owner-approved, homes ADR 0001 plan): /napi/kann and its client, homes-android#231;
                       # 2940 -> 2950 (owner, 2026-11-04: the Hochladen job): POST /napi/musik and the musik share key;
                       # 2950 -> 3007 (owner-approved 2026-11-05: writable Homes-Konto): the four write endpoints, If-Match,
                       #   PUT and DELETE, GemeinsamClient.schreiben (homes#278);
                       # 3007 -> 3120 (owner's yes asked for in the PR, 2026-11-07): Kontakt prüfen lassen — GET and POST
                       #   /napi/kontakte/korrekturen, their reply shapes and parser
budget konto     1726  # 1,242: das Homes-Konto, the sync adapters for contacts and calendars;
                       # 1304 -> 1632 (owner-approved 2026-11-05: writable Homes-Konto): the phone's changes sent before
                       #   each pull, the iCalendar writer, alarms, EXDATE, exceptions and versions in the reader
                       # 1632 -> 1726 (owner approved 2026-11-05: contact pictures, homes#287): a card's
                       #   PHOTO read, written as the contact's picture with its hash read back, the person's own sent, at most 256 px
budget standort  1098  # 1,046: Standortverlauf — recording, batching, import
budget kann      460  #   340 on 2026-11-04: what the box can do, /napi/kann kept and read, „Hier anfordern“ (homes ADR 0001);
                       # 360 -> 420 (owner-approved, homes ADR 0001 plan): the share targets switched by teilen, homes-android#232;
                       # 420 -> 460: the widget providers switched by widgets, homes-android#233
budget einrichtung 603 #   574 on 2026-11-04, a new package (owner): /napi/einrichtung kept and read, „Öffnen“ and „Deine Aufgaben“
                       # on the start screen, the launcher's four shortcuts and their trampoline
budget hochladen  741  #   555 on 2026-11-04, a new package (owner): the Hochladen job — music and Takeout ZIPs, with the screen off;
                       # 583 -> 741 (owner, 2026-11-04: what shows as a notification shows in the open app): hochladen/Stand.kt, the row
budget realtime   667  #   635: the live stream and its foreground service
budget verbindung 247  #   235 on 2026-11-04: what the app says about its line to Homes, one answer for the
                       # notification, the start screen and the widgets (box not reachable, no network, paused)
budget .           35  #    33: HomesConfig.kt
for d in "$src"/*/; do
  p=$(basename "$d")
  case " $known " in *" $p "*) ;; *) echo "error: package $p has no cap in scripts/loc.sh"; bad=1 ;; esac
done
echo "total production: $(kt $src)  tests: $(kt app/src/test app/src/androidTest)"
exit $bad

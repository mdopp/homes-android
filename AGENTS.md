# homes-android — notes for coding agents

Everything a coding agent (Claude Code, pi, anyone else) needs to change the
Homes app: what it is and is not, the rules, the code gates, build, test and
release, and the traps. Humans start at the [README](README.md). The seam
with the server is [`docs/CONTRACT.md`](docs/CONTRACT.md), the look is
[`docs/DESIGN.md`](docs/DESIGN.md).

## What the app is

This repo is the **Homes app**: native Kotlin home-screen widgets, the
pairing with **Homes** (`https://www.<domain>`, repo `mdopp/homes`), the
family's surface of the box Laib runs, and on the app's start screen the
person's favorite devices (the owner's calls, 2026-11-02 and 2026-11-03: the
widgets are one device per widget, one energy widget and the tools Dokumente
and Notizen; no Box widgets; every confirmation is the one dialog,
`widget/ActionDialog.kt`). It is a widget client (laib ADR 0022):
no web shell, no messages, no approvals, no diagnostics, and for the widgets
no network while the screen is off. The widgets stay live through one
`specialUse` foreground service (`realtime/LiveService.kt`) that holds the
stream only while the screen is on. The one thing beyond the widgets is
Daten sichern (#194, laib ADR 0029; renamed 2026-11-03): it picks up the phone's WhatsApp
and Signal backups and uploads them to Homes, and, each behind its own switch
and permission, mirrors the contacts and sends the SMS and call log
age-encrypted. And Hochladen (the owner's call, 2026-11-04): songs and
albums shared „Zur Musik hinzufügen“ and Google Takeout ZIPs for the
Standortverlauf go up in a job of their own, also with the screen off.
The seam with Homes is `docs/CONTRACT.md`.

These rules apply to every session, human or agent.

## Homes is the contract

- The app calls only what Homes serves. Every call is data in `data/Napi.kt`
  (method, path, query, body fields, reply keys), `data/NapiRequest.kt` alone
  builds requests from it, and `NapiContractTest` holds it to a copy of Homes'
  handlers, file and line. A call the app needs that Homes lacks is a Homes
  issue first, never a guess here.
- One set of names: package `cloud.dopp.homes`, scheme `cloud.dopp.homes://`,
  token prefix `homes_device_`. `NamesTest` fails on any older name.
- Haus was renamed Homes (the owner's call, 2026-11-03, laib#1021): a new
  package, so a new app; phones install Homes, pair again and place the
  widgets again. What the server keeps as stored data keeps its old name:
  the iCalendar properties `X-HAUS-KALENDER` and `X-HAUS-ART` of the shared
  calendars. „Haus“ stays where it is the ordinary word: the energy flow's
  „Haus“ (the building), „Haus-Energie“, Haushalt. The household's shared contacts and calendars
  are „Haushalts-Kontakte“ and „Haushalts-Kalender“ (the owner's call,
  2026-11-05), and asking for a module is an „Anfrage“, not a „Wunsch“.
- Messages to people go through their chat channel (laib ADR 0022 §3), not the
  app. Two notifications, and only these: the live service's quiet one, and
  the Hochladen job's while it uploads and for what came of it. Two
  foreground services, and only these: the live widgets' (`specialUse`),
  always there while wanted, and WorkManager's for the Hochladen job
  (`dataSync`), only while it uploads. Why the second: the owner decided on
  2026-11-04 that a large upload keeps going with the screen off, with its
  own notification; Android 15 allows `dataSync` six hours a day, and when
  they run out the job says in German that it goes on later — never a
  silent stop. Do not add a third, a poll (widget update periods stay 0), an
  alarm, a wakelock or a rescue worker. Recovery after a killed process rests
  on the battery-optimization exemption the person grants. `ManifestTest`
  holds this; `e2e/` proves it on emulators (`.github/workflows/live-e2e.yml`).
- Two jobs, and only these. The backup pick-up (the owner's call in #194):
  `sicherung/SicherungWorker.kt`, a WorkManager worker — daily on an unmetered
  network while charging, and once on „Jetzt sichern“. It never goes
  foreground, posts nothing, reads the granted folders only (storage access
  framework, no storage permission; the one writer is a restore on a new
  phone, `sicherung/SafZiel.kt`, run from its screen, not from this job) and, with their switches on, the
  contacts, SMS and call log (read permissions; the one write is Kontakt prüfen lassen's, owner
  2026-11-07: before the contacts go up, the corrections the person accepted on Homes' review of
  their own contacts are written into those phone contacts, `sicherung/HandyKorrektur.kt`, data rows
  only, never without such a correction), and is the one caller
  that does not pass `ScreenGate`. Without a granted folder or a switch on it
  makes no request. And Hochladen (the owner's call, 2026-11-04,
  `hochladen/Hochladen.kt`): the files shared to it, copied into the app's
  own storage first, uploaded one after the other — a song or a ZIP to
  `POST /napi/musik` in one streamed request, a Takeout ZIP to the
  Standortverlauf's import in resumable pieces — in the foreground while it
  runs, its notification saying how far it got and what came of each. A
  stop (no network, the six hours used up) is said and retried; the app's
  screen starts it again. `ManifestTest` holds each job to its one file; do
  not add a third job, and do not reuse either for something else.
- Standortverlauf (the owner's call, 2026-11-02; docs/CONTRACT.md (f)):
  behind its own switch and the background-location permission,
  Android's own LocationManager delivers by PendingIntent to
  `standort/StandortEmpfaenger`, which stores the points and sends a
  batch to Homes while that delivery has the phone awake. It is no job,
  service, alarm or wakelock; keep it that way (`ManifestTest`). The
  decisions — rate per mode and situation, resting told from the fixes,
  when a batch goes — live in `standort/Plan.kt` and `standort/Ruhe.kt`,
  plain and unit-tested.

## Das Homes-Konto

- The owner's call, 2026-11-03 (#206): the household's shared contacts,
  calendars and reminders live in a real Android account „Homes“
  (AccountManager, two sync adapters for ContactsContract and
  CalendarContract), so the dialer, WhatsApp, mail and the calendar app use
  them. „Erinnerungen gehören in den normalen Kalender, nicht in ein
  Widget.“ Source: Homes' `/napi/gemeinsam/…` (vCard, iCalendar), with the
  device token. `konto/` holds it.
- Writable since the owner's call of 2026-11-05 (read only before): the
  calendar and contacts apps offer its calendars and address book; what is
  made, changed or deleted there is sent back first in each sync
  (`PUT|DELETE /napi/gemeinsam/…`, a delete into Homes' Papierkorb), Homes'
  version wins a conflict and the screen says so. Subscriptions and
  „Erinnerungen“ stay read only; a single changed instance of a series is
  not sent yet. The sync adapter's own writes go into the account's own
  entries only. Android's SyncManager runs the adapters (the switch,
  „Jetzt abgleichen“, hourly, and a `gemeinsam` frame of the open live
  stream, #209); the app adds no job, alarm or worker for them. `ManifestTest` holds this.
  Beside the account's own entries, one other writer of contacts exists:
  Kontakt prüfen lassen's (owner 2026-11-07, `sicherung/HandyKorrektur.kt`),
  which writes the person's own contact only by a correction they accepted
  on Homes, as the app and not as a sync adapter; `ManifestTest` holds it to
  data rows and to the backup run. Since 2026-11-09 („App erweitern“) Homes
  sends its own changes the same way: a name its Immich person got, and a
  new contact (`uid` `""`), the one case it makes a raw contact (never
  changes or deletes one).
- The other direction stays: a person's own contacts go phone → archive
  („Kontakte übertragen“), and the Homes account's never do
  (`HomesKontoAusschlussTest`, `e2e` step 8).

## No Google Play services

- The owner's decision, 2026-11-02 („ohne Google“): no
  `com.google.android.gms` or Firebase library, class or permission — not
  for location, not for anything. Use the platform (LocationManager,
  SensorManager). `ManifestTest.noGooglePlayServices` fails if one
  comes back.

## The icon

- `art/icon.svg` is the one master (owner, 2026-11-02: the terminal prompt
  inside a house drawn as a green line, `#3ddc84` on `#0c120e`). Every PNG — launcher,
  adaptive foreground and monochrome, notification, store — is rendered from
  it by `scripts/icons` (`npm ci && node icons.mjs`), never drawn or resized
  by hand. mdopp/homes carries the same master for the web app's icons.

## Code gates

The owner's call, 2026-11-03: the code stays as clean as it can be, and a
PR proves it. CI runs these on every PR (`.github/workflows/android-build.yml`);
each one fails on a new finding, and each has a baseline of what was there
before the gate, so the old findings pass while nothing new joins them.

- **Android Lint** (`./gradlew :app:lintDebug`): the default checks, every
  warning an error, plus `UnusedResources` and `UnusedIds`. Checks that turn
  red with the calendar instead of the code (a newer library or SDK) are off,
  in `app/build.gradle`. Baseline: `app/lint-baseline.xml`.
- **detekt** (`sh scripts/detekt-ratchet.sh`): detekt's defaults with
  `config/detekt.yml` — unused imports, private members, classes, properties
  and parameters; `LongMethod` 60, `CyclomaticComplexMethod` 15, `LargeClass`
  600, `LongParameterList`, `NestedBlockDepth`, `ComplexCondition`; a string
  literal three times; swallowed and too generic exceptions. Complexity and
  exceptions are judged in production code only. Baseline:
  `app/detekt-baseline.xml`.
- **Dead public code** (`python3 scripts/deadcode.py`): a non-private
  top-level declaration, or member of a top-level `object`, that no other
  file — Kotlin, XML, manifest, e2e, workflows — names. Nothing names it:
  delete it; only its own file does: make it private. Baseline:
  `scripts/deadcode-baseline.txt`.
- **Line caps** (`sh scripts/loc.sh`): each package's production lines
  under its cap, set at its size on 2026-11-03 plus 5 %; tests are counted,
  not capped. Raising a cap is the owner's call, in the same PR, with its
  reason next to the number; a new package needs its own line.

**The ratchet.** A baseline only shrinks. A fixed finding fails the gate
too, until its entry leaves the baseline in the same PR: lint reports it as
`LintBaselineFixed` (delete the entry, or `./gradlew :app:updateLintBaseline`),
the detekt script has already written the shrunk file (commit it), the
dead-code script names the line to delete. Never regenerate a baseline to
let a new finding in; fix it, or, where the rule is wrong for this code, say
why in a `@Suppress` beside it.


## Commits

- **Conventional Commits**: `type(scope): subject` — `feat`/`fix`/`refactor`/
  `chore`/`docs`/`test`. Scope mirrors the area: `feat(widget):`,
  `feat(pairing):`, `chore(ci):`, `docs:`.
- **No parentheses in the subject** beyond the conventional `(scope)`.
- In KDoc, never write `/*` (for example in a path glob): Kotlin nests block
  comments and the file stops compiling.

## Never commit secrets

- **NEVER** commit signing keystores or Play credentials (`*.jks`, `*.keystore`,
  `*.keystore.base64`, `local.properties`, any Play service-account JSON). They
  stay local and reach CI as **secrets** (`ANDROID_KEYSTORE_BASE64`,
  `ANDROID_KEYSTORE_PASSWORD`; key alias `haus`).
- A new package id is a new app: its signing key is the owner's decision
  (laib#30). Debug and CI builds need no key.

## Releases

- Play release = a **signed AAB** uploaded via **Play Console**, human-gated.
- The debug build in CI only proves the project compiles and its tests pass.

## Verify

- There is no local Android SDK here: **CI is the proof** for build, unit
  tests (Robolectric) and the code gates.
- A feature is verified on a **device or emulator** (install the APK, pair,
  place the widgets, watch them update while the screen is on). If you cannot
  run one, say so — a green build is not a verified feature.

## Layout

```
app/src/main/java/cloud/dopp/homes/
  data/        every call to Homes: Napi.kt (the calls as data), NapiRequest.kt,
               pairing, token and server store, ScreenGate
  widget/      the device, energy and tool widgets, the one ActionDialog,
               publishing the shortcuts, and the widget picker
  ui/          the app's screens: start (favourites, LevelSheet), share,
               Daten sichern, Zurückholen, Standortverlauf, Homes-Konto
  realtime/    LiveService, the live widgets' foreground service, and the stream protocol
  verbindung/  the one answer about the line to Homes (live, paused, box not
               reachable, no network, not paired) for notification, start, widgets
  sicherung/   the backup job (SicherungWorker), restore, age, SMS/vCard
  standort/    location recording (Plan, Ruhe, Puffer) and the history import
  konto/       the Homes account: two sync adapters, vCard and iCal readers
  kann/        what the box can do (/napi/kann, homes ADR 0001): kept, read, „Hier anfordern“
  hochladen/   the Hochladen job (WorkManager, dataSync while it uploads): music, Takeout ZIPs
  archiv/      Share → „Ins Archiv“: a Facebook or LinkedIn export told by its structure, its one
               file age-encrypted to the person's archive and uploaded while the share's dialog is open
  einrichtung/ the person's modules and tasks (/napi/einrichtung): „Öffnen“, „Deine Aufgaben“,
               the installed-apps report, the launcher's four shortcuts and their trampoline
app/src/test/  JVM tests with Robolectric, including the preview renders
e2e/           emulator runs (.github/workflows/live-e2e.yml)
art/icon.svg   the one icon master; scripts/icons renders every PNG
config/        detekt.yml
scripts/       loc.sh, deadcode.py, detekt-ratchet.sh, readme_langs.py, icons/
docs/          CONTRACT (the seam with Homes), DESIGN (the look), img/ (README pictures)
```

## Build, test, ship

- Plain Gradle: `./gradlew :app:assembleDebug`, `./gradlew
  :app:testDebugUnitTest`, `./gradlew :app:lintDebug`, `sh
  scripts/detekt-ratchet.sh`, `sh scripts/loc.sh`, `python3
  scripts/deadcode.py`.
- CI (`.github/workflows/android-build.yml`) builds and tests every pull
  request and runs the code gates. There is no local Android SDK in the
  Werkstatt: CI is the proof for build, tests and gates.
- Each merge to `main` publishes a signed APK as a pre-release
  `v<next patch>-dev.<run>`; the newest five are kept, none is marked
  latest. A `v*` tag builds a signed APK and AAB as that release, when the
  signing secrets are set. Every signed build takes `versionCode` from the
  workflow's run number, so each installs over the one before.
- Phones follow the releases with Obtainium (README). Dev builds and
  releases carry the same key; a debug build has a different key and must
  be uninstalled first, then the app is paired again.
- README screenshots come from the Robolectric preview tests
  (`*PreviewTest.kt`, demo data only): they write PNGs to
  `app/build/widget-preview/` (and `$WIDGET_PREVIEW_DIR` when set). The
  README's copies are under `docs/img/`, WebP, a few hundred KB at most.

# CONTRACT — the interface with Homes

The seam between this repo (the Homes app) and its server, **Homes**
(`mdopp/homes`, served at `https://homes.<domain>`). Homes is the contract: the
app calls exactly the paths Homes serves, under the names Homes'
`internal/app` declares (laib ADR 0022). One set of names, nothing older next
to them. Any change here is a **cross-repo change** with `mdopp/homes`.

| | Value | Homes |
|---|---|---|
| package id | `cloud.dopp.homes` | — |
| device-token prefix | `homes_device_` | `app.TokenPrefix` |
| pair deep link | `cloud.dopp.homes://pair#token=…&id=…` | `app.Scheme`, `pages/pair.go` |
| setup QR content | `cloud.dopp.homes://setup?server=…&token=…` | `pages/geraete.go` |

The scheme is named after the domain on purpose: a bare `homes://` could be
registered by any other app on the phone and catch the token in the redirect.

## (a) Pairing

- The person enters Homes' address (or scans the QR on Box → Geräte). The app
  opens `<homes>/pair-device` in a Custom Tab, where their Authelia session is;
  they confirm; Homes mints a device and redirects to the pair deep link with
  the token in the **fragment** (never in a proxy log).
- Only `https://` addresses: an `http://` one is refused with a message, typed
  or scanned.
- The pair link is taken only while a pairing the app started is pending (it
  opened `/pair-device` less than 15 minutes ago); Homes' redirect carries no
  nonce of the app's, so the pending server is what ties the link to it. Any
  other pair link pairs nothing.
- Nothing replaces server or token without a dialog that names the host: the
  pair link, a scanned address and a scanned setup QR all ask first.
- Before the token is stored, the app checks it: the prefix `homes_device_`,
  then `GET /napi/whoami` against that server (200 with a `uid`). Then it is
  stored encrypted on the device; if that write fails, the app says so and
  stays unpaired.
- The setup QR is QR content, not a link: the manifest has an intent filter for
  `cloud.dopp.homes://pair` only, so no web page can point the app at a foreign
  server. `server` is required; `token` is optional (present → paired once
  confirmed and checked; absent → the app opens `/pair-device` there).
- The assistant's name comes from `GET /napi/assistent` → `{"name": "Homes"}`
  (the box's one setting, Laib's box.yml `assistant`; „Homes“ when unset). The
  app keeps the last name in plain prefs, forgets it on unpairing and shows
  „Homes“ until Homes answered. Nothing else about the assistant comes from the
  box: its colours are fixed in the app (`terminal_green` `#3DDC84` on
  `terminal_ground` `#0C120E`) and its icon is the app's own launcher icon.
  (This replaced the per-box persona of laib ADR 0020, owner 2026-11-02.)

## (b) The `/napi` paths the app calls

Every request carries `Authorization: Bearer homes_device_…`; Homes answers 401
without one and a JSON 404 for a path it does not serve. Each call is declared
as data in `app/src/main/java/cloud/dopp/homes/data/Napi.kt` — method, path,
query parameters (and the values Homes takes), body fields, the reply keys the
app reads — and `data/NapiRequest.kt` is the one place a request is built from
it. `NapiContractTest` holds every declaration to a copy of Homes' handlers,
each row naming the Homes file and lines it came from.

| Widget | Path |
|---|---|
| pairing | `GET /napi/whoami`, `GET /napi/assistent` |
| the start screen's devices | `GET /napi/portal/favorites` (the person's ☆ things on Homes, as cards in their order; homes#213), `POST /napi/ha/call`, `GET /napi/portal/state` (a cover's or a lock's state before its toggle) |
| one device (widget, restored 2026-11-03) | `GET /napi/portal/state`, `POST /napi/ha/call`; its picker `GET /napi/portal/start/addable` |
| app-icon shortcuts | Garagentor, Einkaufsliste, Zeitleiste, Dokument scannen: see (i); the garage among the favourites, else `GET /napi/portal/start/addable` (without cameras: Homes draws no card for one), `POST /napi/ha/call` |
| energy | `GET /napi/portal/energy`: the flow now, per total `today` (kWh since the house's midnight; homes#213), and `charge`, how full the battery is (mdopp/homes#228) |
| Daten sichern | `GET /napi/sicherung/stand`, `POST /napi/sicherung/fehlend`, `POST /napi/sicherung/uploads`, `HEAD`/`PATCH /napi/sicherung/uploads/{id}`, `GET /napi/sicherung/schluessel`, `POST /napi/kontakte`, `GET /napi/kontakte/korrekturen`, `POST /napi/kontakte/korrekturen/{id}`, `GET /napi/sicherung/letzte`, `GET /napi/sicherung/letzte/{quelle}/{pfad...}`: see (e) |
| Standortverlauf | `GET /napi/standort`, `POST /napi/standort`, `…/standort/import…`: see (f) |
| Homes-Konto | `GET /napi/gemeinsam/kontakte`, `GET /napi/gemeinsam/kalender` (`?schreibbar=1`), `PUT\|DELETE /napi/gemeinsam/kalender/{kalender}/{uid}`, `PUT\|DELETE /napi/gemeinsam/kontakte/{uid}`: see (g) |
| what the box can do | `GET /napi/kann`, `POST /napi/kann/anfordern`: see (h) |
| Öffnen, Deine Aufgaben | `GET /napi/einrichtung`, `POST /napi/einrichtung/apps`: see (i) |
| Share → Musik, Hochladen | `POST /napi/musik`, multipart, one `file` part a request, streamed from the app's copy: see (j) |
| Share → Homes, photos | `POST /napi/upload`, multipart `filename` then `file`, one photo a request → `{ok, id}`: the camera app's photos (one or several, `image/*`) into the folder Paperless consumes (owner, 2026-11-02). JPEG, PNG, WebP and GIF of at most 25 MB go as they are; HEIC/HEIF or anything larger is made a JPEG on the phone (long edge ≤ 4096). At most 20 a share. Homes' refusals are codes; the app says them in German by status (404 no folder, 413, 415, 429 busy, 503, 507 full) |
| Share → Homes | `POST /napi/merken` `{text}`: what the share sheet sent (title and link); Homes keeps the page as a note and answers `{kurz, notiz, schon, …}` or `{error}` with a German sentence (homes#120); the share sheet's Direct Share target carries the assistant's name (#188) |
| tools | `GET /napi/defs/tool`, the catalog's `tool-api-path` rows (`/napi/portal/{documents,notes}`; Homes also lists `contacts` and `task`, which the app no longer offers as a widget, owner 2026-11-02 and 2026-11-03), `POST /napi/action-callback` |
| live stream | `POST /napi/portal/watch`, `GET /napi/portal/events` |

Fields the widgets read beyond a value to show:

- `page` on every card (`/napi/portal/state`, `/napi/portal/favorites`) and
  on every tool row with an id: the item's own page on Homes, a path
  (`/raeume/<entity_id>`, `/kalender/erinnerung/<uid>`, `/dokumente/<id>`,
  `/notizen/<file>`). See (d).
- `today` on every energy total: what it added since the house's midnight,
  in kWh, by the Energie page's rule; absent when HA's history does not say,
  and then the widget shows a dash, never the lifetime count.
- `charge` on the energy picture: the battery's charge sensor as a meter, its
  `state` the percent (the Energie page's Akku ring); absent without a battery
  leg or a number. Without it the widget draws the Akku node without its ring
  and the small widget shows the grid instead; its sensor joins the watch set.

## (c) The live stream

One foreground service (`realtime/LiveService.kt`, type `specialUse`, a quiet
"Live-Widgets" notification) runs while the app is paired and a widget the
stream keeps current is placed (one device, energy, tools; the Box widgets are
gone, owner 2026-11-03). There is no switch (owner, 2026-11-08: „es ist jetzt
der einzige Weg“); an older install's stored switch value is ignored. The
start screen keeps one row only while the battery exemption is missing:
„Widgets aktuell halten · Tippen und „Zulassen“ wählen“. It listens for the screen itself. While the screen is on it
holds `GET /napi/portal/events` and reads one frame, `card_state`; on
screen-off it drops the connection. Once connected it posts its watch set
(`POST /napi/portal/watch`: `entity_ids`, the device widgets' devices and,
while the energy widget is placed, the energy flow's sensors and the battery's charge; re-posted inside Homes' one-hour TTL; a 503 while
Home Assistant is down is retried with backoff, about two minutes) and has
every fed widget read again.

- `gemeinsam` (`{teil}`) syncs that part of the Homes account, see (g).
- `card_state` (`{entity_id, card}`) draws the device widgets of that entity
  without a read; for an energy sensor it reads the energy
  widget again, at most every 10 s, Homes' sensor interval. Any other frame
  reads nothing. The app never asks for `active_set` (no `active` in its
  watch), and a stray one is not read.

A 401 stops the service.

It starts from allowed starts only (Android 12+): the app's screen, a widget
tap, a configure screen (the fed widgets without a choice have a no-UI one,
so placing them counts), boot and an app update. After its process died,
Android restarts it. On Android 12+ that restart is a background start, which
is documented as allowed while the app is exempt from battery optimization —
asked once after pairing, shown beside the switch. (On the e2e's emulators,
API 31 to 35, the restart also went foreground without it; that is not
documented, and an OEM build may refuse it. A refused restart ends quietly,
without a notification, until the next allowed start.) A connection silent for 240 s (Homes pings every
90 s) counts as dead and is reopened. A session counts as healthy — and resets the reconnect
backoff — only after 120 s, longer than Homes' 90 s ping, so a proxy that
cuts idle streams does not make the app reconnect every minute. With the screen
off nothing goes over the network for the widgets: no stream, no poll (every
widget's update period is 0), no catch-up, and no other request: every widget
call to Homes passes `data/ScreenGate.kt`, so a widget update Android delivers
late reads nothing and keeps its last card. The one exception is the backup
job of (e). Messages to people go
through their chat channel, not the app (laib ADR 0022 §3).

## (d) Pages

A widget's body tap opens one of Homes' pages in the browser at the paired
address: `/energie` for the energy widget, `/start` for a tool widget's
header, a device's own page for a device widget whose device a tap cannot
switch (a lamp, a switch, a cover, a lock act on the tap instead). The app has
no web shell of its own; its start
screen opens Homes' pages in a Custom Tab: „Alle Geräte“ `/raeume`, a
favorite's own page on a long press (or on a tap, for a device a tap cannot
switch).

An item opens its own page, base + `page`: a favorite on the start screen, a
tool row. Only a path on Homes is taken (it starts with a single `/`, has no
`..`, no backslash, no whitespace); an absolute URL or anything else is never
opened. Without a page a favorite opens `/start`, and a tool row keeps its
tap: a row with actions opens its sheet — with "Öffnen" as one more entry,
after the actions, when it has a page — and a row with neither does nothing.
A collection row has one tap target, so tool rows go through one translucent
screen (`ToolActionActivity`) that opens the page or the sheet.

Every page a widget opens carries `von=widget.<kind>` exactly once (`?von=…`,
or `&von=…` after a query the page already has; a `von` of the page's own is
replaced), so Homes counts server-side which widget a tap came from (#180).
The kinds (`WidgetKind` in `widget/HomesPages.kt`): `device`, `energy`, `tool`. The app counts nothing and makes no call for it; a
launcher shortcut and the app's own links carry no `von`.

## (d2) The start screen's devices

(Owner, 2026-11-02: the devices live in the app, the device, active-devices
and camera widgets are gone.) Paired, the app's screen shows first the
person's favorite devices — `GET /napi/portal/favorites`, the things they
pinned with ☆ on Homes, in their order — as tiles (owner, 2026-11-04,
`ui/FavoritenAnsicht.kt`), and in the section's head „Alle →“, which opens
Homes' `/raeume`. The list read last is kept and drawn at once; a read that
fails keeps it. A tap acts as the device widget's did, through
`widget/WidgetActionReceiver`: a light or a switch toggles, a garage, a door
or a gate opens or closes (asking first: Homes' 403), a lock opens its
chooser (#92: „Tür öffnen“ only when the card's `supported_features` names a
latch); a cover with a position opens its level sheet; anything else opens
its page. A long press opens the device's page. When the call is done the
list is read again. With no favorites the screen says in one sentence how to
get some.

Each favorite carries the fine controls Haus's devices page has (owner,
2026-11-03; `ui/DeviceControls.kt`), read off the card's existing fields, no
new route: a lamp that dims its brightness (`light.turn_on` with
`brightness_pct`, 0 is `light.turn_off`); a cover whose `device_class` is
named and is not a garage, a door or a gate its position
(`cover.set_cover_position`, the sheet's Auf, ½, Zu and Stopp); every cover,
a garage too, Hoch, Stopp, Runter as ▲ ■ ▼ on its tile, each as
`supported_features` allows; a
heating that is on Kälter and Wärmer (`climate.set_temperature`, by
`target_temp_step` within `min_temp`/`max_temp`); a playing or paused player
Pause or Weiter, Leiser and Lauter. Each is one `POST /napi/ha/call` through
the device receiver; a call without data that Haus answers 403 asks in the
one dialog, a call with data (only offered where Haus lets it go freely) that
is refused says it did not work.

## (e) Daten sichern

(#194, laib ADR 0029; Homes' side is homes#207.) Under Mehr → Nachrichten
sichern the person grants two folders through the system folder picker
(`ACTION_OPEN_DOCUMENT_TREE`, a persisted read grant; no storage permission):
WhatsApp's, `Android/media/com.whatsapp/WhatsApp` (the picker opens there),
and the one they chose in Signal for its backups. The app only reads them.

One WorkManager job (`sicherung/SicherungWorker.kt`) runs daily on an
unmetered network while the phone charges and its battery is not low, and
once at once on „Jetzt sichern“ (unmetered network only). It never goes
foreground. It sends:

- **WhatsApp:** `Databases/msgstore.db.crypt15` when it is not the one sent
  last (name, size, time kept in the app's prefs), and the files under
  `Media/` Homes reports missing (`POST /napi/sicherung/fehlend`, at most 5000
  paths a call). A path with a dot-name at any level (`.Statuses`,
  `.nomedia`) is never asked about or sent: Homes refuses the whole question
  for one.
- **Signal:** the newest `signal-….backup` directly in its folder, only when
  it is newer than the one sent last. Its `pfad` is its file name.

A file changed in the last ten minutes waits for the next run (it may still
be being written).

The upload: `POST /napi/sicherung/uploads` `{quelle, pfad, groesse}` → 201
`{id, offset}`; then `PATCH /napi/sicherung/uploads/{id}` with
`Upload-Offset` and `Content-Type: application/offset+octet-stream`, 8 MiB a
piece (Homes takes 16), → `{offset, fertig}`. The id is kept per file until it
is whole; after any interruption the next run asks `HEAD …/{id}`
(`Upload-Offset`) and goes on from there. An offset equal to the size — after
HEAD (Homes keeps a finished upload known for seven days) or straight from
the creation — is a finished file. A 409, or a 408 after a partial write,
goes on from the `offset` Homes returns; a 404 starts a new upload; a 429 (the
box takes other pieces) and no answer end the run and WorkManager runs it
again with backoff. 507 (full), 503 (no archive), 403 (a device of nobody)
and 401 stop the run with a German sentence on the screen. Errors are
`{"fehler": …}`, except the 401 of the shared login check
(`{"error":"unauthorized"}`), for which the app says to pair again.

`GET /napi/sicherung/stand` → `{quellen: {whatsapp|signal|sms: {zuletzt,
dateien, bytes, wartend}}, frei_bytes}` is read for its refusal only (no
archive on the box, not paired), one sentence on the screen: what arrived
per source, and the room left, are Homes' page `/daten-sichern` (owner,
2026-11-08: the screen keeps only what needs the phone, one line per
source, and opens that page as „Alles im Überblick“). The app never asks
for or stores a messenger's key: keys go into the person's vault on the
box (laib#986); a source's folded guide opens the archive's `/tresor`.

The screen's progress is the run's totals per source — files whole of all
it sends, bytes on the box of all their bytes („3.809 von 18.017 Dateien,
1,2 von 9,8 GB“), the file under way counted as far as it got — not one
file's bytes (owner, 2026-11-01).

### Sicherung zurückholen

(homes-android#197; Homes' side is homes#209, laib ADR 0029 amendment
2026-11-01.) On a new phone where the app is paired: `GET
/napi/sicherung/letzte` → `{quellen: {signal|whatsapp: [{pfad, groesse,
geaendert}]}}` (epoch ms; a WhatsApp medium as `Media/…`, its plaintext
size) is what the box keeps. Each file comes with `GET
/napi/sicherung/letzte/{quelle}/{pfad...}` (every segment URL-encoded, the
`/` kept), `Range: bytes=<n>-` → 206 with `Content-Range` (200: whole) and
an `ETag`. The person grants a folder per source with write access
(`ACTION_OPEN_DOCUMENT_TREE`, read and write persisted): for Signal any
folder (e.g. „Dokumente/Signal“), the `.backup` goes into it; for WhatsApp
`Android/media/com.whatsapp/WhatsApp` (or `Android/media/com.whatsapp`, or
`Android/media` when nothing is there yet; the rest is made), the files go
to their `pfad` below it, the media first and the database last.

- **Nothing is written over without asking.** A file already there that
  the restore did not write itself is left alone when it has the box's size,
  and otherwise only replaced on „Ersetzen“; the app keeps which files it
  wrote, with the box's ETag, in its prefs.
- **Resumable.** A file of its own goes on from its size with a Range; one
  whose ETag changed on the box (the next night's backup) starts over.
  „Anhalten“ stops after the piece being written. The run is a thread of the
  app while its screen stays on (`FLAG_KEEP_SCREEN_ON`), no job.
- **Media** come only while the person's archive is open: 423 with Homes'
  sentence, then no further medium is asked for, the database still comes,
  and the screen offers „Archiv öffnen“ (`archiv.<domain>` beside
  `homes.<domain>`). 404 is a backup gone from the box: the list is loaded
  again.
- `sicherung/SafZiel.kt` is the one file that writes a document: it makes
  folders and files and writes into them, never deletes, renames, moves or
  copies one (`ManifestTest`).

### Kontakte, SMS und Anrufe

(laib ADR 0029; Homes' side is homes#210.) Two switches on the same screen,
off until the person turns one on, each asking for its own permission when
turned on; refused, it stays off with a sentence. The same job runs them,
after the folders, only while the switch is on and the permission granted.

- **„Kontakte“** (READ_CONTACTS): every contact, read from
  `ContactsContract.Data` and written by the app as vCard 3.0 (not Android's
  2.1 export, which Radicale may refuse) with `UID:<lookup key>`, escaped
  text, lines folded at 75 octets and CRLF, all concatenated into one
  `POST /napi/kontakte` (`Content-Type: text/vcard`) → `{kontakte, neu,
  geaendert, entfernt}`. Homes mirrors the set into the person's own address
  book „Handy-Kontakte“, which the box's admin can read like the calendar.
  No contact on the phone: no request. A contact's own picture goes as its
  `PHOTO;ENCODING=b;TYPE=JPEG` (Android's large one, else the thumbnail,
  at most 256 px a side), for Homes to ask Immich whose face it is
  (homes#298); the face the Homes account added never.
- **Korrekturen** (Kontakt prüfen lassen, owner 2026-11-07; Homes'
  side mdopp/homes#301; WRITE_CONTACTS, asked for by the same switch): in
  the same run, before the contacts go up, `GET /napi/kontakte/korrekturen`
  → `{korrekturen: [{id, uid, name, aenderungen: [{nr, art, vorher,
  nachher, vorher_art, nachher_art, vorher_haupt, nachher_haupt,
  vorher_adresse, nachher_adresse, bild}]}]}`, the changes the person
  accepted on Homes' review of one of their own contacts. Since homes#304
  (owner 2026-11-08): a number's kind (mobil, privat, geschaeftlich,
  andere → Phone.TYPE MOBILE, HOME, WORK, OTHER) and an e-mail's (privat,
  geschaeftlich, andere → Email.TYPE HOME, WORK, OTHER) are written only
  when they changed, the main one (`*_haupt`) as `IS_PRIMARY` and
  `IS_SUPER_PRIMARY`; `art` `foto` replaces the raw contact's picture with
  `bild` (a JPEG of at most 512 px, Homes made it from the pixels alone);
  `art` `notiz` adds `nachher` below the note after an empty line (once).
  An address's `strasse` is the one street line. The other way, „Kontakte
  übertragen“ sends the phone's primary number and e-mail as `TYPE=PREF`. `uid` is the lookup key this switch sent; the app finds the
  phone's contact by it (`Contacts.lookupContact`, which follows Android
  joining and splitting raw contacts; a contact that went by its id only
  is taken only while its name is still `name`) and writes each change into
  the data row of its writable raw contacts (not the Homes account's, not a
  messenger's read-only one) that still holds `vorher`, or adds a row to
  the raw contact with the name; a field changed on the phone meanwhile is
  left alone. The writes go as the app, not as a sync adapter, so the
  contact's own account (Google) syncs them. Then `POST
  /napi/kontakte/korrekturen/{id}` `{geschrieben: [nr…]}`: what it wrote,
  or found written already; Homes shows the rest as not written. A phone
  contact is never touched without such a correction. Homes' own come the
  same way (owner 2026-11-09, „App erweitern“; only to the Immich key
  owner's devices): a contact whose Immich person was renamed gets
  `vorname`/`nachname`, and `uid` `""` is a **new contact** of a person
  newly named in Immich, `name` its name, `vorname`, `nachname` and the
  face as `foto`. The app makes one raw contact with those rows in one
  write, in the Google account most of the person's contacts are in (none:
  the phone itself), unless a contact of that name is there already
  (then all said written, nothing made); the next „Kontakte übertragen“
  carries it to Homes.
- **„SMS und Anrufe“** (READ_SMS, READ_CALL_LOG): the SMS and
  MMS, and the calls, newer than a high-water mark per kind (the largest
  date that reached the box; the first run takes everything), as XML in the
  format of „SMS Backup & Restore“ by SyncTech (`<smses>` and `<calls>`, what
  `msgvault import-synctech-sms` reads), written as a stream into the app's
  cache. Each file is age-encrypted on the phone (age v1, one X25519
  recipient: `GET /napi/sicherung/schluessel` → `{empfaenger: "age1…"}`, or
  404 with Homes' sentence, shown as it is), the plaintext deleted, and the
  `.age` file uploaded through the resumable upload above as `quelle` `sms`,
  `pfad` `sms-<UTC yyyyMMddTHHmmssZ>.xml.age` and `anrufe-<…>.xml.age`. A
  mark moves only once its file is whole on the box; an unfinished file is
  sent on by the next run before anything new is read.

On Android 13+ an app installed outside a store cannot even ask for SMS and
the call log ("restricted settings"): the screen then says to open App-Info →
⋮ → „Eingeschränkte Einstellungen zulassen“ and turn the switch on again.

## (f) Standortverlauf

The owner's call, 2026-11-02. Off until the person turns its switch on
(Mehr → Standortverlauf), behind its own permissions: the location with
„Immer zulassen“ (`ACCESS_FINE/COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`).
No Google Play services (the owner, 2026-11-02): the platform alone.

- The phone: Android's LocationManager, delivered by PendingIntent to
  `standort/StandortEmpfaenger` — no service, job, alarm or wakelock of the
  app's. The provider (`Anbieter`): high accuracy is GPS; balanced is the
  network provider, else the platform's fused one (Android 12+), else GPS.
  On Android 12+ the request carries quality and batching
  (`LocationRequest`), before that the plain interval. While the phone rests,
  a second request on the passive provider takes the fixes other apps cause.
- The rate (`standort/Plan.kt`, chosen, not measured): „Detailliert“ on the
  move every 15 s (GPS), resting 5 min, resting on a Wi-Fi hourly;
  „Sparsam“ on the move every 2 min (network), resting 15 min, resting on a
  Wi-Fi every 2 h. Any Wi-Fi counts (owner: „Wenn WLAN, bewegt sich
  selten“).
- Resting (`standort/Ruhe.kt`): the fixes stay within their accuracy (at
  least 30 m) of one place for 3 min (Detailliert) or 10 min (Sparsam); a
  fix less exact than 500 m counts for nothing. It ends with a fix farther
  away, or when the platform's significant-motion sensor fires — armed while
  the phone rests, one-shot, alive only as long as the app's process. A
  phone without that sensor keeps the plain interval and the screen says so.
- The points wait in a file of the app's (`standort/Puffer.kt`, at most
  50 000, the oldest dropped beyond and said on the screen) and go in
  batches of ≤ 500 when 200 wait or the oldest waited 15 min
  (Detailliert) / 30 min (Sparsam), at a location delivery that woke the
  phone anyway, or at once on „Jetzt senden“ and when the screen opens.
- `POST /napi/standort {punkte:[{zeit, breite, laenge, genauigkeit, hoehe?,
  tempo?}]}` → 200 `{angenommen, verworfen}` once Reitti has them: only then
  do they leave the phone. Any other answer keeps the batch, with a pause of
  5 min doubling to 2 h: 409 `{fehler, grund: kein_schluessel |
  schluessel_abgelehnt}`, 503 `{fehler, grund: reitti_weg |
  nicht_eingerichtet}`, 401 (pair again), no answer.
- `GET /napi/standort` → `{verbunden, seit?}`: without a connection the
  screen says in one sentence to paste the Reitti token on Homes' page
  `/standort`, and a tap opens it there. The token never reaches the phone.

### Standortverlauf übernehmen

The owner's call, 2026-11-03: an old location history goes into Reitti by
sharing the file to the app — no browser, no Reitti login. The share
target „In den Standortverlauf übernehmen“ (`ui/StandortImportActivity`)
takes `ACTION_SEND` of `application/json`, `application/geo+json`,
`application/gpx+xml`, `application/gpx`, `application/xml`, `text/xml`,
`application/octet-stream`, `application/zip`,
`application/x-zip-compressed` and `application/x-zip`; the name decides (`.json`, `.geojson`,
`.gpx`, `.zip`), and a file without one is named by its type. Homes reads
Google Maps' Timeline.json (Android and iPhone), Takeout's Records.json,
GPX and GeoJSON, and hands the points to the person's Reitti with their own
token (mdopp/homes#234). A Google Takeout ZIP (owner, 2026-11-04,
mdopp/homes#274) goes as it is, streamed like any other file in the same
resumable pieces: the app neither unpacks nor holds it, Homes stages it on
its disk and reads it member by member. A Takeout comes in parts of up to
50 GB, each its own share and its own import; a part without a history
ends `fertig` with Homes' gentle sentence to share the other part too. A
ZIP goes to the Hochladen job (owner, 2026-11-04; see (j)), so it keeps
going with the screen off; its notification says how far it got and Homes'
sentence at the end. An older Homes refuses a ZIP with its sentence, which
the notification says as any refusal.

- The upload is the backups' (e): `POST /napi/standort/import {name,
  groesse}` → `{id, offset}` (the same name and size resume; 409 `{fehler,
  grund: kein_schluessel}` before the person connected Reitti — the dialog
  offers „Reitti verbinden“, Homes' `/standort`), `HEAD
  /napi/standort/import/{id}` → `Upload-Offset`, `PATCH
  /napi/standort/import/{id}` in pieces of 8 MiB (`standort/Import.kt`).
  After a cut the app asks HEAD and goes on; „Abbrechen“ stops, and the
  file shared again goes on where it stopped. At most 50 GB (5 GB on a Homes
  before homes#274); a piece is at most 16 MB on Homes' side.
- Then `GET /napi/standort/import/{id}` every 2 s: `{stand: hochladen |
  laeuft | fertig | fehler, gelesen, groesse, punkte, text?, fehler?,
  grund?}`. The dialog shows the per cent read and the places so far, and
  at the end Homes' sentence (`text`, e.g. „12.345 Orte von März 2014 bis
  Dezember 2024 übernommen …“) or why not (`fehler`). Five minutes without
  progress end in a sentence, not a spinner; „Schließen“ leaves the import
  to the box. It runs in the share dialog only: no job, no service.

## (g) Das Homes-Konto

(#206, the owner's call of 2026-11-03; Homes' side is homes#218.) The
household's shared data on the phone, in its own apps: an Android account
„Homes“ (type `cloud.dopp.homes`) with two sync adapters, each behind its own
switch under Mehr → Homes auf dem Handy and its own permission.

- **Haushalts-Kontakte** (WRITE_CONTACTS): `GET
  /napi/gemeinsam/kontakte?schreibbar=1` → `text/vcard`, every card of
  Homes' own address books, each that can be written back with its version
  `X-HOMES-ETAG`. Each card one raw contact of the account, SOURCE_ID the
  SHA-1 of its UID, SYNC1 the hash of its text, SYNC2 the UID, SYNC3 the
  version. Visible without a group, so the dialer, WhatsApp and mail find
  them. Homes' links on a card (`homes-dokumente`, `homes-nachrichten`,
  `homes-fotos`: a grouped URL with its X-ABLabel) are Website rows of that
  label, and never go back.
- **Pictures** (the owner's call of 2026-11-05, homes#287): a card's PHOTO
  (vCard 3.0 `ENCODING=b`, 4.0 a `data:` URI; a link is left out) is the
  contact's picture, a `Photo` data row. Homes adds the face of the card's
  Immich person to a card without a PHOTO, as `homes-foto.PHOTO;…;X-HOMES=immich`
  (≤ 96 px); that row carries SYNC1 `homes-foto`. Android re-encodes a
  picture, so each row's SYNC2 is the hash of the bytes as read back after
  the write. A card goes back with a PHOTO only when the picture is the
  person's: set on the phone (no SYNC2) or changed there (its bytes no
  longer have that hash); then it is Android's large picture (else the
  thumbnail), a JPEG of at most 256 px, `PHOTO;ENCODING=b;TYPE=JPEG:…`.
  Homes' face, and a picture as Homes sent it, are not sent: Homes drops
  its face anyway (by group, X-HOMES or its bytes) and keeps a card's
  stored picture when none comes. A picture removed on the phone is not
  removed in Homes.
- **Haushalts-Kalender** (READ_ and WRITE_CALENDAR): `GET
  /napi/gemeinsam/kalender?schreibbar=1` → `text/calendar`, one VCALENDAR
  per shared calendar (`X-WR-CALNAME`, `X-HAUS-KALENDER` its id,
  `X-HAUS-ART` its kind, `X-HOMES-SCHREIBBAR:TRUE` when the phone writes
  into it), the entries with one a month back to a year ahead as Homes keeps
  them: a series whole (RRULE, EXDATE) with its changed instances
  (RECURRENCE-ID, written as exceptions of the series), every alarm, each
  VEVENT with `X-HOMES-ETAG`; last „Erinnerungen“, each open reminder with a
  due time as an entry with a display alarm (a recurring one with its RRULE
  in the house's time zone). Each calendar one calendar of the account,
  CAL_ACCESS_OWNER when writable, else (subscriptions, the reminders)
  CAL_ACCESS_READ; an entry's SYNC_DATA2 its version, SYNC_DATA3 the
  calendar Homes has it in.
- **Writable** (the owner's call of 2026-11-05, homes#278; read only until
  then): the calendar app and the contacts app offer the account
  (`supportsUploading`, the contacts' edit schema); what they make, change
  or delete there is dirty, and each sync sends it first: `PUT
  /napi/gemeinsam/kalender/{kalender}/{uid}` (one VCALENDAR: the fields the
  calendar shows, the alarms, a series in its time zone with DURATION) or
  `PUT /napi/gemeinsam/kontakte/{uid}` (a vCard 3.0), with `If-None-Match:
  *` when new (a fresh UUID) or `If-Match: <version>`; Homes' new `ETag` is
  kept and the entry is clean. A delete is `DELETE` of the same with
  `If-Match`, into Homes' Papierkorb. An entry moved from one of Homes'
  calendars into another is made in the new one, then deleted from the old.
  412 (Homes has another version) or a refusal: Homes' version wins, the
  phone's is dropped, the screen says so. A single changed instance of a
  series made on the phone is not sent (dropped, said); a deleted single
  instance goes as an EXDATE of its series. Homes not reachable: nothing is
  fetched, the changes wait for the next sync.
- Both carry an ETag; the app sends it as `If-None-Match` and Homes' 304
  costs nothing. A 304 while the phone holds fewer entries than it wrote
  fetches all again.
- When: Android's SyncManager, at the switch, on „Jetzt abgleichen“, and
  every hour while the account syncs automatically. No job, alarm or worker
  of the app's own.
- Push (#209, homes#223): while the live stream is open (c), Homes sends
  `event: gemeinsam`, `{teil: kontakte|kalender}`, when that part changed
  (it reads its Radicale home's getctags every 30 s while a phone listens).
  The app asks Android to sync that part, only with its switch on, at once
  and then at most once more at the end of a 30 s window. With the stream
  down nothing new happens; the hourly sync stays.
- Refusals `{fehler}` (403 a device of nobody, 503 no Radicale, 502 Radicale
  refused, 507 the address book too big to read whole): the sentence under
  the switch, Android backs off. 401: pair again.
- Off: the part's entries leave the phone; both off, the account goes.
  Unpairing removes it.
- **No loop:** „Kontakte übertragen“ (e) leaves the account's rows out. A
  contact Android joined from the person's own and a shared one goes with the
  person's own data and the UID it had before the join (the shared raw
  contact's part taken out of the lookup key); one with nothing of the
  person's but a name (a messenger's rows) does not go.

## (h) What the box can do

(homes ADR 0001 §2, §3, §10; homes-android#231; Homes' side is homes#242
and homes#251.) `GET /napi/kann` → `{version: 1, module: [...], app:
{widgets, teilen, konto, sicherung, mehr}}`: `app` is what the box offers
the app, in the app's keys; `module` is every module, on or off, with its
title, why it is off (`grund`), what it would bring (`bietet`), its trace
(`spur_id`: `fehlt`, `aus`, `angefordert`, `wartet`, `abgelehnt`,
`eingerichtet`; `spur` the German words) and whether it offers „Hier
anfordern“ (`anfordern`).

- **When the app reads:** at start and resume, at most once a minute; when
  the live stream connects (a restarted Homes may offer something else);
  never with the screen off. Always with the kept ETag as `If-None-Match`,
  so an unchanged answer is a 304. The last answer is kept
  (`kann/KannStore.kt`) and used offline.
- **How it reads it:** an unknown key or field is ignored; a known key
  missing from a list Homes sent means the box lacks that feature; a list
  Homes did not send says nothing. A 404 (an older Homes) forgets what was
  kept, and with nothing kept the app offers everything, as before.
- **Nothing is hidden (§10):** a feature the box lacks leaves a quiet trace
  — grey, no red, no warning sign. The widget list shows a missing type as
  a muted entry that cannot be placed; Mehr shows its own rows by `mehr`
  and ends with „Gibt es hier noch nicht“, one trace per off module (not
  the `admins` ones), each with „Hier anfordern“ while `anfordern` is true.
  A placed widget whose type went shows the module's `grund`; a tap opens
  the app.
- **Share targets** (`teilen`: `merken` text, `dokument` images,
  `standort-import` history files): one component each — the share
  activity's aliases `.ui.TeilenMerken` and `.ui.TeilenDokument`, and
  `.ui.StandortImportActivity` — enabled while the key is there, disabled
  while it is missing (`PackageManager.setComponentEnabledSetting`,
  DONT_KILL_APP; `kann/Teilen.kt`), all enabled while unknown. The Direct
  Share target names the category of each enabled one and is gone with
  neither. Android's share sheet cannot show a disabled target: its trace is
  its module's line in Mehr, which says what sharing to it would do.
- **Account, backup, widget providers** (`konto`, `sicherung`, `widgets`):
  a part the box lacks is a switch off and disabled on its screen, its
  trace below. A Homes-Konto part that was on stops syncing
  (`setIsSyncable(…, 0)`, no periodic sync) and its entries stay on the
  phone; it syncs again when the box offers it again (`HomesKonto.folgen`).
  The backup job skips a part the box lacks. The device and energy widget
  providers are disabled only while their key is missing and none of their
  widgets is placed (`kann/Anbieter.kt`).
- **„Hier anfordern“:** `POST /napi/kann/anfordern {"modul": …}` → 200, the
  module's entry with its new state, which replaces the kept one. Pressed,
  the button says „Wird angefordert …“; it ends with the new state, Homes'
  sentence (`{"error"}`; 404, 502, 503, 504), „pair again“ on a 401, or the
  app's own words when Homes did not answer in time or cannot be reached.

## (i) Öffnen, Deine Aufgaben, the launcher's shortcuts

(The owner's call, 2026-11-04; Homes' side is mdopp/homes#269,
docs/REFERENCE.md there.) `GET /napi/einrichtung` → `{version: 1, offen,
module: [{name, titel, icon, oeffnen, web, ziel?, stand?, app: {name, paket,
store}, nur_web, aufgabe: {titel, erledigt, am, wie, einrichten}}], ziele:
{einkauf, zeitleiste, scannen}}`, the device's person's modules that are on.

- **When the app reads:** at start and resume, at most every five minutes,
  and when the answer of `/napi/kann` changed (so also right after the
  pairing); never while the screen is off. The answer is kept
  (`einrichtung/EinrichtungStore.kt`) and used offline. An older Homes
  answers 404: the app forgets what it kept, and „Öffnen“, „Deine
  Aufgaben“ and the shortcuts that need it are gone, quietly.
- **„Öffnen“** below „Deine Geräte“: Homes decides it (owner, 2026-11-07;
  mdopp/homes#300). `oeffnen` (bool) per module marks the ones the app lists,
  in the answer's order, each only with a `web`; a missing `oeffnen` counts as
  false, and the app has no list of its own any more (2026-11-08). A row
  opens `ziel` where Homes names one, else `web` (owner 2026-11-08: „direkter
  bauen“; mdopp/homes#310: for Standortverlauf, once the person connected
  Reitti, Reitti itself, so a tap lands in the timeline, not on the page with
  the token form — „Trennen“ stays on that page, reached from Mehr →
  Standortverlauf's line „Reitti ist verbunden.“ or from Homes' Mehr), and
  says `stand` under its title where Homes sends one („Zuletzt aufgezeichnet
  heute 08:40“, „Noch nicht verbunden“; Musik: „2 Dateien nicht übernommen“).
  Both are https-checked and trimmed like `web`; absent, the row is as before.
  Each opens in a Custom Tab,
  where the person's login is; below them „Homes“, Homes' own start page
  (`/start` of the paired address), whenever the box answered. A module
  with its own app is a task while the app is missing, and then the
  phone's own app icon is the way in. Pictures by `icon` (`einrichtung/Abschnitte.kt`, `Bilder`):
  the drawables are the app's own, so Homes sends a key, never a picture;
  an unknown or missing key gets the generic one.
- **„Deine Aufgaben“** with `offen` beside its title: the tasks with
  `erledigt: false`. A tap opens the app's `store` page while the app is
  not on the phone, else `einrichten` in a Custom Tab.
- **The apps the phone has:** after a read, when the app of an open task
  is installed, `POST /napi/einrichtung/apps {"pakete": [...]}` with every
  module app installed; Homes ticks those tasks and answers as the GET.
  Android 11+ answers only for the packages the manifest's `<queries>`
  names, one by one (never QUERY_ALL_PACKAGES): Immich, Jellyfin,
  Bitwarden, Audiobookshelf, Signal, Home Assistant.
- **Offline** (Verbindung): both sections show the answer read last,
  greyed, and say nothing of their own.
- **The app icon's long-press menu**, exactly these, in this order, each
  only where the box has it, built anew from each answer: **Garagentor**
  (the first cover of class garage or gate among the favourites, else the
  household's devices; its tap is the tile's tap, Homes' 403 puts the
  confirm before an open), **Einkaufsliste** (`ziele.einkauf`) and
  **Zeitleiste** (`ziele.zeitleiste`) in a Custom Tab, **Dokument
  scannen** (`ziele.scannen`): the camera app, then the photo goes the
  way a shared photo goes, `POST /napi/upload`. The camera app writes into
  the app's cache through the one file provider (`cache/scan/`); Android
  wants the camera permission first, as the QR scanner declares it. The
  share target of (b) stays last.

## (j) Hochladen: music and Takeout ZIPs

(The owner's call, 2026-11-04: a large upload keeps going with the screen
off, with its own notification.) The share target „Zur Musik hinzufügen“
(`hochladen/MusikTeilenActivity`) takes `ACTION_SEND` and `SEND_MULTIPLE`
of `audio/*`, `application/zip`, `application/x-zip-compressed`,
`application/x-zip` and `application/octet-stream`; the name decides
(`.mp3`, `.flac`, `.m4a`, `.ogg`, `.opus`, `.wav`, `.zip`), a file without
one is named by its type. It is switched by the box's `teilen` key `musik`.

- A ZIP shared to the music is looked into first (owner, 2026-11-04): by
  the names in its central directory, read from the app's copy — never
  unpacked. With no song in it the dialog asks „Das sieht nicht nach Musik
  aus – trotzdem hochladen?“; one whose names start with `Takeout/` is
  offered to the Standortverlauf instead („Zum Standortverlauf?“).
  Abbrechen drops the copy. What beets later keeps back the app does not
  learn (no call for it; no polling): Homes' `/musik` lists it, „nicht
  einsortiert: <Grund>“, with „Löschen“.
- Each shared file is copied into the app's own storage first — streamed,
  never held in memory — because a share's files are readable only while
  it is open; then the job „Hochladen“ (`hochladen/Hochladen.kt`, a
  WorkManager worker) takes the queue in order.
- Music: `POST /napi/musik`, multipart, one `file` part with the file's
  name, streamed in pieces → `{ok, dateien}`; a refusal `{ok: false, error}`
  is said as Homes words it; 404 (a box without music, or an older Homes):
  „Die Box kann das noch nicht – kommt mit dem nächsten Update.“ A cut
  starts the file again from the beginning (Homes takes it in one request,
  up to 4 GB, within an hour).
- A Takeout ZIP for the Standortverlauf: the import of (f), in its resumable
  pieces, from where Homes stands.
- While it uploads the job runs in WorkManager's foreground service as a
  `dataSync`; its notification (channel „Hochladen“) shows the file and the
  per cent, then what came of each file. Android 15 allows `dataSync` six
  hours a day: when they run out, or Android does not let it start, or the
  line is gone, the notification says „Hochladen pausiert“ and that it goes
  on later — by itself, or at once when the app is opened — and WorkManager
  runs it again. Never a silent stop.
- What the notification says shows in the open app too (owner,
  2026-11-04), from the one state the job writes (`hochladen/Stand.kt`): a
  quiet row at the top of the start screen — „Lädt hoch: <Datei> · 40 %“,
  „Hochladen pausiert – geht später weiter“, or what came of the files („3
  Dateien in der Musik“, „Standortverlauf: …“, why not). Running and paused
  stay until done; a result, once seen in the app, goes with its
  notification. The channel „Hochladen“ shows a badge on the app's icon;
  the live widgets' channel does not (`live_widgets_v2`, the old one
  deleted): nothing there to act on, and the start screen says the line's
  state itself (`verbindung/`).

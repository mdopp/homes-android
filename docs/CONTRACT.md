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
without one and a JSON 404 for a path it does not serve. Every request also
carries the phone's languages as `Accept-Language` (`data/Sprache.kt`, the
owner's call of 2026-11-18): Homes answers its words — `/napi/kann`'s titles
and traces first — in de, en, es or fr by those weights, German otherwise, and
says `Vary: Accept-Language` (mdopp/homes#380). Each call is declared
as data in `app/src/main/java/cloud/dopp/homes/data/Napi.kt` — method, path,
query parameters (and the values Homes takes), body fields, the reply keys the
app reads — and `data/NapiRequest.kt` is the one place a request is built from
it. `NapiContractTest` holds every declaration to a copy of Homes' handlers,
each row naming the Homes file and lines it came from.

| Widget | Path |
|---|---|
| pairing | `GET /napi/whoami`, `GET /napi/assistent` |
| the start screen's claim | `GET /napi/box/home`, read for its `gut` alone (owner 2026-11-27, mdopp/homes#389): „Deinem Zuhause geht es gut.“ under the logo only for `gut: true` — Laib ready, no problem, every service green; `false`, absent (an older Homes) or a failed read leave the neutral „Dein Zuhause, klar im Blick.“, which also stands until Homes answered. Read when the start screen opens or comes back, with the screen on (`ScreenGate`); never in the background |
| the start screen's devices | `GET /napi/portal/favorites` (the person's ☆ things on Homes, as cards in their order; homes#213), `POST /napi/ha/call`, `GET /napi/portal/state` (a cover's or a lock's state before its toggle) |
| one device (widget, restored 2026-11-03) | `GET /napi/portal/state`, `POST /napi/ha/call`; its picker `GET /napi/portal/start/addable` |
| app-icon shortcuts | Garagentor, Einkaufsliste, Zeitleiste, Dokument scannen: see (i); the garage among the favourites, else `GET /napi/portal/start/addable` (without cameras: Homes draws no card for one), `POST /napi/ha/call` |
| the start screen's „Energie“ tile (owner 2026-11-28: the energy widget went, „nur als Link zur Anzeige“) | `GET /napi/portal/energy`: the house's `draw` leg now and the sun's total `today` (else the grid's), as one line „Haus 260 W · heute 5,3 kWh erzeugt“; read when the start screen draws, at most every five minutes, with the screen on, only while `/napi/kann` lists `widgets` `energie`; the tap opens `/energie` in a Custom Tab |
| Daten sichern | `GET /napi/sicherung/stand`, `POST /napi/sicherung/fehlend`, `POST /napi/sicherung/uploads`, `HEAD`/`PATCH /napi/sicherung/uploads/{id}`, `GET /napi/sicherung/schluessel`, `POST /napi/kontakte`, `GET /napi/kontakte/korrekturen`, `POST /napi/kontakte/korrekturen/{id}`, `GET /napi/sicherung/letzte`, `GET /napi/sicherung/letzte/{quelle}/{pfad...}`: see (e) |
| Standortverlauf | `GET /napi/standort`, `POST /napi/standort`, `…/standort/import…`: see (f) |
| Homes-Konto | `GET /napi/gemeinsam/kontakte`, `GET /napi/gemeinsam/kalender` (`?schreibbar=1`), `PUT\|DELETE /napi/gemeinsam/kalender/{kalender}/{uid}`, `PUT\|DELETE /napi/gemeinsam/kontakte/{uid}`: see (g) |
| what the box can do | `GET /napi/kann`, `POST /napi/kann/anfordern`: see (h) |
| Öffnen, Deine Aufgaben | `GET /napi/einrichtung`, `POST /napi/einrichtung/apps`: see (i) |
| Share → Musik, Hochladen | `POST /napi/musik`, multipart, one `file` part a request, streamed from the app's copy: see (j) |
| Share → Homes, photos | `POST /napi/upload`, multipart `filename` then `file`, one photo a request → `{ok, id}`: the camera app's photos (one or several, `image/*`) into the folder Paperless consumes (owner, 2026-11-02). JPEG, PNG, WebP and GIF of at most 25 MB go as they are; HEIC/HEIF or anything larger is made a JPEG on the phone (long edge ≤ 4096). At most 20 a share. Homes' refusals are codes; the app says them in German by status (404 no folder, 413, 415, 429 busy, 503, 507 full) |
| Share → Daten erfragen | `POST /napi/kontakte/fragen` `{name, telefon: [...], mail: [...], anlegen?, uid?}`: a contact's vCard from the contacts app, Homes answers the link that asks its person for what is missing: see (l) |
| Teilen → Kontaktdaten eintragen lassen | `POST /napi/kontakte/einladen` `{}`: a one-time link a person fills in their contact on, fetched on the tap, handed on by NFC or QR: see (m) |
| Share → Homes | `POST /napi/merken` `{text}`: what the share sheet sent (title and link); Homes keeps the page as a note and answers `{kurz, notiz, schon, …}` or `{error}` with a German sentence (homes#120); the share sheet's Direct Share target carries the assistant's name (#188) |
| tools | `GET /napi/defs/tool`, `POST /napi/action-callback`; the app offers no tool widget any more: `contacts` and `task` went on 2026-11-02 and 2026-11-03, `doc` (`/napi/portal/documents`) and `note` (`/napi/portal/notes`) on 2026-11-27 („Widget für Notizen brauche ich nicht mehr“, Dokumente „kann ganz weg“). A placed `doc` or `note` widget fetches nothing and says „Dieses Widget gibt es nicht mehr – lange drücken und entfernen.“ (the app cannot take it off the home screen); a placed `contacts` or `task` one keeps its rows (`/napi/portal/{contacts,tasks}`). The tool widget leaves the system's widget picker while no tool is offered and none is placed |
| Rezepte | „Öffnen“'s `rezepte` entry of `/napi/einrichtung` (its icon the app's own, its `stand` the count line Homes sends) opens `<homes>/rezepte` in a Custom Tab. Share → Homes: `POST /napi/merken`'s `{"art":"rezept","titel","notiz"}` says „‚<titel>‘ als Rezept gemerkt“ with „Ansehen“, opening `notiz`; any `notiz` is opened only as an https address on the paired Homes |
| live stream | `POST /napi/portal/watch`, `GET /napi/portal/events` |
| Auslöser (NFC tags, the charger) | `POST /napi/ausloeser`, `POST /napi/ausloeser/tag`, `DELETE /napi/ausloeser/tag/{tag}`, `GET /napi/ausloeser/tags`: see (k) |

Fields the widgets read beyond a value to show:

- `page` on every card (`/napi/portal/state`, `/napi/portal/favorites`) and
  on every tool row with an id: the item's own page on Homes, a path
  (`/raeume/<entity_id>`, `/kalender/erinnerung/<uid>`, `/dokumente/<id>`,
  `/notizen/<file>`). See (d).
- `text` on a card where Homes has its own words for the thing, as its web
  page says them (owner, 2026-11-14: „Bitte in der App und auf der Webseite
  gleich“): today an IPP printer's state sensor, „druckt · Schwarz 42 %“,
  „bereit · Papier leer · Schwarz 40 %“, „nicht erreichbar“
  (mdopp/homes#370). The app shows it instead of its own reading of
  `state`; absent or blank, the app's words stay.
- `achtung` on a card only while the thing needs a look (homes-android#290,
  mdopp/homes#391): a few German words, „nicht erreichbar“, „Batterie fast
  leer“, a printer's „Papier leer“. The start screen's „Jetzt“ says „<Name>:
  <achtung>“ and „Deine Geräte“ puts it first (`ui/Jetzt.kt`); the app reads
  no other word for it and guesses nothing from `state` or `text`.
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
stream keeps current is placed (one device, tools; the Box widgets are
gone, owner 2026-11-03, the energy widget 2026-11-28). There is no switch (owner, 2026-11-08: „es ist jetzt
der einzige Weg“); an older install's stored switch value is ignored. The
start screen keeps one row only while the battery exemption is missing:
„Widgets aktuell halten · Tippen und „Zulassen“ wählen“. It listens for the screen itself. While the screen is on it
holds `GET /napi/portal/events` and reads one frame, `card_state`; on
screen-off it drops the connection. Once connected it posts its watch set
(`POST /napi/portal/watch`: `entity_ids`, the device widgets' devices; re-posted inside Homes' one-hour TTL; a 503 while
Home Assistant is down is retried with backoff, about two minutes) and has
every fed widget read again.

- `gemeinsam` (`{teil}`) syncs that part of the Homes account, see (g).
- `card_state` (`{entity_id, card}`) draws the device widgets of that entity
  without a read. Any other frame reads nothing. The app never asks for `active_set` (no `active` in its
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
late reads nothing and keeps its last card. The exceptions are the backup
job of (e) and, the owner's yes of 2026-11-26 (homes ADR 0004),
„Ladestation melden“'s one request of (k). Messages to people go
through their chat channel, not the app (laib ADR 0022 §3).

## (d) Pages

A widget's body tap opens one of Homes' pages in the browser at the paired
address: `/start` for a tool widget's
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
The kinds (`WidgetKind` in `widget/HomesPages.kt`): `device`, `tool` (`energy` went with its widget, 2026-11-28). The app counts nothing and makes no call for it; a
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

**Every file goes up encrypted for the person's archive** (archiv#64, the
owner: messages live in the person's sealed archive, Homes only relays bytes
it cannot read). The app age-encrypts it (age v1, one X25519 recipient: `GET
/napi/sicherung/schluessel`, as the SMS below) while it reads it, and names it
`<pfad>.age` with the encrypted size (known beforehand: the header, the
nonce, the plaintext and 16 bytes a 64 KiB chunk) — in `/fehlend` too, whose
answer names the `.age` paths. No copy is made on the phone: `Age.ab` gives
the encrypted bytes from any offset, and the encryption's randomness (file
key, ephemeral secret, nonce) is kept with the upload's id until the file is
whole, so a piece after a cut is the same bytes (`sicherung/Uploader.kt`). A
part on the box the phone has no randomness for any more (its data was
cleared mid-upload) is never gone on with: the screen says it waits until
Homes drops it (seven days). Without the archive's key (404 with Homes'
sentence) nothing is sent and the run stops with that sentence. Homes refuses
anything not age: a name without `.age` (400), a first piece whose bytes do
not begin as age (415), with its sentence. The person's archive opens the
files only while it is open, with its own key, then the messenger's backup
with the key or passphrase the person entered there.

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
geaendert}]}}` (epoch ms; each as the phone names it, without `.age`, with
its plaintext size; a WhatsApp medium as `Media/…`) is what the box keeps. Each file comes with `GET
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
- **Everything comes only while the person's archive is open** (archiv#64:
  the box keeps each file as it came, encrypted for the archive; the archive
  decrypts it to the messenger's own file). 423 with Homes' sentence: on a
  medium no further medium is asked for; on Signal's backup or WhatsApp's
  database the run stops; either way the screen offers „Archiv öffnen“
  (`archiv.<domain>` beside `homes.<domain>`). A backup the box kept before
  archiv#64 without `.age` still comes while the archive is closed. 404 is a
  backup gone from the box: the list is loaded again.
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

### Ins Archiv: a Facebook or LinkedIn export

(The owner's call, 2026-11-11.) The share target „Ins Archiv“
(`archiv/ArchivTeilenActivity`, switched by the box's `teilen` key
`archiv`) takes `ACTION_SEND` and `SEND_MULTIPLE` of a ZIP, JSON or CSV
(and `octet-stream`). The app copies each file into its cache and tells it
by its structure (`archiv/Export.kt`): a ZIP by its entries' names —
Facebook's `connections/friends/your_friends.json`,
`friends_and_followers/friends.json` or `friends/friends.json`, LinkedIn's
`Connections.csv` — a single file by its first bytes (`{` with
`"friends_v2"`, or LinkedIn's header). Only that one file leaves the phone:
it is age-encrypted, streamed, to the key of `GET /napi/sicherung/schluessel`
and uploaded with `quelle` `sozial` as `facebook-<UTC>.json.age` or
`linkedin-<UTC>.csv.age` (the resumable upload above; `archiv/InsArchiv.kt`).
The person's archive reads it when it is opened (archiv `sozial.go`);
neither the app nor Homes reads it. Facebook's HTML format and any other
file are refused in the dialog with the next step. The file is small,
so this runs while the share's dialog is open, not in a job.

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
anfordern“ (`anfordern`). To a device whose person has one, it also gives
`dokumente_adresse`, `<login>+doc+@<domain>` (owner 2026-11-10,
mdopp/homes#338): the app icon's entry „Dokumente-Adresse kopieren“
copies `<login>+doc+` (no start-screen tile since 2026-11-28: Homes'
Dokumente page copies it), and the person types the
sender after the `+` (`widget/DokumenteAdresse.kt`). Without it, neither is
there.

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
  a muted entry that cannot be placed; the start screen's tiles and „Dieses Telefon“ show the app's own areas by `mehr` (no Mehr list since 2026-11-27)
  and ends with „Gibt es hier noch nicht“, one trace per off module (not
  the `admins` ones), each with „Hier anfordern“ while `anfordern` is true.
  A placed widget whose type went shows the module's `grund`; a tap opens
  the app.
- **Share targets** (`teilen`: `merken` text, `dokument` images,
  `standort-import` history files, `musik`, `archiv` a social export; and „Daten erfragen“, a contact's vCard,
  which follows the `kontakte` module's `an` instead of a key): one component each — the share
  activity's aliases `.ui.TeilenMerken` and `.ui.TeilenDokument`,
  `.ui.StandortImportActivity`, `.hochladen.MusikTeilenActivity`, `.archiv.ArchivTeilenActivity` and
  `.fragen.KontaktFragenActivity` — enabled while the key is there, disabled
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
  The backup job skips a part the box lacks. The device widget's provider
  is disabled only while its key is missing and none of its widgets is
  placed; the energy widget's (gone, 2026-11-28) is never offered and stays
  only while one is placed (`kann/Anbieter.kt`).
- **„Hier anfordern“:** `POST /napi/kann/anfordern {"modul": …}` → 200, the
  module's entry with its new state, which replaces the kept one. Pressed,
  the button says „Wird angefordert …“; it ends with the new state, Homes'
  sentence (`{"error"}`; 404, 502, 503, 504), „pair again“ on a 401, or the
  app's own words when Homes did not answer in time or cannot be reached.

## (i) Öffnen, Deine Aufgaben, the launcher's shortcuts

(The owner's call, 2026-11-04; Homes' side is mdopp/homes#269,
docs/REFERENCE.md there.) `GET /napi/einrichtung` → `{version: 1, offen,
module: [{name, titel, icon, oeffnen, web, ziel?, stand?, app: {name, paket,
store}, nur_web, neu?, aufgabe: {titel, erledigt, am, wie, einrichten,
dringend?}}], ziele: {einkauf, zeitleiste, scannen}, bereiche?: {<key>:
{stand?, neu?}}}`, the device's person's modules that are on.

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
  not on the phone, else `einrichten` in a Custom Tab. The number is grey,
  orange while any open task carries `dringend: true` (mdopp/homes#391).
- **„Alles auf einen Blick“** (the start screen's tiles, homes-android#290):
  a tile's line is its entry's `stand`, else `bereiche[<key>].stand` (keys:
  a module's `name`, `ausloeser`, `archiv`); `neu` (on the entry or in
  `bereiche`) shows the green dot and „neu: <neu>“ once, until the tile is
  opened; a changed `neu` shows it again. Absent, the tile is as before.
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

## (k) Auslöser: NFC tags and the charger

(The owner's call, 2026-11-26: „Ich will die Home Assistant App nicht“, and
„wir brauchen ein Konzept wie ich NFC Tags benennen kann“; homes ADR 0004
§1, §2, §4, §5 and §8. Homes' side is `internal/napi/ausloeser.go`,
`ausloeser_automationen.go` and `internal/tagaktion`.)
The phone reports what it noticed; Homes fires Home Assistant's event and
says what it did; HA's automations decide. The app keeps no rule and
filters nothing. The row Mehr → Auslöser is there while `/napi/kann`'s
`mehr` has `ausloeser` (the `geraete` module); without it, its trace.

- **A touched tag.** `ausloeser/TagActivity` takes `NDEF_DISCOVERED` for
  the box's own address `https://<homes>/tag/<id>`, what Homes writes since
  ADR 0004 §5, `cloud.dopp.homes://tag/<id>`, what it wrote before, and
  `https://www.home-assistant.io/tag/<id>`, what HA's own app wrote — the
  id is what follows `/tag/`, so tags already in the house keep working.
  The box's address is taken only for the paired server's host.
  It says „Meldet …“ at once, sends `POST /napi/ausloeser
  {"art":"nfc","tag":"<id>"}` once, shows one line — „Bürotür – gemeldet“
  for `{"ok":true,"name":"Bürotür"}`, „Unbekannter Tag – auf Homes oder
  hier benennen“ for `"name":""`, else the reason — and closes after 2.5 s
  (translucent, its own empty task, not in the recents). A refusal is
  `{"ok":false,"error":"<German sentence>"}`; a 404 `{"reason":"not_found"}`
  means the box has no Home Assistant; a 401 says to pair again; no answer
  in 12 s, or no Homes, is said in the app's words. Unpaired, nothing goes.
- **The screen, short** (owner 2026-11-27: „Neu nimmt zu viel Platz
  ein“). „Eure Tags“ comes first; the form for a new tag is closed behind
  the list's last row „+ Neuen Tag beschreiben“, which opens it in place;
  „Abbrechen“ closes it, and so does a tag written, named or removed (the
  line under the list says what came of it, and is gone while it says
  nothing). Tapping a tag opens the same form for it. A blank tag held to
  the phone while the screen is open opens the form by itself, „Neuer Tag
  erkannt – wie soll er heißen?“, the name field focused. A written tag
  held to it shows what is on it, before anything is offered (owner
  2026-11-27, `ausloeser/TagInhalt.kt`, `TagInhaltTest`), in a card right
  under the list (scrolled into view): a Homes tag (Homes' address, the scheme form or HA's) → its
  name, action and automations from the list `GET /napi/ausloeser/tags`
  already read, „Bearbeiten“ (the list's form for it) and „Auslösen“ (`POST
  /napi/ausloeser` as a touch, the answer on the card); a WLAN tag → its
  network and protection, the password hidden behind „zeigen“, „Neu
  beschreiben“ (the WLAN form filled in) and „Ins Telefon übernehmen“
  (Android 11+'s own „WLAN speichern?“, `ACTION_WIFI_ADD_NETWORKS`; Homes
  keeps nothing); anything else → each record's kind and content in plain
  words (address, text, vCard, MIME, an app's) and „Überschreiben“. **A tag
  that holds something is never written over without one question** („Der
  Inhalt geht verloren.“): only after the yes may the next write go onto
  that tag (its id, once; forgotten when the screen goes), also while a new
  tag or a WLAN tag waits. Nothing read leaves the phone. After „Ins Telefon
  übernehmen“ the card's button becomes „WLAN anbieten“.
- **Blank, or a card with data of its own** (owner 2026-11-28, a field
  case: an access card was taken for a blank sticker; `ausloeser/TagBefund.kt`,
  `TagLeser.kt`, `TagKarteTest`). Every tag is read before anything is
  decided, here and for a blank tag touched anywhere (`LeerTagActivity`):
  an NDEF tag's message really read (Android's cached one is null on some
  phones); blank only with no message or empty records. Any other card is
  one with data of its own — „Karte mit eigenen Daten (<Typ>) – vermutlich
  eine Zugangs-, Bank- oder Fahrkarte. Homes beschreibt sie nicht.“, no
  button that writes or formats, ever — unless its user memory read empty
  (a factory-new NTAG/Ultralight, a MIFARE Classic all open with a public key
  and all zero). When its memory cannot be read, „Ist ein leerer Aufkleber
  …“ asks first („Falls das eine Zugangskarte ist, gehen ihre Daten
  verloren. Ist es wirklich ein leerer Aufkleber?“, „Ja, leerer Aufkleber“ /
  „Abbrechen“). A failed read says „Tag nicht lesbar – halte ihn ruhig noch
  einmal ans Telefon.“ The card's data is shown on the screen only („zeige
  mir die Daten“), monospace, with „Kopieren“ (the hex, marked sensitive):
  type, UID, size, Android's tech list; an NTAG's or Ultralight's pages; a
  MIFARE Classic's sectors opened with the four public default keys only
  (FFFFFFFFFFFF, A0A1A2A3A4A5, D3F7D3F7D3F7, 000000000000), the others
  „gesperrt“; an ISO-DEP card's applications by name from the payment
  directory or SELECT of known AIDs (girocard, Maestro, Mastercard, Visa, the
  eID …) — never a record, so „Bankkarte: …. Die Kartennummer liest Homes
  nicht.“; an NFC-V card's blocks; FeliCa by type. Never stored, sent or
  logged. LeerTagActivity opens the form only for a blank tag; for a card
  with data, or one unclear, „Das ist keine leere NFC-Karte.“ The charger's
  card is as before.
- **„WLAN anbieten“** (owner 2026-11-27; no `/napi`, nothing leaves the
  phone but over NFC to the phone held to it): from the WLAN form's
  „Anbieten statt schreiben“ or a read WLAN tag's „WLAN anbieten“, the
  screen „Halte das andere Telefon an meins“ makes the phone answer as a
  read-only NFC Forum Type 4 Tag (`ausloeser/Typ4Tag.kt`, AID
  `D2760000850101`, CC file E103 with write access FF, NDEF file E104)
  carrying exactly the WLAN tag's record. The bound HCE service
  (`WlanAnbietenDienst`, `BIND_NFC_SERVICE`, `requireDeviceUnlock`) answers
  only while that screen is in front, with the screen on, for at most two
  minutes from its first start (going away and back does not extend it);
  then it stops by itself and says so. At any other time every command is
  6A82. The credential lives in memory only (`WlanAngebot`), never stored,
  and is forgotten when the offer ends or the screen closes.
- **Teilen per Berührung** (owner approved 2026-11-28; nothing leaves the
  phone but over NFC to the phone held to it, or on its screen as a QR code;
  the one call is (m)'s). The same service listens whenever the phone is
  unlocked; with nothing armed it answers 6A82 and, on a reader's SELECT of
  the NDEF application, posts „Ein Telefon möchte etwas von dir. Was
  teilen?“ (heads-up, at most every 30 s, no activity started from the
  background), whose tap opens „Teilen“. Its rows (owner 2026-11-28: „eine
  Zeile pro Thema und dann rechts einen Button für NFC und einen für das
  Anzeigen eines QR-Codes“): „Meine Kontaktdaten“ (NDEF MIME `text/vcard`,
  vCard 3.0 of the ticked fields of the person's own contact, „Dieses
  Telefon“ → „Meine Visitenkarte“), „Gast-WLAN“ (the WLAN tag's record,
  `application/vnd.wfa.wsc`; set in its form — SSID, Kennwort with „zeigen“,
  Mit Passwort / Offen — or by „Als Gast-WLAN merken“ on a read WLAN tag's
  card after „Ins Telefon übernehmen“), „Kontaktdaten eintragen lassen“ (an
  NDEF URI record of (m)'s link), and each kept tag by its name (its NDEF
  message as read). [NFC] answers exactly one reader within two minutes
  (the NDEF file read to its end), and only while „Teilen“ is in front; the
  row says „Halte das andere Telefon an deins … noch 1:43“, then „Geteilt.“
  or „Abgelaufen.“ [QR] shows the code full-screen at full brightness:
  vCard 3.0, `WIFI:`, the link; a kept tag without such a form has it off
  with „Nur per NFC, ohne QR-Code“. Under a WLAN or vCard row „iPhone:
  QR-Code“ (an iPhone takes neither over NFC; a link it does). A row not set
  up shows „Festlegen“ instead (the guest Wi-Fi's form, once saved, arms it
  at once). „Im Telefon behalten“ on the Tag-Inhalt card keeps any NDEF tag
  under a name; a card with data of its own says „Diese Karte kann ein
  Telefon nicht nachspielen.“ Kept tags („Behaltene Tags“, deletable), the
  card and the guest Wi-Fi („Gast-WLAN“, changeable and deletable) are
  encrypted on the phone (Android Keystore AES-GCM).
- **A new tag.** On the Auslöser screen: a name (the list's `vorschlaege`,
  the house's rooms from HA and a few places, as one-tap words under the
  field) → `POST /napi/ausloeser/tag {"name":"Bürotür"}` →
  `{"ok":true,"tag":"homes-<12 hex>","uri":"cloud.dopp.homes://tag/homes-<12 hex>"}`
  (the tag in HA's registry) → „Halte den Tag ans Telefon …“ → one NDEF
  URI record with `uri`, written, or formatted onto a blank tag →
  „„Bürotür“ ist fertig. Kleb ihn hin und sag in der Werkstatt, was er tun
  soll.“ with the hint to write the name on the sticker (the tag does not
  carry it), or why not (read only, too small, gone too early, no NDEF);
  the retry writes the same `uri` and makes no second tag in HA. No tag
  within a minute is said. While the screen is in front the phone reads in
  NFC reader mode: a tag held to it opens nothing and is not reported; a
  blank one is offered for „Neuen Tag beschreiben“, a written one shows
  what is on it (above).
- **Naming** (ADR 0004 §2). The list is `GET /napi/ausloeser/tags` →
  `{"ok":true,"tags":[{"tag","name"}],"vorschlaege":[…]}`, names only (the
  id is never shown). A tag tapped there is renamed — `POST
  /napi/ausloeser/tag {"name","tag"}`, „Sichern“; the tag is not written
  again, so its automations keep working — or removed: „Entfernen“, one
  question („Automationen, die ‚X‘ nutzen, laufen dann nicht mehr.“), then
  `DELETE /napi/ausloeser/tag/{tag}` → `{"ok":true}` (404 with a sentence
  when HA has no such tag). An unknown tag touched shows „Benennen“ beside
  its line, which opens the screen naming that id the same way. A name
  taken (409, case aside) or empty or over 60 characters (400) is said in
  Homes' sentence. Names live in HA (laib ADR 0017); the app stores none.
- **What a tag does** (ADR 0004 §4, owner 2026-11-26 „Go. Passt alles“).
  The list also brings each tag's `"aktion"` (`""` reports to HA,
  `"scannen-ich"`, `"scannen-haushalt"`) and the box's `"aktionen":[{"id",
  "titel"}]`, in order, HA's (`""`, „Home Assistant melden“) first, the scan
  ones only where the box has a scanner. The name form asks „Was soll der
  Tag tun?“ with one choice per entry — exactly what `aktionen` lists, the
  tag's own preselected, `""` for a new one — and sends it as `"aktion"`
  with „Sichern“ and „Tag beschreiben“ (`POST /napi/ausloeser/tag`; the
  answer says the tag's `aktion`; 400 „Das kann ein Tag auf dieser Box
  nicht tun.“ for one the box lacks). An older Homes that lists no
  `aktionen` gets no choice and no `aktion`, so its tags keep theirs. A tag
  whose action is not `""` shows that action's title under its name
  („Scannen – für mich“). A touched scan tag is answered `{"ok":true,
  "name","aktion","text":"Der Scanner startet – nur für dich."}` (or „– für
  den Haushalt.“): the tag's line is that `text` instead of „… – gemeldet“,
  without „Benennen“. Its refusals are sentences, shown as they come: 403
  (a phone without its own login, for „für mich“), 503 (no scanner), 502
  (the scanner silent).
- **The tag's own address** (ADR 0004 §5). `POST /napi/ausloeser/tag`'s
  `uri` is `<HOMES_PUBLIC_URL>/tag/<id>`, an Android App Link: the manifest
  names its host for `android:autoVerify`, and Homes serves
  `/.well-known/assetlinks.json` with the app's package and its signing
  certificate's SHA-256 (`HOMES_APP_SHA256`; the release key's is
  `C3:6A:41:03:80:AB:C9:9D:D5:6E:DF:14:5F:34:FF:A6:DA:7F:4A:15:B0:98:A3:7F:FD:AB:D7:02:FD:68:A6:2D`).
  Android verifies a link only for a host the manifest names, so the host
  is the build's: the manifest placeholder `homesHost`, `www.dopp.cloud` by
  default (the owner's box), `-PhomesHost=<host>` or `HOMES_APP_HOST` for
  another (`app/build.gradle`). The path is `/tag/homes-.*`, so Homes'
  other pages under `/tag/` (a wish's decision, `/tag/freigabe/<id>`) stay
  the browser's. A tag touched is reported at once; the address opened as
  a link (`VIEW`, from a chat or a page) shows „Ein Link zu einem
  Homes-Tag.“ and reports only on „Auslösen“ — a link never acts by
  itself, as Homes' page `/tag/<id>` acts only on its button. Android 12+
  hands a touched tag's web address to the app as `VIEW`, not
  `NDEF_DISCOVERED` (seen on the owner's Samsung, 2026-11-27, once its App
  Link was allowed): a `VIEW` whose referrer is Android's NFC service
  (`android-app://com.android.nfc`), or that carries NfcAdapter's
  `EXTRA_TAG`, `EXTRA_NDEF_MESSAGES` or `EXTRA_ID`, is a touch and is
  reported at once; any other `VIEW` (a chat, a browser, no referrer)
  still asks. The log says which way (`HomesTag`: `nfc`, `nfc-view`,
  `link`), nothing of the tag or the person.
- **A blank tag anywhere** (the owner, 2026-11-27: „kann sich Homes für
  neue Tags registrieren?“). `ausloeser/LeerTagActivity` takes
  `TECH_DISCOVERED` for a tag that can be formatted (`NdefFormatable`) or
  is `Ndef` (`res/xml/nfc_leer.xml`); Android offers it only after
  `NDEF_DISCOVERED` found no taker, so Homes' and HA's tags still go to
  `TagActivity`. Blank — formattable, or Ndef with no message or only
  empty records — it opens the Auslöser screen's form, „Neuer Tag erkannt
  – wie soll er heißen?“, the name focused, with Android's `Tag` handed
  along: „Tag beschreiben“ makes the tag in HA and writes it at once while
  Android still holds it („Beschreibt „X“ …“); gone meanwhile, the screen
  waits for it as for any new tag („Halte „X“ noch einmal ans Telefon …“).
  A tag with records is not Homes': nothing shows. Unpaired, it says so.
  No `TAG_DISCOVERED`. A box built
  for another host: its tags open Homes' page in the browser, which works
  without the app.
- **The scanner's question** (§8). The actions are `scanner-fragen`,
  `scanner-ich`, `scanner-haushalt` with Homes' titles (the app shows
  `aktionen` as it lists them). A touched `scanner-fragen` tag answers
  `{"ok":true,"name","aktion","text","oeffnen":"<HOMES_PUBLIC_URL>/dokumente#scannen"}`:
  the app says `text` and opens `oeffnen` in a Custom Tab (the browser
  without one; no web view in the app) — only an https address on the
  paired Homes; any other is not opened and the line says `text`.
- **A tag's automations** (§8). The list brings each tag's `automationen`
  `[{"id","name"}]`: the row says „startet: Gute Nacht“. Tapping one of
  HA's tags (action `""`) shows them in a card, each with „Lösen“, and
  „Mit einer Automation verbinden“ (`GET /napi/automationen?q=` →
  `{"ok":true,"automationen":[{"id","name","an"}]}`, searched as one types,
  the linked ones left out, an off one „(aus)“) and „Neue Automation“ (one
  sentence: „Was soll passieren, wenn jemand ‚Bürotür‘ berührt?“). Each is
  two steps: `POST /napi/ausloeser/tag/{tag}/verbinden|loesen
  {"automation"}` or `…/neu {"satz"}` without `bestaetigt` →
  `{"ok":true,"satz","bestaetigen":true,"freigabe_noetig"?}`, the sentence
  shown in the app's one dialog (`widget/ActionDialog`; with
  `freigabe_noetig` it says an admin decides, the yes is „Um Freigabe
  bitten“) → the same with `"bestaetigt":true` → `{"ok":true,"satz"}`
  (done, an admin's), with `"bote"` for an admin's new one (the Bote's
  reply, with the code it asks for, shown under the sentence), or
  `"wartet":true,"freigabe":"<id>"` (anyone else's). Pressed, the line
  says „Fragt Homes …“, then „Speichert …“, the buttons wait; a refusal
  (`{"ok":false,"error"}`: 404, 409, 502, 503, 504) is said as it comes.
  The read waits up to 65 s (the Bote drafts; Homes answers 504 when it is
  too late).
- **What a tag did** (ADR 0004 §9; mdopp/homes#387, from HA's automation
  traces). `POST /napi/ausloeser` `{art: nfc}` for a tag with automations
  waits for them and answers `ergebnisse:[{automation,name,lief,grund}]`
  and a ready `text`; the touched tag's line is that `text` (it may take a
  few seconds: „Meldet …“ stays until then; the read waits 12 s). A tag
  without automations gets no `text`, and the line stays „<Name> –
  gemeldet“. The app reads `text` only, not `ergebnisse`. `GET
  /napi/ausloeser/tags` gives each tag `zuletzt:{wann,person?,text}` when
  it ran before — `wann` RFC 3339 in UTC, `person` the login of who touched
  it (absent when Homes does not know): the row says „zuletzt heute 18:12,
  anna – Bürolicht aus“ („gestern …“, else „26.11., …“, in the phone's time
  zone); without `person` „zuletzt heute 18:12 – …“; with a `wann` the app
  cannot read, `text` alone.
- **Waiting for an admin.** A wish's id is kept on the phone with its tag
  (the row says „Wartet auf Freigabe“) and asked after with `GET
  /napi/ausloeser/freigabe/{id}` → `{"ok":true,"stand","satz","wunsch"}`
  every 15 s while the Auslöser screen is open — never in the background,
  no job, no alarm. `wartet` keeps it; `freigegeben`, `abgelehnt`,
  `verfallen` end it and the card says `satz`; a 404 (Homes no longer has
  it) ends it with Homes' sentence. Homes tells the person on their chat
  channel too.
- **The charger.** „Ladestation melden“, off by default. While it is on and
  the live service of (c) runs, the service holds a receiver for
  `ACTION_POWER_CONNECTED`/`DISCONNECTED` (`ausloeser/Ladestation.kt`;
  Android has no manifest receiver for them) and sends `POST /napi/ausloeser
  {"art":"laden","zustand":"an"|"aus","ladeart":"kabellos"|"kabel"|"usb"|""}`,
  `ladeart` from `BatteryManager.EXTRA_PLUGGED` (empty when it stops, or
  for a dock). Android does not say *which* charger: „auf dem Pixel Stand“
  is „lädt kabellos“. The request goes with the screen off too — the
  second exception to (c), the owner's yes of 2026-11-26 (ADR
  0004). No job, alarm, worker or wakelock: the service's foreground process
  carries it. Without a placed live widget the service does not run, and
  the screen says it reports only while one is placed. The last report's
  line („Lädt kabellos – gemeldet“, or why not) shows under the switch.

## (l) Daten erfragen: a contact asked for what is missing

(The owner's request, 2026-11-28; Homes' side is mdopp/homes#396,
`internal/napi/kontakte_fragen.go`, admins only.) The share target „Homes: Daten erfragen“
(`fragen/KontaktFragenActivity`, enabled while `/napi/kann`'s `kontakte`
module is on, and while that is unknown; Homes names no `teilen` key for it) takes `ACTION_SEND` of
`text/x-vcard` and `text/vcard` — Samsung Contacts' „Senden“ → „vCard-Datei
(VCF)“, Google Contacts — and of `text/plain` only when the text is a vCard
(else „Das ist keine Visitenkarte …“, and no request).

- **The card** (`fragen/GeteilteKarte.kt`): the first card only; folds undone,
  vCard 2.1's QUOTED-PRINTABLE (soft line breaks, `CHARSET`, the bare
  parameter) and 3.0/4.0 read alike; the name is `FN`, else `N`'s parts; every
  `TEL` and `EMAIL` once, in the card's order. Nothing else of it leaves the
  phone, and nothing is stored.
- **The call:** „Sucht <Name> im Haushalts-Adressbuch …“, then `POST
  /napi/kontakte/fragen {"name","telefon":[…],"mail":[…]}`:
  - 200 `{"ok":true,"kontakt","vorname","link","text","fehlt":[…],"telefon"?,"neu"?}`
    → „Bei <vorname> fehlen: Adresse, Geburtstag“ (`fehlt` in Homes' words, as
    sent), or „Alles da – trotzdem fragen?“ when `fehlt` is empty; with `neu`
    „<kontakt> ist jetzt im Haushalts-Adressbuch.“ first. Then „Per WhatsApp an
    <vorname>“ (only with `telefon` and WhatsApp installed: `https://wa.me/<digits>?text=<text>`
    in `com.whatsapp`), „Per SMS“ (`smsto:` to `telefon`, else the card's first
    number, `sms_body` the `text`) and „Anders teilen …“ (`ACTION_SEND` of the
    `text` through the chooser). Each ends the share. Without `text` the app
    sends the `link`.
  - 404 `{"ok":false,"error","anlegen":true}` → Homes' sentence and
    „Ins Haushalts-Adressbuch aufnehmen und fragen“, which sends the same body
    with `"anlegen":true`. A 404 without `anlegen` is a refusal.
  - 409 `{"kandidaten":[{"kontakt","uid"}]}` → one row per candidate (Homes'
    `error` as the question, else „Mehrere Kontakte passen. Wer ist gemeint?“);
    the pick sends the body again with its `"uid"`.
  - `telefon` is `""` without a mobile number: no WhatsApp row then.
  - 401: pair again; 403 (not an admin), 400, 502, 503 and any other: Homes' `error`, else „Homes hat nicht
    lesbar geantwortet (<code>) …“; no answer in 20 s or no line: the app's
    own sentence.

## (m) Kontaktdaten eintragen lassen: a one-time link over NFC or QR

(The owner's request, 2026-11-28: „eine URL, in der die Person ihre
Kontaktdaten eintragen kann“. Homes' side is being built in mdopp/homes
branch `feat/kontakt-einladen`; the row in `NapiContractTest` is the agreed
contract until its handler exists, then its file and lines.) „Teilen“'s row
„Kontaktdaten eintragen lassen“ is there while the phone is paired and
`/napi/kann`'s `kontakte` module is on (or that is unknown); who may use it
Homes decides.

- **The call**, on [NFC] or [QR] only — each link is good once, so nothing is
  fetched ahead: the row says „Homes macht einen Link …“ with its buttons
  off, then `POST /napi/kontakte/einladen` with `{}`:
  - 200 `{"ok":true,"link","text","gueltig_bis"}` → [NFC]: an NDEF URI record
    of `link` answers the next reader, as every row does (two minutes, once);
    [QR]: `link` as a QR code. A `link` that is not https is no link.
  - 403 (not an admin), 503, 502 and any other: `{"ok":false,"error"}`, Homes'
    sentence in the row, else „Homes hat nicht lesbar geantwortet (<code>)
    …“; 401: pair again; no answer in 15 s or no line: the app's own sentence.
    Nothing is armed then; the buttons work again.


package cloud.dopp.homes.ui

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import cloud.dopp.homes.HomesConfig
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.data.Napi.KannKey
import cloud.dopp.homes.data.Assistent
import cloud.dopp.homes.data.BoxApiClient
import cloud.dopp.homes.data.Card
import cloud.dopp.homes.data.PairLinks
import cloud.dopp.homes.data.Pairing
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.einrichtung.Abschnitte
import cloud.dopp.homes.einrichtung.EinrichtungStore
import cloud.dopp.homes.kann.Anbieter
import cloud.dopp.homes.kann.Anfordern
import cloud.dopp.homes.kann.KannLeser
import cloud.dopp.homes.kann.KannStore
import cloud.dopp.homes.kann.Liste
import cloud.dopp.homes.kann.Teilen
import cloud.dopp.homes.konto.HomesKonto
import cloud.dopp.homes.data.ToolDef
import cloud.dopp.homes.realtime.Live
import cloud.dopp.homes.widget.ActionDialog
import cloud.dopp.homes.widget.ActionItem
import cloud.dopp.homes.widget.ActionSheet
import cloud.dopp.homes.widget.ActionTone
import cloud.dopp.homes.widget.AppShortcuts
import cloud.dopp.homes.widget.DeviceLook
import cloud.dopp.homes.widget.DeviceTaps
import cloud.dopp.homes.widget.HomesPages
import cloud.dopp.homes.widget.ToolWidgetProvider
import cloud.dopp.homes.widget.WidgetActionReceiver
import cloud.dopp.homes.widget.WidgetPin
import cloud.dopp.homes.widget.WidgetStore
import cloud.dopp.homes.widget.WidgetTypes
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlin.concurrent.thread

/**
 * The app's one screen (#176, laib ADR 0022 §1): pairing, then the widgets.
 *
 * **Pairing.** The person enters Homes' address; the app opens
 * `<homes>/pair-device` in a Custom Tab, where their Authelia session is; they
 * confirm; Homes redirects to `cloud.dopp.homes://pair#token=homes_device_…`, which
 * lands back here. Homes' device page also shows a QR code
 * (`cloud.dopp.homes://setup?server=…&token=…`), scanned in-app — it has no
 * intent filter, so no web page can point the app at a foreign server.
 *
 * Nothing is stored behind the person's back ([Pairing]): a pair link counts
 * only while a pairing started here is pending, a link or a scanned code asks
 * first in a dialog naming the host, the token is checked with Homes'
 * `/napi/whoami` before it is stored, an http address is refused with a
 * message, and a token that cannot be saved is said so.
 *
 * **Paired** (owner 2026-11-27: „nothing hidden; every area speaks only as
 * loud as it matters now“). The head — the app's icon, the assistant's name,
 * the claim. „Jetzt“: at most two hints while something wants attention
 * ([Jetzt]), gone without one. „Deine Geräte“: the favourites as tiles
 * ([FavoritenAnsicht]), what wants attention first, then what is on, „Alle →“
 * in the head. „Deine Aufgaben“ in one row. „Alles auf einen Blick“: every
 * area as a tile ([Ueberblick]); what the box lacks, quiet, below it.
 * „Dieses Telefon · <name>“ ([TelefonActivity]): backups, the Homes account,
 * what the phone reports and the pairing, in one place. Last „Widget
 * hinzufügen“, the list it always was, with the row that asks for the
 * battery exemption while it is missing.
 */
class HomeActivity : AppCompatActivity() {

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { handleScanned(it) }
    }

    /** Android 13+: the live widgets' notification shows only with this permission. */
    private val notifyLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @Volatile
    private var toolsLoading = false

    @Volatile
    private var devicesLoading = false

    /** The favorites drawn last; null until the first read or the cache. */
    private var devices: List<Card>? = null

    /** The last read failed and nothing was ever read: say so instead of an empty card. */
    private var devicesFailed = false

    /** A device tap finished (WidgetActionReceiver, WidgetActionActivity): read the favorites again. */
    private val tapDone = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshDevices()
        }
    }

    /** What the box can do changed (homes ADR 0001): the widget list and Mehr draw again. */
    private val kannNeu = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (paired()) renderWidgetTypes()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        findViewById<Button>(R.id.connect_btn).setOnClickListener { onConnect() }
        findViewById<Button>(R.id.qr_scan_btn).setOnClickListener { startScan() }
        findViewById<View>(R.id.telefon_zeile).setOnClickListener { startActivity(Intent(this, TelefonActivity::class.java)) }
        // The live row (owner 2026-11-03; no switch since 2026-11-08): there while the exemption is missing, a tap asks for it.
        val onLiveRow = View.OnClickListener { requestExemption() }
        findViewById<View>(R.id.live_row).setOnClickListener(onLiveRow)
        findViewById<TextView>(R.id.live_battery).setOnClickListener(onLiveRow)
        cloud.dopp.homes.verbindung.Verbindung.zeigen(this, findViewById(R.id.verbindung)) { unpair() }
        Abschnitte.zeigen(this) // Öffnen and Deine Aufgaben, from /napi/einrichtung (owner, 2026-11-04)
        cloud.dopp.homes.hochladen.Stand.zeigen(this, findViewById(R.id.hochladen_zeile)) // uploads, as their notification says
        padForSystemBars(findViewById(R.id.home_root))
        handleDeepLink(intent)
        entkoppeln(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
        entkoppeln(intent)
        render()
    }

    /** „Kopplung aufheben“ on „Dieses Telefon“ (owner 2026-11-27): the unpairing stays here, as it was. */
    private fun entkoppeln(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_ENTKOPPELN, false) != true) return
        intent.removeExtra(EXTRA_ENTKOPPELN)
        unpair()
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(this, tapDone, IntentFilter(DeviceTaps.ACTION_DONE), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, kannNeu, IntentFilter(KannLeser.ACTION_GEAENDERT), ContextCompat.RECEIVER_NOT_EXPORTED)
        render()
        if (paired()) refreshDevices()
        KannLeser.lesen(this) // what the box can do, at most once a minute
        claim()
        Anbieter.werkzeug(applicationContext) // the tool widget leaves the picker while no tool is offered (owner 2026-11-27)
        cloud.dopp.homes.hochladen.Hochladen.anstossen(applicationContext) // files wait: Android 15 gives the job new hours now
        // The app-icon long-press menu (#97): picks up a widget removed while away.
        if (paired()) AppShortcuts.refreshAsync(applicationContext)
        // The app's screen may start the live service (Android 12+): placed widgets, the switch, the pairing.
        Live.ensure(applicationContext)
    }

    override fun onPause() {
        runCatching { unregisterReceiver(tapDone) }
        runCatching { unregisterReceiver(kannNeu) }
        super.onPause()
    }

    private fun paired() = ServerStore.isConfigured(this) && TokenStore.isPaired(this)

    /** targetSdk 35 is edge-to-edge: keep the content below the status bar and above the navigation bar. */

    /** The claim under the logo (owner 2026-11-27): „Deinem Zuhause geht es gut.“ only while Homes says so, else neutral. */
    private fun claim() {
        val app = applicationContext
        thread { val gut = BoxApiClient(app).gut(); runOnUiThread { if (!isFinishing) findViewById<TextView>(R.id.home_claim).setText(if (gut) R.string.home_claim_gut else R.string.home_claim) } }
    }

    /** „Version 4.1.21 · gekoppelt mit www.dopp.cloud“, or „… · nicht gekoppelt“: the start screen's last line. */
    private fun renderVersion(connected: Boolean) {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        findViewById<TextView>(R.id.home_version).text = if (connected) {
            getString(R.string.home_version_gekoppelt, version, ServerStore.host(this) ?: "")
        } else {
            getString(R.string.home_version_ungekoppelt, version)
        }
    }

    private fun render() {
        val connected = paired()
        findViewById<View>(R.id.connect_section).visibility = if (connected) View.GONE else View.VISIBLE
        findViewById<View>(R.id.connected_section).visibility = if (connected) View.VISIBLE else View.GONE
        val url = findViewById<EditText>(R.id.server_url)
        if (url.text.isNullOrBlank()) ServerStore.baseUrl(this)?.let { url.setText(it) }
        findViewById<TextView>(R.id.connect_hint).text =
            if (ServerStore.isConfigured(this) && !TokenStore.isPaired(this)) {
                getString(R.string.home_reconnect, ServerStore.host(this) ?: "")
            } else {
                getString(R.string.home_connect_hint)
            }
        renderAssistent()
        renderVersion(connected)
        if (!connected) return
        findViewById<View>(R.id.live_row).visibility = if (Live.exempt(this)) View.GONE else View.VISIBLE
        renderDevices()
        renderTelefon()
        renderWidgetTypes()
        renderTools()
        refreshAssistent()
    }

    // --- the assistant's name -----------------------------------------------

    private fun renderAssistent() {
        findViewById<TextView>(R.id.assistent_name).text = Assistent.name(this)
    }

    /** Ask Homes for the assistant's name; redraw when it changed. */
    private fun refreshAssistent() {
        thread {
            val fresh = runCatching { BoxApiClient(applicationContext).getAssistent() }.getOrNull() ?: return@thread
            if (!Assistent.save(applicationContext, fresh)) return@thread
            // the share sheet's target carries the name (#188)
            runCatching { AppShortcuts.refresh(applicationContext) }
            runOnUiThread { if (!isFinishing) renderAssistent() }
        }
    }

    // --- the devices (owner, 2026-11-02) --------------------------------------

    /** The favorites as tiles ([FavoritenAnsicht], owner 2026-11-04); „Alle →“ in the head. The list read last draws at once. */
    private fun renderDevices() {
        val shown = devices ?: StartDevices.load(this).also { devices = it }
        val group = findViewById<ViewGroup>(R.id.devices_group)
        group.removeAllViews()
        findViewById<View>(R.id.devices_all).setOnClickListener { openHomes(HomesPages.RAEUME) }
        val ansicht = FavoritenAnsicht(
            this,
            stale = { cloud.dopp.homes.verbindung.Verbindung.stand(this) != null },
            tap = ::tapDevice,
            longPress = { openHomes(HomesPages.item(it.page)) },
            openLevel = { card, level, extra, switching -> openLevel(card, level, switching, extra) },
            send = { card, b, switching ->
                switching()
                sendBroadcast(WidgetActionReceiver.call(this, card.entityId, card.name, b.service, b.data))
            },
        )
        when {
            shown == null && devicesFailed -> group.addView(note(getString(R.string.home_devices_unreadable)))
            shown == null -> group.addView(note(getString(R.string.home_devices_loading)))
            shown.isEmpty() -> group.addView(note(getString(R.string.home_devices_none)))
            else -> ansicht.kacheln(group, Jetzt.ordnen(shown)) // what wants attention first, then what is on (owner 2026-11-27)
        }
        renderJetzt()
        // an open level sheet stays open across a fresh read and shows its level
        levelSheet?.let { sheet -> shown?.firstOrNull { it.entityId == sheet.entityId }?.let { sheet.refresh(DeviceControls.of(it).slider) } }
    }

    /** A favorite's tap: a light, a switch or a cover switches, a lock opens its chooser, anything else its page. */
    private fun tapDevice(card: Card, switching: () -> Unit) {
        if (StartDevices.acts(card)) {
            // the tap answers at once; the fresh state comes with DeviceTaps.ACTION_DONE
            if (card.domain != "lock") switching()
            sendBroadcast(WidgetActionReceiver.tap(this, card.entityId, card.name, card.supportedFeatures))
        } else {
            openHomes(HomesPages.item(card.page))
        }
    }

    /** Read the favorites from Homes, keep them, draw them; a failed read keeps what is shown. */
    private fun refreshDevices() {
        if (devicesLoading) return
        devicesLoading = true
        val app = applicationContext
        thread {
            val fresh = runCatching { ApiClient(app).favoritesBody() }.getOrNull()
            val parsed = fresh?.let { ApiClient.parseFavorites(it) }
            if (fresh != null && parsed != null) StartDevices.save(app, fresh)
            runOnUiThread {
                devicesLoading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (parsed != null) {
                    devices = parsed
                    devicesFailed = false
                } else {
                    devicesFailed = true
                }
                if (paired()) renderDevices()
            }
        }
    }

    /** The level sheet open now, so a fresh read updates it instead of closing it. */
    private var levelSheet: LevelSheet? = null

    /** The favorite's level in its sheet (owner, 2026-11-03): each pick is one call through the device receiver. */
    private fun openLevel(card: Card, level: DeviceControls.Slider, switching: () -> Unit, extra: List<DeviceControls.Button>) {
        levelSheet?.dialog?.dismiss()
        val sheet = LevelSheet(this, card.entityId, DeviceLook.shortName(card.name), level, { service, data ->
            switching()
            sendBroadcast(WidgetActionReceiver.call(this, card.entityId, card.name, service, data))
        }, extra)
        sheet.dialog.setOnDismissListener { if (levelSheet === sheet) levelSheet = null }
        levelSheet = sheet
        sheet.show()
    }

    /** A sentence where the tiles would be: loading, none yet, not readable. */
    private fun note(text: String): View =
        TextView(this, null, 0, R.style.Text_Homes_Body).apply {
            this.text = text
            setBackgroundResource(R.drawable.card_homes)
            val pad = resources.getDimensionPixelSize(R.dimen.card_padding)
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    /** [page] of the paired Homes in a Custom Tab. */
    private fun openHomes(page: String) {
        val base = ServerStore.baseUrl(this) ?: return
        open(Uri.parse(HomesPages.url(base, page)))
    }

    // --- the widgets ----------------------------------------------------------

    /** One list, no groups (owner, 2026-11-03): one device, the energy, then the tools. */
    private fun renderWidgetTypes() {
        showTools(WidgetStore.keptToolCatalog(this).filter { it.isOffered })
        renderBereiche()
    }

    /**
     * „Alles auf einen Blick“ (owner 2026-11-27, [Ueberblick]) and below it, quiet, what the box
     * lacks, each with „Hier anfordern“ (homes ADR 0001 §10). Daten sichern and Homes auf dem Handy
     * are on „Dieses Telefon“.
     */
    private fun renderBereiche() {
        Ueberblick.zeichnen(this, findViewById(R.id.ueberblick_section), findViewById(R.id.ueberblick_group))
        if (Ueberblick.energie(KannStore.kann(this))) EnergieKachel.lesen(this, ::renderBereiche) // the Energie tile's line
        val spuren = StartAngebot(this, KannStore.kann(this), ::renderWidgetTypes).spuren(findViewById(R.id.spuren_group))
        findViewById<View>(R.id.spuren_section).visibility = if (spuren) View.VISIBLE else View.GONE
    }

    /** „Jetzt“: at most two hints while something wants attention ([Jetzt]); gone without one. */
    private fun renderJetzt() {
        val group = findViewById<ViewGroup>(R.id.jetzt_group)
        val alle = devices.orEmpty().mapNotNull(Jetzt::geraet) + Telefon.ueberfaellig(this)
        val zeigen = Jetzt.zeigen(alle, Jetzt.weg(this))
        group.removeAllViews()
        zeigen.forEach { h -> group.addView(hinweis(group, h)) }
        findViewById<View>(R.id.jetzt_section).visibility = if (zeigen.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun hinweis(group: ViewGroup, h: Jetzt.Hinweis): View {
        val v = layoutInflater.inflate(R.layout.item_hinweis, group, false)
        v.findViewById<TextView>(R.id.hinweis_text).text = h.text
        v.setOnClickListener {
            when (val z = h.ziel) {
                is Jetzt.Ziel.Seite -> openHomes(HomesPages.item(z.page))
                Jetzt.Ziel.Sicherung -> startActivity(Intent(this, SicherungActivity::class.java))
            }
        }
        v.findViewById<View>(R.id.hinweis_weg).setOnClickListener {
            Jetzt.wegtun(this, h)
            renderJetzt()
        }
        return v
    }

    /** „Dieses Telefon · <name>“ and its one line, orange while a backup part is overdue. */
    private fun renderTelefon() {
        findViewById<TextView>(R.id.telefon_zeile_titel).text = getString(R.string.telefon_zeile, Telefon.name(this))
        val zeile = Telefon.zeile(this)
        val stand = findViewById<TextView>(R.id.telefon_zeile_stand)
        stand.text = zeile
        stand.visibility = if (zeile.isEmpty()) View.GONE else View.VISIBLE
        stand.setTextColor(getColor(if (Telefon.ueberfaellig(this).isEmpty()) R.color.homes_text_muted else R.color.homes_accent))
    }

    private fun row(group: ViewGroup, icon: Int, label: String, onTap: () -> Unit): View {
        val row = layoutInflater.inflate(R.layout.item_widget_type, group, false)
        row.findViewById<ImageView>(R.id.type_icon).setImageResource(icon)
        row.findViewById<TextView>(R.id.type_label).text = label
        row.setOnClickListener { onTap() }
        return row
    }

    /**
     * The tools from Homes' catalog (`/napi/defs/tool`): the last one seen draws
     * at once, then the fresh one replaces it. Only tools that fill a list are
     * offered, and not the contacts ([ToolDef.isOffered]).
     */
    private fun renderTools() {
        if (toolsLoading) return
        toolsLoading = true
        thread {
            val fresh = WidgetStore.freshToolCatalog(applicationContext)
            runOnUiThread {
                toolsLoading = false
                if (fresh != null && !isFinishing) showTools(fresh.filter { it.isOffered })
            }
        }
    }

    /**
     * The widget list: the fixed types first, then a row per tool; a type the
     * box lacks is a quiet trace that cannot be placed (homes ADR 0001 §10).
     */
    private fun showTools(defs: List<ToolDef>) {
        val group = findViewById<ViewGroup>(R.id.widgets_group)
        group.removeAllViews()
        val zeilen = WidgetTypes.ALL.map { e ->
            StartAngebot.Zeile(e.key, e.icon, getString(e.label)) { pin(e.provider) }
        } + defs.map { def ->
            StartAngebot.Zeile(KannKey.TOOL + def.id, R.drawable.ic_services, def.label) { pinTool(def) }
        }
        val kept = WidgetStore.keptToolCatalog(this)
        StartAngebot(this, KannStore.kann(this), ::renderWidgetTypes).fuellen(
            group, Liste.WIDGETS, zeilen, { row(group, it.icon, it.label, it.tap) },
        ) { key, m ->
            if (!StartAngebot.widget(key)) null else kept.firstOrNull { KannKey.TOOL + it.id == key }?.label ?: m.titel
        }
    }

    private fun pinManager(): AppWidgetManager? {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || mgr == null || !mgr.isRequestPinAppWidgetSupported) {
            toast(getString(R.string.home_add_widget_manual))
            return null
        }
        return mgr
    }

    private fun pin(provider: Class<*>) {
        val mgr = pinManager() ?: return
        WidgetPin.request(this, mgr, provider)
    }

    private fun pinTool(def: ToolDef) {
        val mgr = pinManager() ?: return
        WidgetPin.request(this, mgr, ToolWidgetProvider::class.java, def.id, def.label)
    }

    // --- pairing --------------------------------------------------------------

    /** The typed address: refused if http, else stored and paired at. */
    private fun onConnect() {
        val typed = findViewById<EditText>(R.id.server_url).text.toString().trim()
        if (typed.isBlank()) {
            toast(getString(R.string.home_enter_url))
            return
        }
        if (ServerStore.refusesHttp(typed)) {
            toast(getString(R.string.server_http_refused))
            return
        }
        val server = ServerStore.normalize(typed)
        if (server == null) {
            toast(getString(R.string.home_enter_url))
            return
        }
        ServerStore.setBaseUrl(this, server)
        openPairing(server)
    }

    /** Open `/pair-device` at [server]; its pair link is taken while this pairing is pending. */
    private fun openPairing(server: String) {
        Pairing.start(this, server)
        open(Uri.parse(server + HomesConfig.PAIR_PATH))
    }

    private fun startScan() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.qr_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    /**
     * `cloud.dopp.homes://setup?server=…&token=…` from Homes' device page, or a
     * plain address. Nothing switches before the person confirms the host.
     */
    private fun handleScanned(text: String) {
        val setup = PairLinks.parseSetup(text)
        val raw = setup?.server ?: text.trim().takeIf { it.contains("://") }
        if (raw == null) {
            toast(getString(R.string.qr_invalid))
            return
        }
        if (ServerStore.refusesHttp(raw)) {
            toast(getString(R.string.server_http_refused))
            return
        }
        val server = ServerStore.normalize(raw)
        if (server == null) {
            toast(getString(R.string.qr_invalid))
            return
        }
        val host = ServerStore.hostOf(server)
        val token = setup?.token
        if (token != null) {
            confirm(getString(R.string.pairing_confirm_setup, host)) { pair(server, token) }
        } else {
            confirm(getString(R.string.pairing_confirm_server, host)) { openPairing(server) }
        }
    }

    /**
     * The redirect from /pair-device: `cloud.dopp.homes://pair#token=homes_device_…&id=…`.
     * Taken only while a pairing started here waits for it, and only once the
     * person confirms the host.
     */
    private fun handleDeepLink(intent: Intent?) {
        if (intent == null) return
        val data = intent.data ?: return
        if (!PairLinks.isPairLink(data)) return
        intent.data = null // handled once, not again on a recreate
        val server = Pairing.pending(this)
        if (server == null) {
            toast(getString(R.string.pairing_not_started))
            return
        }
        val token = PairLinks.pairToken(data)
        if (token == null) {
            toast(getString(R.string.pairing_failed))
            return
        }
        confirm(
            getString(R.string.pairing_confirm_link, ServerStore.hostOf(server)),
            onNo = { Pairing.cancel(this) },
        ) { pair(server, token) }
    }

    /** Ask before pairing; [message] names the host. The app's one dialog ([ActionDialog]). */
    private fun confirm(message: String, onNo: () -> Unit = {}, onYes: () -> Unit) {
        ActionDialog.show(
            activity = this,
            title = getString(R.string.pairing_confirm_title),
            message = message,
            sheet = ActionSheet(listOf(ActionItem(R.string.pairing_confirm_yes, ActionTone.PRIMARY))),
            onPick = { onYes() },
            onCancel = onNo,
            footer = getString(R.string.pairing_confirm_no),
            paintScrim = false,
        )
    }

    /** Check [token] with the Homes at [server] off the main thread, then store it and say how it went. */
    private fun pair(server: String, token: String) {
        val app = applicationContext
        thread {
            val checked = Pairing.check(server, token)
            runOnUiThread {
                val outcome = if (checked == Pairing.Outcome.PAIRED) Pairing.store(app, server, token) else checked
                toast(
                    getString(
                        when (outcome) {
                            Pairing.Outcome.PAIRED -> R.string.pairing_success
                            Pairing.Outcome.REFUSED -> R.string.pairing_refused
                            Pairing.Outcome.UNREACHABLE -> R.string.pairing_unreachable
                            Pairing.Outcome.NOT_SAVED -> R.string.pairing_not_saved
                        },
                    ),
                )
                if (outcome == Pairing.Outcome.PAIRED) askExemptionOnce()
                Live.ensure(app)
                if (!isFinishing) render()
            }
        }
    }

    /**
     * Once paired, ask once — saying why — to be exempt from battery
     * optimization: only then may Android's restart of the live service after
     * its process died go foreground on Android 12+. Then the notification.
     */
    private fun askExemptionOnce() {
        val prefs = getSharedPreferences(PREFS_ASKED, MODE_PRIVATE)
        if (Live.exempt(this) || prefs.getBoolean(KEY_EXEMPTION, false)) {
            askToNotify()
            return
        }
        prefs.edit().putBoolean(KEY_EXEMPTION, true).apply()
        ActionDialog.show(
            activity = this,
            title = getString(R.string.live_battery_title),
            message = getString(R.string.live_battery_why),
            sheet = ActionSheet(listOf(ActionItem(R.string.live_battery_yes, ActionTone.PRIMARY))),
            onPick = {
                requestExemption()
                askToNotify()
            },
            onCancel = { askToNotify() },
            footer = getString(R.string.live_battery_no),
            paintScrim = false,
        )
    }

    /** The system's own question for the exemption, else its settings list. */
    private fun requestExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        } catch (e: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    /** Once paired, ask for the live widgets' notification on Android 13+; without it they still run. */
    private fun askToNotify() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { notifyLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    private fun unpair() {
        StartDevices.clear(this)
        devices = null
        devicesFailed = false
        TokenStore.clear(this)
        ServerStore.clear(this)
        Assistent.clear(this)
        Pairing.cancel(this)
        HomesKonto.entfernen(applicationContext) // the household's contacts and calendars leave with the pairing (#206)
        KannStore.clear(this) // the next Homes says afresh what it can do
        EinrichtungStore.clear(this) // and its modules and tasks
        Teilen.schalten(this, null) // every share target and widget provider as before
        Anbieter.schalten(this, null)
        Anfordern.clear()
        AppShortcuts.refreshAsync(applicationContext) // takes the share target away (#188)
        findViewById<EditText>(R.id.server_url).setText("")
        Live.ensure(applicationContext)
        render()
        toast(getString(R.string.home_logged_out))
    }

    /** In a Custom Tab (it shares the browser's Authelia session), else the browser. */
    private fun open(uri: Uri) = Abschnitte.oeffnen(this, uri)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val PREFS_ASKED = "homes_asked"
        private const val KEY_EXEMPTION = "battery_exemption"

        /** From „Dieses Telefon“'s „Kopplung aufheben“: unpair, here. */
        const val EXTRA_ENTKOPPELN = "cloud.dopp.homes.ENTKOPPELN"
    }
}

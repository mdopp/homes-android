package cloud.dopp.homes.ausloeser

import android.nfc.NdefMessage
import android.os.SystemClock

/**
 * „WLAN anbieten“ (owner 2026-11-27: „Bau alles“): the phone answers as an
 * NFC tag with one WLAN record ([WlanTag]) — only while the person offers it
 * on [WlanAnbietenActivity], for at most [DAUER_MS], and only in memory: the
 * credential is put here by the WLAN form or a read WLAN tag, and forgotten
 * when the offer ends, the two minutes are up or the screen closes. Nothing
 * of it is stored, sent or logged.
 */
object WlanAngebot {

    /** How long one offer lasts, however often the screen goes and comes back. */
    const val DAUER_MS = 120_000L

    @Volatile
    private var nachricht: ByteArray? = null

    /** The network's name while one is held, for the screen; null: nothing to offer. */
    @Volatile
    var ssid: String? = null
        private set

    /** When the offer ends (elapsed realtime); 0: not begun. */
    @Volatile
    var bis = 0L
        private set

    @Volatile
    private var offen = false

    /** „Teilen per Berührung“ (owner 2026-11-28): one answer only, then the offer is over ([geteilt]). */
    @Volatile
    var einmal = false
        private set

    /** The one answer of an [einmal] offer went: a reader read the whole message. */
    @Volatile
    var geteilt = false
        private set

    /** Hold [n] for an offer of [name]; it begins when the screen is in front. [nurEinmal]: one reader, then over. */
    fun vorbereiten(name: String, n: NdefMessage, nurEinmal: Boolean = false) {
        nachricht = n.toByteArray()
        ssid = name
        bis = 0L
        offen = false
        einmal = nurEinmal
        geteilt = false
    }

    /** A reader read the NDEF file to its end ([Typ4Tag]): an [einmal] offer is over and forgotten. */
    fun gelesen() {
        if (!einmal || nachricht == null) return
        vergessen()
        geteilt = true
    }

    /** The screen is in front: answered from now until [bis] (set at the first start, never moved). False: nothing, or over. */
    fun anfangen(jetzt: Long = SystemClock.elapsedRealtime()): Boolean {
        if (nachricht == null) return false
        if (bis == 0L) bis = jetzt + DAUER_MS
        offen = jetzt < bis
        return offen
    }

    /** The screen went: nothing is answered until it is in front again. */
    fun pause() {
        offen = false
    }

    /** The record's bytes while offered, null otherwise (also once the time is up). */
    fun nachricht(jetzt: Long = SystemClock.elapsedRealtime()): ByteArray? = nachricht?.takeIf { offen && jetzt < bis }

    /** The offer over: the credential forgotten. */
    fun vergessen() {
        geteilt = false
        nachricht?.fill(0)
        nachricht = null
        ssid = null
        bis = 0L
        offen = false
    }
}

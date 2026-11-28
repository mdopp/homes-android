package cloud.dopp.homes.ausloeser

import android.content.Intent
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi

/**
 * „Ins Telefon übernehmen“ (owner 2026-11-27): a WLAN read from a tag handed
 * to Android's own WLAN settings, which ask the person whether to save it
 * (Android 11+, `ACTION_WIFI_ADD_NETWORKS`; no permission). Homes keeps
 * nothing of it.
 */
object WlanUebernahme {

    /** Why it cannot be handed over, one German sentence; null when it can. */
    fun warumNicht(n: WlanTag.Netz, sdk: Int = Build.VERSION.SDK_INT): String? = when {
        sdk < Build.VERSION_CODES.R ->
            "Dieses Android nimmt kein WLAN von einer App. Halte den Tag ans Telefon, während Homes zu ist: Android bietet das WLAN selbst an."
        n.schutz == WlanTag.Schutz.WPA2 && !n.psk -> "Dieses WLAN (${n.art}) kann Android so nicht übernehmen."
        else -> null
    }

    /** Android's question, for [warumNicht] null; null when the password is not one Android takes. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun absicht(n: WlanTag.Netz): Intent? {
        val s = runCatching {
            WifiNetworkSuggestion.Builder().setSsid(n.ssid).apply {
                if (n.schutz == WlanTag.Schutz.WPA2) setWpa2Passphrase(n.passwort)
            }.build()
        }.getOrNull() ?: return null
        return Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(s))
    }
}

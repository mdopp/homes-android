package cloud.dopp.homes.ausloeser

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.PowerManager
import cloud.dopp.homes.teilen.Teilen

/**
 * „WLAN anbieten“'s tag (owner 2026-11-27): a bound host-card-emulation
 * service Android's NFC stack alone binds (`BIND_NFC_SERVICE`) for the NDEF
 * application's AID, only with the phone unlocked (`requireDeviceUnlock`) —
 * never a foreground service. It answers as a read-only Type 4 Tag
 * ([Typ4Tag]) while [WlanAngebot] offers, and with the screen on; otherwise
 * every command is 6A82, as if there were no tag — and a reader asking then
 * makes [Teilen] ask the person what to share (owner approved 2026-11-28),
 * at most every 30 s, from this service while bound (an allowed start). An offer made there answers once.
 */
class WlanAnbietenDienst : HostApduService() {

    private val tag = Typ4Tag(WlanAngebot::gelesen) { WlanAngebot.nachricht()?.takeIf { bildschirmAn() } }

    private fun bildschirmAn() = (getSystemService(POWER_SERVICE) as? PowerManager)?.isInteractive == true

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        // nothing chosen: a reader that asks is told „nothing“, and the person is asked what to share (owner 2026-11-28)
        if (WlanAngebot.nachricht() == null && Typ4Tag.waehltNdef(commandApdu)) Teilen.anfrage(this)
        return tag.antwort(commandApdu)
    }

    override fun onDeactivated(reason: Int) = tag.zuruecksetzen()
}

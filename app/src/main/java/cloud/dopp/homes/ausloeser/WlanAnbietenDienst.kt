package cloud.dopp.homes.ausloeser

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.PowerManager

/**
 * „WLAN anbieten“'s tag (owner 2026-11-27): a bound host-card-emulation
 * service Android's NFC stack alone binds (`BIND_NFC_SERVICE`) for the NDEF
 * application's AID, only with the phone unlocked (`requireDeviceUnlock`) —
 * never a foreground service, no notification. It answers as a read-only
 * Type 4 Tag ([Typ4Tag]) while [WlanAngebot] offers, and with the screen on;
 * otherwise every command is 6A82, as if there were no tag.
 */
class WlanAnbietenDienst : HostApduService() {

    private val tag = Typ4Tag { WlanAngebot.nachricht()?.takeIf { bildschirmAn() } }

    private fun bildschirmAn() = (getSystemService(POWER_SERVICE) as? PowerManager)?.isInteractive == true

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray = tag.antwort(commandApdu)

    override fun onDeactivated(reason: Int) = tag.zuruecksetzen()
}

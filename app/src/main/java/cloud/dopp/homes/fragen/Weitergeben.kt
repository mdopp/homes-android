package cloud.dopp.homes.fragen

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri

/**
 * How Homes' message leaves the phone (Daten erfragen, owner 2026-11-28): by
 * WhatsApp to the contact's number, by SMS, or through Android's own share
 * sheet. Each is an intent for another app; nothing is sent or kept here.
 */
object Weitergeben {

    const val WHATSAPP = "com.whatsapp"

    /** `https://wa.me/<digits>?text=…` in WhatsApp: the number's digits only, a leading `00` dropped. */
    fun whatsapp(nummer: String, text: String): Intent {
        val ziffern = nummer.filter(Char::isDigit).let { if (nummer.trim().startsWith("00")) it.removePrefix("00") else it }
        val uri = Uri.Builder().scheme("https").authority("wa.me").appendPath(ziffern).appendQueryParameter("text", text).build()
        return Intent(Intent.ACTION_VIEW, uri).setPackage(WHATSAPP)
    }

    /** The messaging app with [nummer] (none: the person picks) and the message written. */
    fun sms(nummer: String?, text: String): Intent =
        Intent(Intent.ACTION_SENDTO, ("smsto:" + Uri.encode(nummer.orEmpty())).toUri()).putExtra("sms_body", text)

    /** Android's share sheet with the message as text. */
    fun anders(text: String, titel: String): Intent =
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), titel)
}

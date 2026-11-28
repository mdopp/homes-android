package cloud.dopp.homes.ausloeser

import android.app.Activity
import android.content.Context
import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Parcelable
import java.io.IOException

/**
 * Writes Homes' URI onto an NFC tag (homes ADR 0004 §1): one NDEF URI record,
 * nothing else on it. While the Auslöser screen is in front the phone reads in
 * reader mode (§2: a blank tag is offered there), so a tag held to it opens
 * nothing: written while the screen waits for one, else only looked at.
 * A tag that holds something is never written unless the person said yes to
 * losing it ([erlauben], after „Der Inhalt geht verloren.“): it is read
 * instead ([Ergebnis.Gelesen]), and the screen says what is on it.
 */
interface TagSchreiber {

    /** What a write came to. */
    sealed class Ergebnis {
        object Fertig : Ergebnis()
        data class Fehler(val satz: String) : Ergebnis()

        /** Looked at, not waited for: nothing on it yet. */
        object Leer : Ergebnis()

        /** Looked at, not waited for: something is on it already that the phone could not read. */
        object Beschrieben : Ergebnis()

        /** Something is on it: [nachricht], read and not written over; [id] the tag's own, for [erlauben]. */
        class Gelesen(val nachricht: NdefMessage, val id: ByteArray?) : Ergebnis()
    }

    enum class Bereit { KEIN_NFC, AUS, JA }

    /** Whether the phone has NFC, and it is on. */
    fun bereit(ctx: Context): Bereit

    /**
     * While [activity] is in front, each tag held to the phone: [uri] asked when it comes, written onto it when
     * there is one, else the tag only looked at; [ergebnis] on a binder thread.
     */
    fun an(activity: Activity, uri: () -> String?, ergebnis: (Ergebnis) -> Unit)

    /** Stop reading tags. */
    fun aus(activity: Activity)

    /**
     * Write [uri] onto [tag], a blank tag Android handed over ([LeerTagActivity]) and may still hold; null when
     * this writer cannot (the person holds it to the phone again, as for any new tag). Blocking.
     */
    fun direkt(tag: Parcelable, uri: String): Ergebnis? = null

    /**
     * Instead of [an] until [an] or [aus] again: the next tag held to the phone gets [nachricht] (a WLAN tag's
     * one record); a tag too small for it is said by [zuKlein] (needed, there bytes). A writer that cannot: nothing.
     */
    fun anNachricht(activity: Activity, nachricht: NdefMessage, zuKlein: (Int, Int) -> String, ergebnis: (Ergebnis) -> Unit) = Unit

    /**
     * The person said yes to losing what is on the tag [id] („Überschreiben“): the next write may go onto it,
     * once. Null takes it back. A writer that never reads: nothing.
     */
    fun erlauben(id: ByteArray?) = Unit

    companion object {
        /** May a write go onto the tag [id]: when it is blank, or it is the one the person said yes for. */
        fun darf(leer: Boolean, id: ByteArray?, erlaubt: ByteArray?): Boolean =
            leer || erlaubt != null && id?.contentEquals(erlaubt) == true

        /** Blank: one Ndef can format ([formatierbar]), or an Ndef tag whose message is none or empty records only. */
        fun leer(formatierbar: Boolean, ndef: Boolean, nachricht: NdefMessage?): Boolean =
            formatierbar || ndef && nachricht?.records.orEmpty().all { it.tnf == NdefRecord.TNF_EMPTY }
    }
}

/** The phone's own NFC. */
object NfcSchreiber : TagSchreiber {

    private const val ZU_FRUEH = "Der Tag war zu früh weg. Halte ihn noch einmal ans Telefon, bis „fertig“ dasteht."
    private const val SCHREIBGESCHUETZT = "Dieser Tag ist schreibgeschützt. Nimm einen anderen."
    private const val ZU_KLEIN = "Auf diesen Tag passt die Adresse nicht. Nimm einen mit mehr Speicher, etwa einen NTAG215."
    private const val NICHT_BESCHREIBBAR = "Der Tag ließ sich nicht beschreiben. Versuch es noch einmal oder nimm einen anderen."
    private const val KEIN_NDEF = "Diesen Tag kann das Telefon nicht beschreiben. Nimm einen NTAG-Aufkleber."

    /** Every kind of tag a sticker is; the platform's NDEF check stays, it gives Ndef and what is on the tag. */
    private const val ARTEN = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V

    override fun bereit(ctx: Context): TagSchreiber.Bereit {
        val nfc = NfcAdapter.getDefaultAdapter(ctx) ?: return TagSchreiber.Bereit.KEIN_NFC
        return if (nfc.isEnabled) TagSchreiber.Bereit.JA else TagSchreiber.Bereit.AUS
    }

    /** The tag the person said yes for; written once, then forgotten (also when the screen goes). */
    @Volatile
    private var erlaubt: ByteArray? = null

    override fun erlauben(id: ByteArray?) {
        erlaubt = id?.copyOf()
    }

    override fun an(activity: Activity, uri: () -> String?, ergebnis: (TagSchreiber.Ergebnis) -> Unit) {
        val nfc = NfcAdapter.getDefaultAdapter(activity) ?: return
        nfc.enableReaderMode(activity, { tag -> ergebnis(uri()?.takeIf { darf(tag) }?.let { schreiben(tag, it) } ?: ansehen(tag)) }, ARTEN, null)
    }

    /** Blank, or the one said yes for. */
    private fun darf(tag: Tag): Boolean {
        val ndef = Ndef.get(tag)
        val leer = TagSchreiber.leer(ndef == null && NdefFormatable.get(tag) != null, ndef != null, ndef?.cachedNdefMessage)
        return TagSchreiber.darf(leer, tag.id, erlaubt)
    }

    /** Blank: an NDEF tag with no record yet, or one still to be formatted; else what is on it. */
    private fun ansehen(tag: Tag): TagSchreiber.Ergebnis {
        val ndef = Ndef.get(tag) ?: return if (NdefFormatable.get(tag) != null) TagSchreiber.Ergebnis.Leer else TagSchreiber.Ergebnis.Fehler(KEIN_NDEF)
        val nachricht = ndef.cachedNdefMessage
        return when {
            TagSchreiber.leer(false, true, nachricht) -> TagSchreiber.Ergebnis.Leer // a new sticker often carries one empty record
            nachricht != null -> TagSchreiber.Ergebnis.Gelesen(nachricht, tag.id)
            else -> TagSchreiber.Ergebnis.Beschrieben
        }
    }

    override fun direkt(tag: Parcelable, uri: String): TagSchreiber.Ergebnis? = (tag as? Tag)?.let { schreiben(it, uri) }

    override fun anNachricht(activity: Activity, nachricht: NdefMessage, zuKlein: (Int, Int) -> String, ergebnis: (TagSchreiber.Ergebnis) -> Unit) {
        val nfc = NfcAdapter.getDefaultAdapter(activity) ?: return
        nfc.enableReaderMode(activity, { tag -> ergebnis(if (darf(tag)) schreiben(tag, nachricht, zuKlein) else ansehen(tag)) }, ARTEN, null)
    }

    override fun aus(activity: Activity) {
        erlaubt = null
        runCatching { NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity) }
    }

    /** [uri] as the tag's one record. */
    private fun schreiben(tag: Tag, uri: String): TagSchreiber.Ergebnis =
        schreiben(tag, NdefMessage(arrayOf(NdefRecord.createUri(uri)))) { _, _ -> ZU_KLEIN }

    /** [nachricht] as all that is on the tag. */
    private fun schreiben(tag: Tag, nachricht: NdefMessage, zuKlein: (Int, Int) -> String): TagSchreiber.Ergebnis {
        val ndef = Ndef.get(tag) ?: return formatieren(tag, nachricht)
        return versuche { ndef.use { beschreibe(it, nachricht, zuKlein) } }.also { if (it == TagSchreiber.Ergebnis.Fertig) erlaubt = null }
    }

    private fun beschreibe(ndef: Ndef, nachricht: NdefMessage, zuKlein: (Int, Int) -> String): TagSchreiber.Ergebnis {
        ndef.connect()
        if (!ndef.isWritable) return TagSchreiber.Ergebnis.Fehler(SCHREIBGESCHUETZT)
        if (ndef.maxSize < nachricht.byteArrayLength) return TagSchreiber.Ergebnis.Fehler(zuKlein(nachricht.byteArrayLength, ndef.maxSize))
        ndef.writeNdefMessage(nachricht)
        return TagSchreiber.Ergebnis.Fertig
    }

    /** A blank tag that has no NDEF yet: formatted with the record. */
    private fun formatieren(tag: Tag, nachricht: NdefMessage): TagSchreiber.Ergebnis {
        val leer = NdefFormatable.get(tag) ?: return TagSchreiber.Ergebnis.Fehler(KEIN_NDEF)
        return versuche {
            leer.use {
                it.connect()
                it.format(nachricht)
                TagSchreiber.Ergebnis.Fertig
            }
        }
    }

    /** The tag's failures in words: gone too early (lost, out of date), or it refused the record. */
    @Suppress("SwallowedException") // the sentence is the outcome
    private fun versuche(schreibe: () -> TagSchreiber.Ergebnis): TagSchreiber.Ergebnis = try {
        schreibe()
    } catch (e: IOException) {
        TagSchreiber.Ergebnis.Fehler(ZU_FRUEH)
    } catch (e: SecurityException) {
        TagSchreiber.Ergebnis.Fehler(ZU_FRUEH)
    } catch (e: FormatException) {
        TagSchreiber.Ergebnis.Fehler(NICHT_BESCHREIBBAR)
    }
}

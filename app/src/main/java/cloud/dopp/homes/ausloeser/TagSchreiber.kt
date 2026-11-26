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
import java.io.IOException

/**
 * Writes Homes' URI onto an NFC tag (homes ADR 0004 §1): one NDEF URI record,
 * nothing else on it. While the Auslöser screen is in front the phone reads in
 * reader mode (§2: a blank tag is offered there), so a tag held to it opens
 * nothing: written while the screen waits for one, else only looked at.
 */
interface TagSchreiber {

    /** What a write came to. */
    sealed class Ergebnis {
        object Fertig : Ergebnis()
        data class Fehler(val satz: String) : Ergebnis()

        /** Looked at, not waited for: nothing on it yet. */
        object Leer : Ergebnis()

        /** Looked at, not waited for: something is on it already. */
        object Beschrieben : Ergebnis()
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

    override fun an(activity: Activity, uri: () -> String?, ergebnis: (TagSchreiber.Ergebnis) -> Unit) {
        val nfc = NfcAdapter.getDefaultAdapter(activity) ?: return
        nfc.enableReaderMode(activity, { tag -> ergebnis(uri()?.let { schreiben(tag, it) } ?: ansehen(tag)) }, ARTEN, null)
    }

    /** Blank: an NDEF tag with no record yet, or one still to be formatted. */
    private fun ansehen(tag: Tag): TagSchreiber.Ergebnis {
        val ndef = Ndef.get(tag) ?: return if (NdefFormatable.get(tag) != null) TagSchreiber.Ergebnis.Leer else TagSchreiber.Ergebnis.Fehler(KEIN_NDEF)
        // a new sticker often carries one empty record
        val leer = ndef.cachedNdefMessage?.records.orEmpty().all { it.tnf == NdefRecord.TNF_EMPTY }
        return if (leer) TagSchreiber.Ergebnis.Leer else TagSchreiber.Ergebnis.Beschrieben
    }

    override fun aus(activity: Activity) {
        runCatching { NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity) }
    }

    /** [uri] as the tag's one record. */
    private fun schreiben(tag: Tag, uri: String): TagSchreiber.Ergebnis {
        val nachricht = NdefMessage(arrayOf(NdefRecord.createUri(uri)))
        val ndef = Ndef.get(tag) ?: return formatieren(tag, nachricht)
        return versuche { ndef.use { beschreibe(it, nachricht) } }
    }

    private fun beschreibe(ndef: Ndef, nachricht: NdefMessage): TagSchreiber.Ergebnis {
        ndef.connect()
        if (!ndef.isWritable) return TagSchreiber.Ergebnis.Fehler(SCHREIBGESCHUETZT)
        if (ndef.maxSize < nachricht.byteArrayLength) return TagSchreiber.Ergebnis.Fehler(ZU_KLEIN)
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

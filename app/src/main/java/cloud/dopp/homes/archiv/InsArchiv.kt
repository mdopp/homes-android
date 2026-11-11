package cloud.dopp.homes.archiv

import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.SicherungClient
import cloud.dopp.homes.sicherung.Age
import cloud.dopp.homes.sicherung.NachrichtenLauf
import cloud.dopp.homes.sicherung.Stueckweise
import java.io.File
import java.io.IOException

/**
 * One shared export into the person's archive (owner, 2026-11-11): its one
 * file ([Export]) encrypted on the phone to the archive's key (`GET
 * /napi/sicherung/schluessel`, as the SMS), into [ordner] in the app's cache,
 * then uploaded as source `sozial` (`facebook-<UTC>.json.age`,
 * `linkedin-<UTC>.csv.age`) through Homes' resumable upload. Homes keeps the
 * ciphertext in the archive's inbox; the archive reads it when it is opened.
 * No plaintext is written; the encrypted copy is deleted on every path.
 */
class InsArchiv(
    private val client: SicherungClient,
    private val ordner: File,
    private val uhr: () -> Long = System::currentTimeMillis,
) {
    sealed class Ausgang {
        data class Fertig(val art: Export.Art) : Ausgang()

        /** Homes said no, with its sentence if it gave one (no archive, the box full). */
        data class Nein(val satz: String?) : Ausgang()
        object FacebookHtml : Ausgang()
        object Fremd : Ausgang()
        object NichtGekoppelt : Ausgang()
        object Unerreichbar : Ausgang()
    }

    fun senden(datei: File): Ausgang {
        val ja = when (val e = Export.erkennen(datei)) {
            is Export.Erkannt.Ja -> e
            Export.Erkannt.FacebookHtml -> return Ausgang.FacebookHtml
            Export.Erkannt.Fremd -> return Ausgang.Fremd
        }
        ordner.mkdirs()
        val aus = File(ordner, "${ja.art.wire}-${NachrichtenLauf.stempel(uhr())}.${ja.art.endung}.age")
        return try {
            val an = Age.empfaenger(client.schluessel())
            Export.lesen(datei, ja) { ein -> aus.outputStream().use { Age.verschluesseln(an, ein, it) } }
            hochladen(aus, ja.art)
        } catch (e: SicherungClient.Abgelehnt) {
            if (e.code == HTTP_401) Ausgang.NichtGekoppelt else Ausgang.Nein(e.satz)
        } catch (ignored: IllegalArgumentException) {
            Ausgang.Nein(null) // Homes' key is no age recipient
        } catch (ignored: IOException) {
            Ausgang.Unerreichbar
        } finally {
            aus.delete()
        }
    }

    private fun hochladen(aus: File, art: Export.Art): Ausgang {
        val ruf = object : Stueckweise.Ruf {
            override fun anlegen() = client.anlegen(Napi.Quelle.SOZIAL, aus.name, aus.length(), aus.lastModified())
            override fun senden(id: String, offset: Long, puffer: ByteArray, laenge: Int) = client.senden(id, offset, puffer, laenge)
        }
        val ende = Stueckweise(ruf).laden(ruf.anlegen(), aus.length()) { ab -> aus.inputStream().also { it.skip(ab) } }
        return when (ende) {
            is Stueckweise.Ende.Fertig -> Ausgang.Fertig(art)
            is Stueckweise.Ende.Voll -> Ausgang.Nein(ende.satz)
            else -> Ausgang.Unerreichbar
        }
    }

    private companion object {
        const val HTTP_401 = 401
    }
}

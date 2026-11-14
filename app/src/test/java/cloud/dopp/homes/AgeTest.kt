package cloud.dopp.homes

import cloud.dopp.homes.sicherung.Age
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.InflaterInputStream

/**
 * The SMS and the call log leave the phone age-encrypted (laib ADR 0029).
 * Three proofs that [Age] writes age v1:
 *
 * 1. For fixed randomness its bytes are exactly [FIXTURES]: files made by a
 *    reference built on Go's x/crypto and decrypted by the real `age` CLI
 *    (filippo.io/age, `age -d -i`) back to the plaintext — 0 bytes, 1 byte,
 *    exactly one chunk, one chunk and a bit, several chunks.
 * 2. [AgeLeser] passes the C2SP age testkit (github.com/C2SP/CCTV, `age/testdata`
 *    at b88c4b2: every `x25519*` and `stream_*` vector, in
 *    `src/test/resources/age-testkit`, free to copy without attribution), and
 *    it reads back what [Age] writes with fresh randomness.
 * 3. [Age]'s payload re-encrypts each testkit success vector's plaintext with
 *    its file key and nonce to the vector's very bytes.
 */
class AgeTest {

    /** The testkit's identity; its recipient is [EMPFAENGER]. */
    private val identitaet = AgeLeser.identitaet(IDENTITAET)

    private fun klartext(n: Int) = ByteArray(n) { i -> ((i * 31 + 7 + i / 1024) and 0xff).toByte() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun theRecipientIsReadFromItsBech32() {
        assertArrayEquals(AgeLeser.empfaenger(identitaet), Age.empfaenger(EMPFAENGER))
        assertArrayEquals(AgeLeser.empfaenger(identitaet), Age.empfaenger(EMPFAENGER.uppercase()))
        listOf(
            EMPFAENGER.dropLast(1) + "x", // checksum
            EMPFAENGER.replaceFirst("age1", "agf1"), // another prefix
            "age1", "", "AGE1xmwwc06ly3ee5rytxm9mflaz2u56jjj36s0mypdrwsvlul66mv4q47ryef", // mixed case
        ).forEach { bad ->
            try {
                Age.empfaenger(bad)
                fail("took $bad")
            } catch (e: IllegalArgumentException) {
                // refused
            }
        }
    }

    @Test
    fun forFixedRandomnessTheBytesAreThoseTheAgeCliDecrypts() {
        val dateiSchluessel = hex("000102030405060708090a0b0c0d0e0f")
        val ephemer = ByteArray(32) { (0x40 + it).toByte() }
        val nonce = ByteArray(16) { (0xa0 + it).toByte() }
        FIXTURES.forEach { (n, laenge, hash) ->
            val out = ByteArrayOutputStream()
            Age.verschluesseln(Age.empfaenger(EMPFAENGER), ByteArrayInputStream(klartext(n)), out, dateiSchluessel, ephemer, nonce)
            val b = out.toByteArray()
            assertEquals("length for $n bytes", laenge, b.size)
            assertEquals("bytes for $n bytes", hash, sha(b))
            assertArrayEquals(klartext(n), AgeLeser.entschluesseln(identitaet, ByteArrayInputStream(b)))
        }
    }

    @Test
    fun whatItWritesWithFreshRandomnessReadsBack() {
        listOf(0, 5, Age.STUECK - 1, Age.STUECK, Age.STUECK + 1, 3 * Age.STUECK, 300_000).forEach { n ->
            val a = ByteArrayOutputStream()
            val b = ByteArrayOutputStream()
            Age.verschluesseln(Age.empfaenger(EMPFAENGER), ByteArrayInputStream(klartext(n)), a)
            Age.verschluesseln(Age.empfaenger(EMPFAENGER), ByteArrayInputStream(klartext(n)), b)
            assertTrue("fresh randomness each time", !a.toByteArray().contentEquals(b.toByteArray()))
            assertArrayEquals("$n bytes", klartext(n), AgeLeser.entschluesseln(identitaet, ByteArrayInputStream(a.toByteArray())))
            assertTrue(String(a.toByteArray(), 0, 40, Charsets.ISO_8859_1).startsWith("age-encryption.org/v1\n-> X25519 "))
        }
    }

    /** One testkit file: its headers, then the age file (zlib-compressed when it says so). */
    private class Vektor(val name: String, val kopf: Map<String, String>, val datei: ByteArray)

    private fun vektoren(): List<Vektor> {
        val liste = javaClass.classLoader!!.getResourceAsStream("age-testkit/index.txt")!!.bufferedReader().use { it.readLines() }
        return liste.filter { it.isNotBlank() }.map { name ->
            val roh = javaClass.classLoader!!.getResourceAsStream("age-testkit/$name")!!.use { it.readBytes() }
            var i = 0
            val kopf = LinkedHashMap<String, String>()
            while (true) {
                val ende = roh.indexOfFirst(i) { it == '\n'.code.toByte() }
                val zeile = String(roh, i, ende - i, Charsets.UTF_8)
                i = ende + 1
                if (zeile.isEmpty()) break
                val (k, v) = zeile.split(": ", limit = 2)
                kopf[k] = v
            }
            var datei = roh.copyOfRange(i, roh.size)
            if (kopf["compressed"] == "zlib") datei = InflaterInputStream(ByteArrayInputStream(datei)).use(InputStream::readBytes)
            Vektor(name, kopf, datei)
        }
    }

    private fun ByteArray.indexOfFirst(ab: Int, p: (Byte) -> Boolean): Int {
        for (j in ab until size) if (p(this[j])) return j
        return -1
    }

    @Test
    fun theReaderPassesTheAgeTestkit() {
        val alle = vektoren()
        assertEquals(42, alle.size)
        val falsch = alle.mapNotNull { v ->
            val id = AgeLeser.identitaet(v.kopf.getValue("identity"))
            val erwartet = v.kopf.getValue("expect")
            val ausgang = try {
                val klar = AgeLeser.entschluesseln(id, ByteArrayInputStream(v.datei))
                if (sha(klar) == v.kopf["payload"]) "success" else "wrong payload"
            } catch (e: AgeLeser.Kopffehler) {
                "header"
            } catch (e: AgeLeser.Nutzlastfehler) {
                "payload failure"
            }
            val passt = when (erwartet) {
                "success" -> ausgang == "success"
                "header failure", "no match" -> ausgang == "header"
                "payload failure" -> ausgang == "payload failure"
                else -> false
            }
            if (passt) null else "${v.name}: expected $erwartet, got $ausgang"
        }
        assertTrue(falsch.joinToString("\n"), falsch.isEmpty())
    }

    @Test
    fun thePayloadMatchesTheTestkitBytes() {
        val erfolge = vektoren().filter { it.kopf["expect"] == "success" }
        assertTrue(erfolge.size >= 10)
        erfolge.forEach { v ->
            val klar = AgeLeser.entschluesseln(AgeLeser.identitaet(v.kopf.getValue("identity")), ByteArrayInputStream(v.datei))
            // the payload starts after the header's last line, "--- <mac>\n"
            val text = String(v.datei, Charsets.ISO_8859_1)
            val ab = text.indexOf('\n', text.indexOf("\n--- ") + 1) + 1
            val nonce = v.datei.copyOfRange(ab, ab + 16)
            val out = ByteArrayOutputStream()
            Age.nutzlast(hex(v.kopf.getValue("file key")), nonce, ByteArrayInputStream(klar), out)
            assertArrayEquals(v.name, v.datei.copyOfRange(ab, v.datei.size), out.toByteArray())
        }
    }

    /** archiv#64: the box gets the size before the bytes; for the fixtures it is their length. */
    @Test
    fun theSizeIsKnownBeforeEncrypting() {
        FIXTURES.forEach { (n, laenge, _) -> assertEquals("size for $n bytes", laenge.toLong(), Age.groesse(n.toLong())) }
        listOf(0, 5, Age.STUECK - 1, Age.STUECK, Age.STUECK + 1, 3 * Age.STUECK, 300_000).forEach { n ->
            val out = ByteArrayOutputStream()
            Age.verschluesseln(Age.empfaenger(EMPFAENGER), ByteArrayInputStream(klartext(n)), out)
            assertEquals("size for $n bytes", out.size().toLong(), Age.groesse(n.toLong()))
        }
    }

    /**
     * archiv#64: a backup of several GB goes up encrypted as it is read, and
     * after a cut goes on from any offset: with the same [Age.Umschlag] the
     * bytes from there are those [Age.verschluesseln] writes — the fixtures'
     * bytes, which the age CLI decrypts — and the plaintext is opened at the
     * chunk the offset lies in, never from the start.
     */
    @Test
    fun fromAnyOffsetTheStreamIsTheSameBytes() {
        val dateiSchluessel = hex("000102030405060708090a0b0c0d0e0f")
        val ephemer = ByteArray(32) { (0x40 + it).toByte() }
        val nonce = ByteArray(16) { (0xa0 + it).toByte() }
        val u = Age.Umschlag.aus(java.util.Base64.getEncoder().encodeToString(dateiSchluessel + ephemer + nonce))!!
        val an = Age.empfaenger(EMPFAENGER)
        FIXTURES.forEach { (n, laenge, hash) ->
            val klar = klartext(n)
            val geoeffnet = ArrayList<Long>()
            val oeffnen: (Long) -> InputStream = { ab ->
                geoeffnet += ab
                ByteArrayInputStream(klar, ab.toInt(), klar.size - ab.toInt())
            }
            val ganz = Age.ab(an, u, n.toLong(), 0, oeffnen).readBytes()
            assertEquals("bytes for $n bytes", hash, sha(ganz))
            listOf(1, 150, 199, 200, 201, 4000, 65_000, 65_736, 65_737, 131_000, laenge - 1, laenge).filter { it <= laenge }.forEach { ab ->
                geoeffnet.clear()
                assertArrayEquals("from $ab of $n bytes", ganz.copyOfRange(ab, laenge), Age.ab(an, u, n.toLong(), ab.toLong(), oeffnen).readBytes())
                assertTrue("opened at a chunk: $geoeffnet", geoeffnet.all { it % Age.STUECK == 0L })
            }
        }
        // a plaintext shorter than said (the file changed): the stream ends, no made-up bytes
        val kurz = Age.ab(an, u, 200_000, 0) { ByteArrayInputStream(klartext(70_000)) }.readBytes()
        assertTrue(kurz.size < Age.groesse(200_000))
        assertTrue(Age.Umschlag.aus(Age.Umschlag.neu().text()) != null)
        assertTrue(Age.Umschlag.aus("kaputt") == null)
    }

    companion object {
        /** The C2SP testkit's identity; its recipient is [EMPFAENGER]. */
        const val IDENTITAET = "AGE-SECRET-KEY-1EGTZVFFV20835NWYV6270LXYVK2VKNX2MMDKWYKLMGR48UAWX40Q2P2LM0"

        /** The testkit identity's recipient (`age-keygen -y`). */
        const val EMPFAENGER = "age1xmwwc06ly3ee5rytxm9mflaz2u56jjj36s0mypdrwsvlul66mv4q47ryef"

        /**
         * Plaintext length, file length, SHA-256 of the file — for file key
         * 00…0f, ephemeral secret 40…5f, nonce a0…af, plaintext `(i*31+7+i/1024) & 0xff`.
         * Each file decrypts with `age -d` (filippo.io/age) to that plaintext.
         */
        val FIXTURES = listOf(
            Triple(0, 200, "ded3990a22721ecbd969f75d686833621c7b450dc55405c430bb8b6ea15c8d1e"),
            Triple(1, 201, "c2ba606df41962fd9d230a931e836d2bf1272d7801331adb71bc85bc67865f0b"),
            Triple(65536, 65736, "403cdfb779ad50b8e9bcc9fcc1629d551abcbf4271ad761a5b017a0f5a162d67"),
            Triple(70000, 70216, "c5388695c17590b95722d319e9ee4607dc805c7a4e12c30505e28ab112d565af"),
            Triple(200000, 200248, "d6d7e254941ba65f1edf71ca316845d7fa0e60381cae74ad1da2d76afb175638"),
        )
    }
}

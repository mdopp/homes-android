package cloud.dopp.homes

import cloud.dopp.homes.kann.Kann
import cloud.dopp.homes.ui.StartAngebot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * „Dein Archiv“ on Mehr (owner 2026-11-10): only when Homes' `/napi/kann`
 * says `archiv` for this device's person — not for another person, not
 * before Homes has answered, not without a Homes address.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchivZeileTest {

    private fun kann(vararg mehr: String): Kann? = Kann.lesen(
        """{"version":1,"module":[],"app":{"mehr":[${mehr.joinToString(",") { "\"$it\"" }}]}}""",
    )

    @Test
    fun theRowIsTheArchiveOnlyForItsPerson() {
        val base = "https://www.box.test"
        assertEquals("https://archiv.box.test/", StartAngebot.archiv(kann("daten-sichern", "archiv"), base))
        assertNull(StartAngebot.archiv(kann("daten-sichern"), base))
        assertNull(StartAngebot.archiv(null, base))
        assertNull(StartAngebot.archiv(kann("archiv"), null))
    }
}

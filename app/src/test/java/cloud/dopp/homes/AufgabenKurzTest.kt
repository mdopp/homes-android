package cloud.dopp.homes

import cloud.dopp.homes.einrichtung.Abschnitte
import org.junit.Assert.assertEquals
import org.junit.Test

/** The start screen shows the first tasks and, with more, the way to all (owner 2026-11-14). */
class AufgabenKurzTest {
    @Test
    fun fewTasksAllShownNoRowToAll() {
        assertEquals(listOf(1, 2) to false, Abschnitte.kurz(listOf(1, 2), 2))
        assertEquals(listOf(1, 2, 3) to false, Abschnitte.kurz(listOf(1, 2, 3), 3))
    }

    @Test
    fun moreTasksFirstThreeAndRowToAll() {
        assertEquals(listOf(1, 2, 3) to true, Abschnitte.kurz(listOf(1, 2, 3, 4, 5), 5))
    }

    @Test
    fun homesCountsMoreThanItSentRowToAll() {
        assertEquals(listOf(1, 2) to true, Abschnitte.kurz(listOf(1, 2), 7))
    }
}

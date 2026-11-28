package cloud.dopp.homes

import cloud.dopp.homes.widget.DeviceWidgetProvider
import cloud.dopp.homes.widget.ToolWidgetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Which widgets keep the live service running (#177 review, findings 3 and
 * 9): those it keeps current — a device widget draws its entity's frames,
 * a tool widget's rows, and every one of them is read
 * again on screen-on.
 * Removing a fed widget looks again, so the last one gone stops the service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveFeedTest {

    private val fed: List<*> by lazy { Reach.get(Reach.obj("cloud.dopp.homes.realtime.Live"), "FED") as List<*> }

    @Test
    fun theWidgetsTheStreamKeepsCurrentAreFed() {
        assertEquals(
            setOf(DeviceWidgetProvider::class.java, ToolWidgetProvider::class.java),
            fed.toSet(),
        )
    }

    @Test
    fun removingAFedWidgetLooksAgain() {
        val missing = fed.map { it as Class<*> }.filter { cls ->
            val src = File(RepoFiles.main, "java/" + cls.name.replace('.', '/') + ".kt").readText()
            val deleted = src.substringAfter("override fun onDeleted(", "")
            deleted.isEmpty() || !deleted.substringBefore("\n    }").contains("Live.recheck(")
        }
        assertTrue("onDeleted does not look again: $missing", missing.isEmpty())
    }
}

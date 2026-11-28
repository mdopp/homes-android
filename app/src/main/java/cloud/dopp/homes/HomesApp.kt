package cloud.dopp.homes

import android.app.Application
import cloud.dopp.homes.teilen.Vorne

/** The process: it only tells [Vorne] which of the app's screens is in front. */
class HomesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Vorne.beobachten(this)
    }
}

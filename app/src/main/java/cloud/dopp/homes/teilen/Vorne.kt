package cloud.dopp.homes.teilen

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.os.Bundle
import android.os.PowerManager
import java.lang.ref.WeakReference

/**
 * Whether one of the app's own screens is in front (owner approved 2026-11-28, „Was teilen?“ vorne öffnen): resumed,
 * the screen on, the phone unlocked. Then a touch's „Was teilen?“ is started from it ([Teilen]) — an allowed start,
 * the app is visible — rather than from the bound HCE service. It counts resumed activities; it holds none alive.
 */
object Vorne : Application.ActivityLifecycleCallbacks {

    @Volatile
    private var offen = 0

    @Volatile
    private var zuletzt: WeakReference<Activity>? = null

    /** From the process' start on (a new Application in each test, so counted afresh). */
    fun beobachten(app: Application) {
        offen = 0
        zuletzt = null
        app.registerActivityLifecycleCallbacks(this)
    }

    /** The resumed screen, while the phone is on and unlocked; else null. */
    fun aktivitaet(): Activity? {
        val a = zuletzt?.get()?.takeIf { offen > 0 && !it.isFinishing } ?: return null
        val an = a.getSystemService(PowerManager::class.java)?.isInteractive != false
        val gesperrt = a.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        return a.takeIf { an && !gesperrt }
    }

    override fun onActivityResumed(activity: Activity) {
        offen++
        zuletzt = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        offen = maxOf(0, offen - 1)
        if (zuletzt?.get() === activity) zuletzt = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

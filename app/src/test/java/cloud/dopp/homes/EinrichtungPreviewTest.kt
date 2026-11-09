package cloud.dopp.homes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.Assistent
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.einrichtung.Abschnitte
import cloud.dopp.homes.einrichtung.EinrichtungStore
import cloud.dopp.homes.einrichtung.Kurz
import cloud.dopp.homes.einrichtung.Verknuepfungen
import cloud.dopp.homes.ui.HomeActivity
import cloud.dopp.homes.ui.StartDevices
import cloud.dopp.homes.verbindung.Verbindung
import cloud.dopp.homes.widget.AppShortcuts
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.net.ConnectException
import java.util.Calendar

/**
 * „Öffnen“ and „Deine Aufgaben“ on the start screen (owner, 2026-11-04),
 * below „Deine Geräte“, on a 411 dp phone: Homes answering, and the box not
 * reached (greyed, the last answer). And the app icon's long-press menu, the
 * four shortcuts as a launcher lists them. Demo data only. Into
 * `build/widget-preview/einrichtung-<name>.png` (and `$WIDGET_PREVIEW_DIR`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class EinrichtungPreviewTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun drawTheStartScreen() = start("start", offline = false)

    @Test
    fun drawTheStartScreenOffline() = start("offline", offline = true)

    @Test
    fun drawTheLongPressMenu() {
        koppeln()
        AppShortcuts.refresh(ctx())
        val shortcuts = ShortcutManagerCompat.getDynamicShortcuts(ctx()).filter { it.id.startsWith(Verknuepfungen.PREFIX) }.sortedBy { it.rank }
        assertEquals(listOf("Garagentor", "Einkaufsliste", "Zeitleiste", "Dokument scannen"), shortcuts.map { it.shortLabel.toString() })
        val d = ctx().resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        // a launcher's popup: white, rounded, one row per shortcut, under the app's icon
        val popup = LinearLayout(ctx()).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 24 * d
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        shortcuts.forEach { s ->
            popup.addView(
                LinearLayout(ctx()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(24), dp(10))
                    val bild = Kurz.values().first { Verknuepfungen.PREFIX + it.id == s.id }.bild // the platform keeps no icon object to read back
                    addView(ImageView(ctx()).apply { setImageResource(bild) }, LinearLayout.LayoutParams(dp(36), dp(36)))
                    addView(
                        TextView(ctx()).apply {
                            text = s.shortLabel
                            textSize = 16f
                            setTextColor(Color.rgb(0x1F, 0x1F, 0x1F))
                            setPadding(dp(16), 0, 0, 0)
                        },
                    )
                },
            )
        }
        val icon = ImageView(ctx()).apply { setImageResource(R.mipmap.ic_launcher) }
        val w = (ctx().resources.configuration.screenWidthDp * d).toInt()
        val h = dp(420)
        popup.measure(View.MeasureSpec.makeMeasureSpec(dp(260), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        popup.layout(0, 0, popup.measuredWidth, popup.measuredHeight)
        icon.measure(View.MeasureSpec.makeMeasureSpec(dp(64), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(dp(64), View.MeasureSpec.EXACTLY))
        icon.layout(0, 0, dp(64), dp(64))
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(0x2E, 0x3B, 0x4E)) // a wallpaper
        val canvas = Canvas(bmp)
        canvas.save()
        canvas.translate(dp(40).toFloat(), dp(32).toFloat())
        popup.draw(canvas)
        canvas.restore()
        canvas.save()
        canvas.translate(dp(40).toFloat(), (dp(32) + popup.measuredHeight + dp(16)).toFloat())
        icon.draw(canvas)
        canvas.restore()
        save("menue", bmp)
    }

    private fun koppeln() {
        ServerStore.setBaseUrl(ctx(), "https://homes.example")
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_TESTTOKEN123")
        Assistent.save(ctx(), "Homes")
        StartDevices.save(ctx(), FAVORITES)
        EinrichtungStore.neu(ctx(), ANTWORT)
    }

    private fun start(name: String, offline: Boolean) {
        koppeln()
        if (offline) {
            Verbindung.ergebnis(ctx(), ConnectException("Connection refused"), null)
            ctx().getSharedPreferences("homes_verbindung", Context.MODE_PRIVATE).edit().putLong("stand", at0814()).commit()
        }
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().visible().get()
        // the screen's own reads of homes.example fail here: let them end, then say what the drawing shows
        Thread.sleep(SETTLE_MS)
        ShadowLooper.idleMainLooper()
        Verbindung.ergebnis(ctx(), if (offline) ConnectException("Connection refused") else null, if (offline) null else 200)
        if (offline) ctx().getSharedPreferences("homes_verbindung", Context.MODE_PRIVATE).edit().putLong("stand", at0814()).commit()
        HomeActivity::class.java.getDeclaredMethod("renderDevices").apply { isAccessible = true }.invoke(a)
        Abschnitte.zeichnen(a)
        ShadowLooper.idleMainLooper()
        val d = a.resources.displayMetrics.density
        val w = (a.resources.configuration.screenWidthDp * d).toInt()
        val content = a.findViewById<ViewGroup>(R.id.home_root).getChildAt(0) as ViewGroup
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.oeffnen_section).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.aufgaben_section).visibility)
        val oeffnen = a.findViewById<ViewGroup>(R.id.oeffnen_group)
        assertEquals("the three, then Homes", 4, oeffnen.childCount)
        val titel = (0 until oeffnen.childCount).map { oeffnen.getChildAt(it).findViewById<TextView>(R.id.einr_titel).text.toString() }
        assertEquals(listOf("Dokumente", "Zeitleiste", "Musik verwalten", "Homes"), titel)
        val unter = (0 until oeffnen.childCount).map { oeffnen.getChildAt(it).findViewById<TextView>(R.id.einr_unter) }
        assertEquals("the row says where it stands", "Zuletzt aufgezeichnet heute 08:40", unter[1].text.toString())
        assertEquals(listOf(View.GONE, View.VISIBLE, View.GONE, View.GONE), unter.map { it.visibility })
        if (!offline) {
            oeffnen.getChildAt(1).performClick()
            assertEquals("the timeline itself, not Homes' page about it", "https://reitti.example", shadowOf(a).nextStartedActivity?.dataString)
            oeffnen.getChildAt(3).performClick()
            assertEquals("Homes' start page", "https://homes.example/start", shadowOf(a).nextStartedActivity?.dataString)
        }
        assertEquals(2, a.findViewById<ViewGroup>(R.id.aufgaben_group).childCount)
        assertEquals("2", a.findViewById<TextView>(R.id.aufgaben_zahl).text.toString())
        // from the line about the box (offline) or „Deine Geräte“ down to the end of the tasks
        val top = top(content, if (offline) a.findViewById(R.id.verbindung) else a.findViewById<ViewGroup>(R.id.devices_group).let { g ->
            (g.parent as ViewGroup).getChildAt((g.parent as ViewGroup).indexOfChild(g) - 2)
        })
        val aufgaben = a.findViewById<View>(R.id.aufgaben_section)
        val bottom = top(content, aufgaben) + aufgaben.height
        val margin = (20 * d).toInt()
        val bmp = Bitmap.createBitmap(w, bottom - top + 2 * margin, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(ctx().getColor(R.color.homes_bg))
        val canvas = Canvas(bmp)
        canvas.translate(0f, (margin - top).toFloat())
        content.draw(canvas)
        save(name, bmp)
    }

    private fun top(content: ViewGroup, v: View): Int {
        val r = android.graphics.Rect(0, 0, v.width, v.height)
        content.offsetDescendantRectToMyCoords(v, r)
        return r.top
    }

    private fun save(name: String, bmp: Bitmap) {
        listOfNotNull(File(RepoFiles.root, "app/build/widget-preview"), System.getenv("WIDGET_PREVIEW_DIR")?.let(::File)).forEach { dir ->
            dir.mkdirs()
            File(dir, "einrichtung-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun at0814() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 8)
        set(Calendar.MINUTE, 14)
    }.timeInMillis

    companion object {
        private const val SETTLE_MS = 1500L

        /** Four favourites, the garage among them. */
        private const val FAVORITES = """{"ok":true,"favorites":[
            {"entity_id":"light.buero","name":"Bürolicht","domain":"light","state":"off","supported_color_modes":["brightness"],"page":"/raeume/light.buero"},
            {"entity_id":"light.kueche","name":"Küchenlicht","domain":"light","state":"on","brightness":179,"supported_color_modes":["brightness"],"page":"/raeume/light.kueche"},
            {"entity_id":"cover.garage","name":"Garagentor Garage","domain":"cover","device_class":"garage","state":"closed","supported_features":3,"page":"/raeume/cover.garage"},
            {"entity_id":"lock.haustuer","name":"Haustür","domain":"lock","state":"locked","supported_features":1,"page":"/raeume/lock.haustuer"}]}"""

        /** A household's answer: Einkauf and the three Homes marks `oeffnen` on the web, two apps still to set up, one done. */
        private const val ANTWORT = """{"version":1,"offen":2,"module":[
            {"name":"fotos","titel":"Fotos","icon":"fotos","web":"https://photos.example",
             "app":{"name":"Immich","paket":"app.alextran.immich","store":"https://play.google.com/store/apps/details?id=app.alextran.immich"},
             "nur_web":false,"aufgabe":{"titel":"Fotos auf dem Handy einrichten","erledigt":false,"einrichten":"https://homes.example/einrichten/fotos"}},
            {"name":"passwoerter","titel":"Passwörter","icon":"passwoerter","web":"https://vault.example",
             "app":{"name":"Bitwarden","paket":"com.x8bit.bitwarden","store":"https://play.google.com/store/apps/details?id=com.x8bit.bitwarden"},
             "nur_web":false,"aufgabe":{"titel":"Passwörter auf dem Handy einrichten","erledigt":false,"einrichten":"https://homes.example/einrichten/passwoerter"}},
            {"name":"filme","titel":"Filme und Musik","icon":"filme","web":"https://media.example",
             "app":{"name":"Jellyfin","paket":"org.jellyfin.mobile","store":"https://play.google.com/store/apps/details?id=org.jellyfin.mobile"},
             "nur_web":false,"aufgabe":{"titel":"Filme und Musik auf dem Handy einrichten","erledigt":true,"am":"2026-11-04","wie":"app","einrichten":"https://homes.example/einrichten/filme"}},
            {"name":"einkauf","titel":"Einkauf","icon":"einkauf","oeffnen":false,"web":"https://homes.example/einkauf","nur_web":true},
            {"name":"dokumente","titel":"Dokumente","icon":"dokumente","oeffnen":true,"web":"https://homes.example/dokumente","nur_web":true},
            {"name":"standort","titel":"Zeitleiste","icon":"standort","oeffnen":true,"web":"https://homes.example/standort","ziel":"https://reitti.example","stand":"Zuletzt aufgezeichnet heute 08:40","nur_web":true},
            {"name":"musik","titel":"Musik verwalten","icon":"musik","oeffnen":true,"web":"https://homes.example/musik","nur_web":true}],
           "ziele":{"einkauf":"https://homes.example/einkauf","zeitleiste":"https://reitti.example","scannen":"https://homes.example/napi/upload"}}"""
    }
}

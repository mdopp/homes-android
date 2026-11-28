package cloud.dopp.homes.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/**
 * The energy widget, taken out (owner 2026-11-28: „Energie brauche ich auch
 * nicht als Widget sondern nur als Link zur Anzeige.“): the start screen's
 * „Energie“ tile opens Homes' Energie page instead. The app cannot take a
 * placed widget off the home screen, so a placed one says how, quietly
 * ([WidgetFallback.ausgebaut]), and reads nothing. The provider stays only
 * while one is placed ([cloud.dopp.homes.kann.Anbieter]); the picker never
 * offers it.
 */
class EnergyWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { WidgetFallback.ausgebaut(context, it) }
    }
}

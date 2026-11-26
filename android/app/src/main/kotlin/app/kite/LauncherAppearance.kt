package app.kite

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object LauncherAppearance {
    private val names = listOf("LauncherDefault", "LauncherMaps", "LauncherNight", "LauncherNightMaps")

    fun apply(context: Context, night: Boolean, maps: Boolean) {
        val active = (if (night) 2 else 0) + (if (maps) 1 else 0)
        val manager = context.packageManager
        val entries = names.mapIndexed { index, name ->
            ComponentName(context.packageName, "app.kite.$name") to if (index == active) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        if (Build.VERSION.SDK_INT >= 33) {
            manager.setComponentEnabledSettings(entries.map { (component, state) ->
                PackageManager.ComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            })
        } else {
            val (component, state) = entries[active]
            manager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            entries.filter { it.first != component }.forEach { (other, disabled) ->
                manager.setComponentEnabledSetting(other, disabled, PackageManager.DONT_KILL_APP)
            }
        }
        AppSettings.launcherNightIcon = night
        AppSettings.launcherMapsName = maps
    }
}

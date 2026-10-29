package app.litelauncher

import android.content.pm.LauncherActivityInfo
import android.os.UserHandle

/** One launchable activity. [key] is stable across restarts and identifies it in preferences. */
class App(val label: String, val info: LauncherActivityInfo, val user: UserHandle, userSerial: Long) {
    val packageName: String get() = info.componentName.packageName
    val key = info.componentName.flattenToShortString() + "#" + userSerial
    val searchLabel = label.lowercase()
}

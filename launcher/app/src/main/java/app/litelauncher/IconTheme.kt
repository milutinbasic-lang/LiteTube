package app.litelauncher

/**
 * Swaps the icons of common apps (Google's and AOSP's defaults, plus a few open-source
 * alternatives) for Adwaita-style ones like Phosh shows on postmarketOS. Apps not listed here
 * keep their own icon.
 */
object IconTheme {

    private val byPackage: Map<String, Int> = HashMap<String, Int>().apply {
        fun map(icon: Int, vararg packages: String) = packages.forEach { put(it, icon) }

        map(R.drawable.pm_dialer, "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer")
        map(
            R.drawable.pm_messages, "com.google.android.apps.messaging", "com.android.mms",
            "com.android.messaging", "com.samsung.android.messaging",
        )
        map(
            R.drawable.pm_browser, "com.android.chrome", "com.chrome.beta", "org.chromium.chrome",
            "com.android.browser", "org.mozilla.firefox", "org.mozilla.fennec_fdroid", "com.sec.android.app.sbrowser",
        )
        map(
            R.drawable.pm_camera, "com.google.android.GoogleCamera", "com.android.camera", "com.android.camera2",
            "org.lineageos.aperture", "com.sec.android.app.camera",
        )
        map(R.drawable.pm_settings, "com.android.settings")
        map(R.drawable.pm_contacts, "com.google.android.contacts", "com.android.contacts", "com.samsung.android.app.contacts")
        map(R.drawable.pm_clock, "com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage")
        map(
            R.drawable.pm_calculator, "com.google.android.calculator", "com.android.calculator2",
            "com.sec.android.app.popupcalculator",
        )
        map(
            R.drawable.pm_calendar, "com.google.android.calendar", "com.android.calendar", "org.lineageos.etar",
            "com.samsung.android.calendar",
        )
        map(
            R.drawable.pm_photos, "com.google.android.apps.photos", "com.android.gallery3d", "org.lineageos.glimpse",
            "com.sec.android.gallery3d",
        )
        map(
            R.drawable.pm_files, "com.google.android.apps.nbu.files", "com.android.documentsui",
            "com.google.android.documentsui", "com.sec.android.app.myfiles",
        )
        map(R.drawable.pm_maps, "com.google.android.apps.maps", "net.osmand", "net.osmand.plus", "app.organicmaps")
        map(R.drawable.pm_email, "com.google.android.gm", "com.android.email", "com.samsung.android.email.provider")
        map(
            R.drawable.pm_videos, "com.google.android.youtube", "com.google.android.videos", "org.videolan.vlc",
            "io.github.milutinbasic_lang.litetube", "io.github.milutinbasic_lang.litetube.debug",
        )
        map(R.drawable.pm_music, "com.google.android.apps.youtube.music", "com.spotify.music")
        map(R.drawable.pm_store, "com.android.vending", "org.fdroid.fdroid", "com.aurora.store")
        map(R.drawable.pm_notes, "com.google.android.keep", "com.google.android.apps.docs.editors.docs")
        map(R.drawable.pm_terminal, "com.termux")
    }

    /**
     * Default apps found by intent (see LauncherActivity.defaultApps), so another vendor's
     * dialer, browser, etc. is themed too.
     */
    @Volatile
    var roles: Map<String, Int> = emptyMap()

    /** The themed icon for [app], or 0 to keep its own. */
    fun iconFor(app: App): Int {
        val activity = app.info.componentName.className
        // Some phones ship the dialer and contacts as two launcher activities of one package.
        if (app.packageName == "com.android.contacts" && "Dial" in activity) return R.drawable.pm_dialer
        return byPackage[app.packageName] ?: roles[app.packageName] ?: 0
    }
}

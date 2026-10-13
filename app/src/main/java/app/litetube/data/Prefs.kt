package app.litetube.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

object Prefs {
    const val THEME = "theme"
    const val MAX_QUALITY = "max_quality"
    const val LOW_POWER = "low_power"
    const val AUDIO_ONLY = "audio_only"
    const val SHOW_THUMBNAILS = "show_thumbnails"
    const val SHOW_RELATED = "show_related"
    const val AUTO_REFRESH = "auto_refresh"
    const val BACKGROUND_PLAY = "background_play"
    const val SPONSORBLOCK = "sponsorblock"
    const val SPONSORBLOCK_CATEGORIES = "sponsorblock_categories"
    const val CAPTION_LANGUAGE = "caption_language"

    private val DEFAULT_SPONSOR_CATEGORIES = setOf("sponsor", "selfpromo", "interaction")

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = PreferenceManager.getDefaultSharedPreferences(context)
    }

    /** "system", "light", "dark" or "black" (AMOLED). */
    val theme: String get() = sp.getString(THEME, "system") ?: "system"
    val maxQuality: Int get() = sp.getString(MAX_QUALITY, "360")?.toIntOrNull() ?: 360
    val lowPower: Boolean get() = sp.getBoolean(LOW_POWER, true)
    val audioOnly: Boolean get() = sp.getBoolean(AUDIO_ONLY, false)
    val showThumbnails: Boolean get() = sp.getBoolean(SHOW_THUMBNAILS, true)
    val showRelated: Boolean get() = sp.getBoolean(SHOW_RELATED, true)
    val autoRefresh: Boolean get() = sp.getBoolean(AUTO_REFRESH, true)
    val backgroundPlay: Boolean get() = sp.getBoolean(BACKGROUND_PLAY, true)
    val sponsorBlock: Boolean get() = sp.getBoolean(SPONSORBLOCK, true)
    val sponsorBlockCategories: Set<String>
        get() = sp.getStringSet(SPONSORBLOCK_CATEGORIES, null) ?: DEFAULT_SPONSOR_CATEGORIES

    /** Last caption language the user picked; null = captions off (the default). */
    var captionLanguage: String?
        get() = sp.getString(CAPTION_LANGUAGE, null)?.ifEmpty { null }
        set(value) = sp.edit().putString(CAPTION_LANGUAGE, value.orEmpty()).apply()

    fun applyNightMode() {
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark", "black" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}

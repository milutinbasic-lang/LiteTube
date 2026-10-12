package app.litetube.ui

import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import app.litetube.BuildConfig
import app.litetube.R
import app.litetube.data.FeedCache
import app.litetube.data.Prefs
import app.litetube.databinding.ActivitySettingsBinding
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupToolbar(b.toolbar)
        supportActionBar?.setTitle(R.string.settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

        @OptIn(ExperimentalCoilApi::class)
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            findPreference<Preference>("version")?.summary = BuildConfig.VERSION_NAME
            findPreference<Preference>("clear_cache")?.setOnPreferenceClickListener {
                val context = requireContext().applicationContext
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        context.imageLoader.memoryCache?.clear()
                        context.imageLoader.diskCache?.clear()
                        FeedCache.clear()
                    }
                    context.toast(context.getString(R.string.cache_cleared))
                }
                true
            }
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (key != Prefs.THEME) return
            val before = AppCompatDelegate.getDefaultNightMode()
            Prefs.applyNightMode()
            // Switching between dark and black keeps the same night mode, so recreate manually.
            if (AppCompatDelegate.getDefaultNightMode() == before) activity?.recreate()
        }
    }
}

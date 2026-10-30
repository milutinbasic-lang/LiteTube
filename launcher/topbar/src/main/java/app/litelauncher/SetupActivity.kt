package app.litelauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import app.litelauncher.topbar.R

/** Opens Accessibility settings, where the top bar service is switched on or off. */
class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(this, R.string.setup_hint, Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
        }
        finish()
    }
}

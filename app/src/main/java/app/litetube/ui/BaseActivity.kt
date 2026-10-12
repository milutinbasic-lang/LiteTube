package app.litetube.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import app.litetube.R
import app.litetube.data.Prefs

abstract class BaseActivity : AppCompatActivity() {

    private var appliedTheme: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedTheme = Prefs.theme
        // Pure black backgrounds save power on AMOLED screens.
        if (appliedTheme == "black") theme.applyStyle(R.style.ThemeOverlay_LiteTube_Black, true)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (appliedTheme != Prefs.theme) recreate()
    }

    protected fun setupToolbar(toolbar: Toolbar, showUp: Boolean = true) {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(showUp)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

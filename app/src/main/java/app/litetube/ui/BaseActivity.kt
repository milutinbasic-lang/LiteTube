package app.litetube.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import app.litetube.CrashLog
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
        if (appliedTheme != Prefs.theme) {
            recreate()
            return
        }
        CrashLog.take()?.let(::showCrashReport)
    }

    private fun showCrashReport(report: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.crash_title)
            .setMessage(getString(R.string.crash_message) + "\n\n" + report.take(1500))
            .setPositiveButton(R.string.share) { _, _ ->
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "LiteTube crash report")
                    .putExtra(Intent.EXTRA_TEXT, report)
                startActivity(Intent.createChooser(send, getString(R.string.share)))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

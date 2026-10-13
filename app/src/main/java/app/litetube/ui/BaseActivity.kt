package app.litetube.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import app.litetube.CrashLog
import app.litetube.R
import app.litetube.data.Prefs
import app.litetube.player.PlayerController

abstract class BaseActivity : AppCompatActivity() {

    private var appliedTheme: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedTheme = Prefs.theme
        // Pure black backgrounds save power on AMOLED screens.
        if (appliedTheme == "black") theme.applyStyle(R.style.ThemeOverlay_LiteTube_Black, true)
        super.onCreate(savedInstanceState)
        // Resolve the status bar colour from the activity's (day/night aware) resources;
        // the window's theme value isn't always updated when the system switches to dark.
        window.statusBarColor = if (appliedTheme == "black") {
            android.graphics.Color.BLACK
        } else {
            androidx.core.content.ContextCompat.getColor(this, R.color.status_bar)
        }
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

    /** Every screen except Home gets a Home button in its toolbar. */
    protected open val showHomeAction = true

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (showHomeAction) {
            menu.add(Menu.NONE, R.id.action_home, Menu.NONE, R.string.home)
                .setIcon(R.drawable.ic_home)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_home) {
            goHome()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** Back to the feed. Unlike Back in the player, this keeps the current video playing. */
    fun goHome() {
        PlayerController.closingToHome = true
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
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

package app.litetube.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.litetube.ui.PlayerActivity

/**
 * Background playback: shows the media notification, lock-screen controls and handles
 * headphone/Bluetooth buttons. Media3 runs it in the foreground while something plays.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val openPlayer = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_RESUME, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val immutable = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openPlayer, immutable or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val s = MediaSession.Builder(this, PlayerController.player)
            .setSessionActivity(pendingIntent)
            .build()
        session = s
        addSession(s)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        // The player itself is shared and outlives the service; only the session is released.
        session?.release()
        session = null
        super.onDestroy()
    }
}

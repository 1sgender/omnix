package com.omnix.assistant.agent.media.ymusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.omnix.assistant.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground-сервис плеера Яндекс Музыки: уведомление с кнопками
 * «назад / пауза / дальше / стоп». Сама музыка играет в [YandexMusicPlayer];
 * сервис держит процесс живым и рисует уведомление по состоянию плеера.
 */
@AndroidEntryPoint
class YandexMusicPlaybackService : Service() {

    @Inject lateinit var player: YandexMusicPlayer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        // startForeground сразу: система требует этого в течение нескольких секунд после старта.
        startForeground(NOTIFICATION_ID, buildNotification(player.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        when (intent?.action) {
            ACTION_TOGGLE -> scope.launch { player.togglePlayPause() }
            ACTION_NEXT -> scope.launch { player.next() }
            ACTION_PREVIOUS -> scope.launch { player.previous() }
            ACTION_STOP -> scope.launch { player.stop() }
        }
        if (watcher == null) {
            watcher = scope.launch {
                player.state.collect { state ->
                    if (!state.active) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java)
                            ?.notify(NOTIFICATION_ID, buildNotification(state))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.ymusic_notif_channel), NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(state: YmPlayerState): Notification {
        val track = state.track
        val open = PendingIntent.getActivity(
            this, 0, packageManager.getLaunchIntentForPackage(packageName) ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggleLabel = getString(if (state.playing) R.string.ymusic_action_pause else R.string.ymusic_action_play)
        val toggleIcon = if (state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(track?.title ?: getString(R.string.ymusic_notif_loading))
            .setContentText(if (state.loading && track != null) getString(R.string.ymusic_notif_loading) else track?.artistLine)
            .setContentIntent(open)
            .setOngoing(state.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, getString(R.string.ymusic_action_previous), ACTION_PREVIOUS))
            .addAction(action(toggleIcon, toggleLabel, ACTION_TOGGLE))
            .addAction(action(android.R.drawable.ic_media_next, getString(R.string.ymusic_action_next), ACTION_NEXT))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.ymusic_action_stop), ACTION_STOP))
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        player.sessionToken?.let { style.setMediaSession(it) }
        return builder.setStyle(style).build()
    }

    private fun action(icon: Int, title: String, action: String): Notification.Action {
        val pending = PendingIntent.getService(
            this, action.hashCode(), Intent(this, YandexMusicPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon), title, pending
        ).build()
    }

    companion object {
        private const val CHANNEL_ID = "omnix_music"
        private const val NOTIFICATION_ID = 7420
        const val ACTION_TOGGLE = "com.omnix.assistant.ymusic.TOGGLE"
        const val ACTION_NEXT = "com.omnix.assistant.ymusic.NEXT"
        const val ACTION_PREVIOUS = "com.omnix.assistant.ymusic.PREVIOUS"
        const val ACTION_STOP = "com.omnix.assistant.ymusic.STOP"
    }
}

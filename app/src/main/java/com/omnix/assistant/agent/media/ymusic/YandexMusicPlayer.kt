package com.omnix.assistant.agent.media.ymusic

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

sealed interface YmPlayResult {
    data class Started(val track: YmTrack) : YmPlayResult
    data class Failed(val failure: MusicFailure) : YmPlayResult
}

data class YmPlayerState(
    val active: Boolean = false,
    val loading: Boolean = false,
    val playing: Boolean = false,
    val track: YmTrack? = null
)

/**
 * Плеер внутри OMNIX: очередь из Яндекс Музыки, MediaPlayer, системная MediaSession
 * (поэтому медиа-клавиши, наушники и голосовые «пауза / дальше» доходят сюда сами)
 * и foreground-сервис с уведомлением ([YandexMusicPlaybackService]).
 * Все операции выполняются на главном потоке.
 */
@Singleton
class YandexMusicPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: YandexMusicClient,
    private val tokens: YandexMusicTokenStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(YmPlayerState())
    val state: StateFlow<YmPlayerState> = _state.asStateFlow()
    val isActive: Boolean get() = _state.value.active

    private var queue: List<YmTrack> = emptyList()
    private var index = 0
    private var mp: MediaPlayer? = null
    private var session: MediaSession? = null
    private var focus: AudioFocusRequest? = null
    private var resumeOnGain = false
    private var generation = 0

    val sessionToken: MediaSession.Token? get() = session?.sessionToken

    suspend fun play(tracks: List<YmTrack>): YmPlayResult = withContext(Dispatchers.Main.immediate) {
        queue = tracks
        index = 0
        ensureSession().isActive = true
        _state.value = YmPlayerState(active = true, loading = true, track = tracks.firstOrNull())
        startService()
        startAt(0)
    }

    suspend fun resume(): Boolean = withContext(Dispatchers.Main.immediate) {
        val p = mp ?: return@withContext false
        if (!isActive) return@withContext false
        if (!requestFocus()) return@withContext false
        runCatching { p.start() }.isSuccess.also { ok -> if (ok) publish(playing = true) }
    }

    suspend fun pause(): Boolean = withContext(Dispatchers.Main.immediate) {
        val p = mp ?: return@withContext false
        resumeOnGain = false
        runCatching { p.pause() }.isSuccess.also { ok -> if (ok) publish(playing = false) }
    }

    suspend fun togglePlayPause(): Boolean =
        if (_state.value.playing) pause() else resume()

    suspend fun next(): YmPlayResult? = withContext(Dispatchers.Main.immediate) {
        if (!isActive) return@withContext null
        if (index + 1 >= queue.size) {
            stop()
            return@withContext null
        }
        startAt(index + 1)
    }

    suspend fun previous(): YmPlayResult? = withContext(Dispatchers.Main.immediate) {
        if (!isActive) return@withContext null
        startAt(if (index > 0) index - 1 else 0)
    }

    suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        generation++
        releasePlayer()
        abandonFocus()
        session?.run { isActive = false; release() }
        session = null
        queue = emptyList()
        _state.value = YmPlayerState()
    }

    // ------------------------------------------------------------------ внутреннее

    /** Пробует трек [from], затем следующие: ссылка может не отдаться (регион, право, шифрование). */
    private suspend fun startAt(from: Int): YmPlayResult {
        val token = tokens.token()
        if (token == null) {
            stop()
            return YmPlayResult.Failed(MusicFailure.UNAUTHORIZED)
        }
        val myGeneration = ++generation
        var lastFailure = MusicFailure.UNAVAILABLE
        var i = from
        var attempts = 0
        while (i < queue.size && attempts < MAX_ATTEMPTS) {
            val track = queue[i]
            index = i
            _state.value = _state.value.copy(active = true, loading = true, track = track)
            when (val stream = client.resolve(track.id, token)) {
                is YmResult.Ok -> {
                    if (myGeneration != generation) return YmPlayResult.Failed(MusicFailure.UNAVAILABLE)
                    if (prepareAndStart(stream.value.url)) {
                        publishMetadata(track)
                        publish(playing = true, loading = false)
                        return YmPlayResult.Started(track)
                    }
                }
                YmResult.Unauthorized -> { stop(); return YmPlayResult.Failed(MusicFailure.UNAUTHORIZED) }
                YmResult.NoResults -> lastFailure = MusicFailure.NO_RESULTS
                YmResult.Unavailable -> lastFailure = MusicFailure.UNAVAILABLE
            }
            i++
            attempts++
        }
        if (myGeneration == generation) stop()
        return YmPlayResult.Failed(lastFailure)
    }

    private suspend fun prepareAndStart(url: String): Boolean {
        releasePlayer()
        if (!requestFocus()) return false
        val player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
        }
        val ready = withTimeoutOrNull(PREPARE_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                player.setOnPreparedListener { if (cont.isActive) cont.resume(true) }
                player.setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(false); true }
                try {
                    player.setDataSource(url)
                    player.prepareAsync()
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(false)
                }
                cont.invokeOnCancellation { runCatching { player.release() } }
            }
        } ?: false
        if (!ready) {
            runCatching { player.release() }
            return false
        }
        player.setOnErrorListener { _, _, _ -> scope.launch { next() }; true }
        player.setOnCompletionListener { scope.launch { next() } }
        mp = player
        return runCatching { player.start() }.isSuccess
    }

    private fun releasePlayer() {
        mp?.let { p ->
            p.setOnCompletionListener(null)
            p.setOnErrorListener(null)
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        mp = null
    }

    private fun ensureSession(): MediaSession {
        session?.let { return it }
        return MediaSession(context, "OmnixYandexMusic").also { s ->
            s.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { scope.launch { resume() } }
                override fun onPause() { scope.launch { pause() } }
                override fun onSkipToNext() { scope.launch { next() } }
                override fun onSkipToPrevious() { scope.launch { previous() } }
                override fun onStop() { scope.launch { stop() } }
            })
            session = s
        }
    }

    private fun publishMetadata(track: YmTrack) {
        session?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artistLine)
                .apply { track.durationMs?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) } }
                .build()
        )
    }

    private fun publish(playing: Boolean, loading: Boolean = false) {
        _state.value = _state.value.copy(playing = playing, loading = loading)
        val code = when {
            loading -> PlaybackState.STATE_BUFFERING
            playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        val position = runCatching { mp?.currentPosition?.toLong() }.getOrNull() ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
                )
                .setState(code, position, if (playing) 1f else 0f)
                .build()
        )
    }

    private fun requestFocus(): Boolean {
        val request = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                scope.launch {
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS -> pause()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            if (_state.value.playing) { pause(); resumeOnGain = true }
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mp?.setVolume(DUCK_VOLUME, DUCK_VOLUME)
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            mp?.setVolume(1f, 1f)
                            if (resumeOnGain) { resumeOnGain = false; resume() }
                        }
                    }
                }
            }
            .build()
            .also { focus = it }
        return audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    /** Из фона запуск foreground-сервиса может быть запрещён системой: тогда музыка играет без уведомления. */
    private fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, YandexMusicPlaybackService::class.java))
        } catch (_: IllegalStateException) {
            // нет права стартовать foreground-сервис из фона
        } catch (_: SecurityException) {
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val PREPARE_TIMEOUT_MS = 20_000L
        const val DUCK_VOLUME = 0.2f
    }
}

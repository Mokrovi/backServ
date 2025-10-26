package com.example.backgroundservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.example.backgroundservice.NetworkSignalService.Companion.isStreamActivityRunning
import kotlinx.coroutines.delay

const val ACTION_UPDATE_STREAM_URL = "com.example.backgroundservice.ACTION_UPDATE_STREAM_URL"

class CameraStreamActivity : ComponentActivity() {
    private val TAG = "CameraStreamActivity"
    private var isReceiverRegistered = false
    private var isStreamEnded = false

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_CLOSE_CAMERA_STREAM -> {
                    Log.d(TAG, "Received close broadcast")
                    finish()
                }
                ACTION_UPDATE_STREAM_URL -> {
                    Log.d(TAG, "Received update stream URL broadcast")
                    val externalUrl = intent.getStringExtra("EXTERNAL_STREAM_URL")
                    val localUrl = intent.getStringExtra("LOCAL_STREAM_URL")
                    // Здесь можно обновить URL потока если нужно
                }
            }
        }
    }

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Устанавливаем флаги для отображения поверх блокировки экрана
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Добавляем флаги для окна
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )

        // Регистрируем broadcast receiver
        val intentFilter = IntentFilter().apply {
            addAction(ACTION_CLOSE_CAMERA_STREAM)
            addAction(ACTION_UPDATE_STREAM_URL)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(closeReceiver, intentFilter)
        }
        isReceiverRegistered = true
        isStreamActivityRunning = true

        // Запускаем сервис
        val serviceIntent = Intent(this, NetworkSignalService::class.java)
        startService(serviceIntent)

        handleIntent(intent)
    }

    @UnstableApi
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent called with new stream data")
        setIntent(intent)
        handleIntent(intent)
    }

    @UnstableApi
    private fun handleIntent(intent: Intent?) {
        val externalStreamUrl = intent?.getStringExtra("EXTERNAL_STREAM_URL")
        val localStreamUrl = intent?.getStringExtra("LOCAL_STREAM_URL")
        val singleStreamUrl = intent?.getStringExtra("STREAM_URL")

        val primaryUrl = externalStreamUrl ?: singleStreamUrl

        if (primaryUrl == null && localStreamUrl == null) {
            Log.w(TAG, "No stream URLs provided, finishing activity")
            finish()
            return
        }

        Log.d(TAG, "Starting stream with primary URL: $primaryUrl, fallback URL: $localStreamUrl")

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Spacer(Modifier.weight(3f))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp)
                                .aspectRatio(16 / 9f)
                                .clip(RoundedCornerShape(16.dp))
                        ) {
                            CameraStreamPlayer(
                                primaryUrl,
                                localStreamUrl,
                                onPlaybackEnded = {
                                    Log.d(TAG, "Playback ended, finishing activity")
                                    isStreamEnded = true
                                    finish()
                                },
                                onPlaybackError = {
                                    Log.d(TAG, "Playback error, finishing activity")
                                    isStreamEnded = true
                                    // Даем время увидеть ошибку перед закрытием
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        finish()
                                    }, 3000)
                                }
                            )
                        }
                        Spacer(Modifier.weight(2f))
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isReceiverRegistered) {
            unregisterReceiver(closeReceiver)
        }
        isStreamActivityRunning = false
    }


}

@UnstableApi
@Composable
fun CameraStreamPlayer(
    primaryUrl: String?,
    fallbackUrl: String?,
    onPlaybackEnded: () -> Unit,
    onPlaybackError: () -> Unit
) {
    val context = LocalContext.current
    val exoPlayer = remember {
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setMaxVideoSizeSd())
        }
        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .build()
    }

    var connectionState by remember { mutableStateOf("Инициализация плеера...") }
    var isConnecting by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf<String?>(null) }
    var shouldSwitchToFallback by remember { mutableStateOf(false) }
    var shouldCloseActivity by remember { mutableStateOf(false) }

    // Обработка переключения на резервный поток
    LaunchedEffect(shouldSwitchToFallback) {
        if (shouldSwitchToFallback && fallbackUrl != null) {
            delay(1000)
            val fallbackMediaItem = MediaItem.Builder()
                .setUri(fallbackUrl)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setMaxPlaybackSpeed(1.02f)
                        .build()
                )
                .build()
            val fallbackMediaSource = RtspMediaSource.Factory()
                .setTimeoutMs(10000L)
                .createMediaSource(fallbackMediaItem)

            exoPlayer.setMediaSource(fallbackMediaSource)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            shouldSwitchToFallback = false
        }
    }

    // Обработка закрытия активности
    LaunchedEffect(shouldCloseActivity) {
        if (shouldCloseActivity) {
            delay(3000)
            onPlaybackError()
        }
    }

    DisposableEffect(key1 = exoPlayer, key2 = primaryUrl, key3 = fallbackUrl) {
        val listener = object : Player.Listener {
            private var isUsingPrimaryUrl = primaryUrl != null
            private var errorCount = 0
            private val maxErrorCount = 3

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_IDLE -> {
                        connectionState = "Плеер в режиме ожидания"
                        isConnecting = false
                    }
                    Player.STATE_BUFFERING -> {
                        connectionState = "Буферизация видео..."
                        isConnecting = true
                        hasError = false
                    }
                    Player.STATE_READY -> {
                        connectionState = if (isUsingPrimaryUrl) {
                            "✓ Подключено к основному потоку"
                        } else {
                            "✓ Подключено к резервному потоку"
                        }
                        isConnecting = false
                        hasError = false
                    }
                    Player.STATE_ENDED -> {
                        connectionState = "Воспроизведение завершено"
                        isConnecting = false
                        onPlaybackEnded()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                errorCount++
                val errorMessage = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Ошибка сети: невозможно подключиться"
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Таймаут подключения"
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Ошибка HTTP: ${error.message}"
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Поток не найден"
                    PlaybackException.ERROR_CODE_DECODING_FAILED -> "Ошибка декодирования видео"
                    PlaybackException.ERROR_CODE_UNSPECIFIED -> "Неизвестная ошибка воспроизведения"
                    else -> "Ошибка: ${error.errorCode} - ${error.message}"
                }

                if (errorCount >= maxErrorCount) {
                    connectionState = "❌ Критическая ошибка: превышено количество попыток подключения"
                    hasError = true
                    isConnecting = false
                    shouldCloseActivity = true
                    return
                }

                if (isUsingPrimaryUrl && fallbackUrl != null) {
                    connectionState = "❌ Ошибка основного потока: $errorMessage\nПопытка подключения к резервному потоку..."
                    isUsingPrimaryUrl = false
                    currentUrl = fallbackUrl
                    shouldSwitchToFallback = true
                } else if (fallbackUrl != null) {
                    shouldSwitchToFallback = true
                }
            }

            override fun onIsLoadingChanged(isLoading: Boolean) {
                if (isLoading) {
                    connectionState = "Загрузка видео данных..."
                }
            }
        }
        exoPlayer.addListener(listener)

        val initialUrl = primaryUrl ?: fallbackUrl
        currentUrl = initialUrl

        if (initialUrl != null) {
            connectionState = if (primaryUrl != null) {
                "Подключаюсь к основному потоку..."
            } else {
                "Подключаюсь к резервному потоку..."
            }

            val mediaItem = MediaItem.Builder()
                .setUri(initialUrl)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setMaxPlaybackSpeed(1.02f)
                        .build()
                )
                .build()

            val mediaSource = RtspMediaSource.Factory()
                .setTimeoutMs(15000L)
                .createMediaSource(mediaItem)

            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } else {
            connectionState = "❌ Ошибка: URL потока не указан"
            hasError = true
            shouldCloseActivity = true
        }

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay с информацией о подключении
        if (isConnecting || hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White
                        )
                    }

                    Text(
                        text = connectionState,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    currentUrl?.let { url ->
                        Text(
                            text = "URL: ${url.take(60)}${if (url.length > 60) "..." else ""}",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }
}
package com.example.backgroundservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
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

class CameraStreamActivity : ComponentActivity() {
    private val TAG = "CameraStreamActivity"
    private var isReceiverRegistered = false
    private var isStreamEnded = false

    companion object {
        var currentVideoName: String? = null
        var currentVolume: Float = 1.0f
    }

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )

        val intentFilter = IntentFilter().apply {
            addAction(ACTION_CLOSE_CAMERA_STREAM)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(broadcastReceiver, intentFilter)
        }
        isReceiverRegistered = true
        isStreamActivityRunning = true

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
        val isDebugMode = intent?.getBooleanExtra("DEBUG_MODE", false) ?: false

        // Автоматически запускаем первое видео при старте активности
        if (currentVideoName == null) {
            val videoFiles = getAvailableVideoFiles(this)
            if (videoFiles.isNotEmpty()) {
                currentVideoName = videoFiles.first()
                Log.d(TAG, "Auto-starting video: $currentVideoName")
            }
        }

        val primaryUrl = externalStreamUrl ?: singleStreamUrl

        if (primaryUrl == null && localStreamUrl == null) {
            Log.w(TAG, "No stream URLs provided, finishing activity")
            finishAndRemoveTask()
            return
        }

        Log.d(TAG, "Starting stream with primary URL: $primaryUrl, fallback URL: $localStreamUrl, Debug mode: $isDebugMode")

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .aspectRatio(16 / 9f)
                                .clip(RoundedCornerShape(16.dp))
                        ) {
                            CameraStreamPlayer(
                                primaryUrl,
                                localStreamUrl,
                                isDebugMode = isDebugMode,
                                onPlaybackEnded = {
                                    Log.d(TAG, "Playback ended, finishing activity")
                                    isStreamEnded = true
                                    finishAndRemoveTask()
                                },
                                onPlaybackError = {
                                    Log.d(TAG, "Playback error, finishing activity")
                                    isStreamEnded = true
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        finishAndRemoveTask()
                                    }, 3000)
                                }
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .aspectRatio(16 / 9f)
                                .clip(RoundedCornerShape(16.dp))
                        ) {
                            AnimationPlayer(
                                isDebugMode = isDebugMode
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isReceiverRegistered) {
            unregisterReceiver(broadcastReceiver)
        }
        isStreamActivityRunning = false

        if (isStreamEnded) {
            Log.d(TAG, "Stream ended, returning to previous app")
            val homeIntent = Intent(Intent.ACTION_MAIN)
            homeIntent.addCategory(Intent.CATEGORY_HOME)
            homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(homeIntent)
        }
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_CLOSE_CAMERA_STREAM -> {
                    Log.d(TAG, "Received close broadcast")
                    finishAndRemoveTask()
                }
            }
        }
    }
}

@UnstableApi
@Composable
fun AnimationPlayer(
    isDebugMode: Boolean
) {
    val context = LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }

    var currentVideoName by remember { mutableStateOf(CameraStreamActivity.currentVideoName) }
    var currentVolume by remember { mutableStateOf(CameraStreamActivity.currentVolume) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPlayingVideo by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            if (CameraStreamActivity.currentVideoName != currentVideoName) {
                currentVideoName = CameraStreamActivity.currentVideoName
            }
            if (CameraStreamActivity.currentVolume != currentVolume) {
                currentVolume = CameraStreamActivity.currentVolume
            }
        }
    }

    LaunchedEffect(currentVideoName) {
        if (currentVideoName != null && currentVideoName != currentPlayingVideo) {
            try {
                errorMessage = null
                currentPlayingVideo = currentVideoName

                Log.d("AnimationPlayer", "Trying to play: $currentVideoName")

                val videoFile = findVideoFile(context, currentVideoName!!)
                if (videoFile != null && videoFile.exists()) {
                    Log.d("AnimationPlayer", "File found: ${videoFile.absolutePath}")
                    val mediaItem = MediaItem.fromUri(Uri.fromFile(videoFile))
                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.repeatMode = Player.REPEAT_MODE_ALL // Включить повтор
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                    exoPlayer.volume = currentVolume
                    isPlaying = true
                    Log.d("AnimationPlayer", "Started playing: $currentVideoName with repeat mode")
                } else {
                    errorMessage = "Файл не найден: $currentVideoName"
                    Log.e("AnimationPlayer", "File not found: $currentVideoName")
                }
            } catch (e: Exception) {
                errorMessage = "Ошибка воспроизведения: ${e.message}"
                Log.e("AnimationPlayer", "Error playing video", e)
            }
        } else if (currentVideoName == null && currentPlayingVideo != null) {
            exoPlayer.stop()
            isPlaying = false
            currentPlayingVideo = null
            errorMessage = null
            Log.d("AnimationPlayer", "Playback stopped")
        }
    }

    LaunchedEffect(currentVolume) {
        exoPlayer.volume = currentVolume
        Log.d("AnimationPlayer", "Volume set to: $currentVolume")
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (currentVideoName != null && isPlaying) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false // Скрыть контролы управления
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage!!,
                            color = Color.Red,
                            textAlign = TextAlign.Center
                        )
                    } else if (currentVideoName != null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = Color.White
                        )
                        Text(
                            text = "Загрузка: $currentVideoName",
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            text = "Анимация будет отображаться здесь\n\nВыберите видео в веб-интерфейсе",
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        if (isDebugMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                contentAlignment = Alignment.TopStart
            ) {
                Text(
                    text = "Анимация: ${currentVideoName ?: "нет"}\nГромкость: ${(currentVolume * 100).toInt()}%\nСтатус: ${if (isPlaying) "playing" else "stopped"}",
                    color = Color.Yellow,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun findVideoFile(context: Context, fileName: String): java.io.File? {
    val directories = arrayOf(
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES),
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM),
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
        android.os.Environment.getExternalStorageDirectory()
    )

    for (directory in directories) {
        if (directory.exists() && directory.isDirectory) {
            val file = java.io.File(directory, fileName)
            if (file.exists()) {
                return file
            }
            val files = directory.listFiles()
            if (files != null) {
                for (f in files) {
                    if (f.isDirectory) {
                        val subFile = java.io.File(f, fileName)
                        if (subFile.exists()) {
                            return subFile
                        }
                    }
                }
            }
        }
    }
    return null
}

private fun getAvailableVideoFiles(context: Context): List<String> {
    val videoFiles = mutableListOf<String>()

    try {
        val directories = arrayOf(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
            android.os.Environment.getExternalStorageDirectory()
        )

        val videoExtensions = arrayOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "3gp", "webm", "m4v")

        for (directory in directories) {
            if (directory.exists() && directory.isDirectory) {
                scanDirectoryForVideos(directory, videoFiles, videoExtensions, 0)
            }
        }

        // Сортируем файлы по имени для удобства
        videoFiles.sort()

        Log.d("VideoSearch", "Found ${videoFiles.size} video files: ${videoFiles.joinToString(", ")}")

    } catch (e: Exception) {
        Log.e("VideoSearch", "Error getting video files", e)
    }

    return videoFiles
}

private fun scanDirectoryForVideos(
    directory: java.io.File,
    videoFiles: MutableList<String>,
    extensions: Array<String>,
    depth: Int
) {
    try {
        // Ограничиваем глубину рекурсии для избежания бесконечных циклов
        if (depth > 5) return

        val files = directory.listFiles() ?: return

        for (file in files) {
            if (file.isDirectory) {
                // Игнорируем некоторые системные папки
                if (!file.name.startsWith(".") &&
                    !file.name.equals("Android", true) &&
                    !file.name.equals("lost+found", true)) {
                    scanDirectoryForVideos(file, videoFiles, extensions, depth + 1)
                }
            } else if (file.isFile) {
                val fileName = file.name.toLowerCase()
                val extension = fileName.substringAfterLast('.', "")
                if (extensions.contains(extension)) {
                    videoFiles.add(file.name)
                    Log.d("VideoSearch", "Found video: ${file.absolutePath}")
                }
            }
        }
    } catch (e: Exception) {
        Log.e("VideoSearch", "Error scanning directory ${directory.absolutePath}", e)
    }
}

@UnstableApi
@Composable
fun CameraStreamPlayer(
    primaryUrl: String?,
    fallbackUrl: String?,
    isDebugMode: Boolean,
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
                        errorCount = 0
                    }
                    Player.STATE_READY -> {
                        connectionState = if (isUsingPrimaryUrl) {
                            "✓ Подключено к основному потоку"
                        } else {
                            "✓ Подключено к резервному потоку"
                        }
                        isConnecting = false
                        hasError = false
                        errorCount = 0
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

        if (isConnecting || hasError || isDebugMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = if (isDebugMode) Alignment.TopStart else Alignment.TopCenter
            ) {
                Column(
                    horizontalAlignment = if (isDebugMode) Alignment.Start else Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isConnecting && !isDebugMode) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White
                        )
                    }

                    if (!isDebugMode && (isConnecting || hasError)) {
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
}
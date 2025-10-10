package com.example.backgroundservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*

class RemoteCameraViewActivity : AppCompatActivity() {

    private val TAG = "RemoteCameraView"
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var statusTextView: TextView
    private var streamStatusJob: Job? = null

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CLOSE_REMOTE_CAMERA_VIEW) {
                Log.d(TAG, "Received close broadcast. Finishing Activity.")
                finishWithCleanup()
            }
        }
    }

    private fun finishWithCleanup() {
        NetworkSignalService.isRemoteCameraActivityRunning = false
        streamStatusJob?.cancel()
        releasePlayer()
        finish()
    }


    private fun startStreamStatusChecker() {
        streamStatusJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(5000) // Проверяем каждые 5 секунд

                if (!isStreamActiveOnPC()) {
                    Log.d(TAG, "RTSP поток прекратился на ПК, возвращаемся")
                    withContext(Dispatchers.Main) {
                        statusTextView.text = "Стрим остановлен. Возврат..."
                        finishWithCleanup()
                    }
                    break
                }
            }
        }
    }


    private fun isStreamActiveOnPC(): Boolean {
        return try {
            val remoteIpAddress = intent.getStringExtra("REMOTE_IP_ADDRESS") ?: return false
            val statusUrl = "http://$remoteIpAddress:8080/status"

            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = okhttp3.Request.Builder()
                .url(statusUrl)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val jsonObject = org.json.JSONObject(responseBody)
                val isStreaming = jsonObject.optBoolean("streaming", false)
                val isRtspActive = jsonObject.optBoolean("rtsp_stream_active", false)

                Log.d(TAG, "PC status - streaming: $isStreaming, rtsp_active: $isRtspActive")
                isStreaming && isRtspActive
            } else {
                Log.d(TAG, "Failed to get PC status: ${response.code}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при проверке статуса ПК: ${e.message}")
            false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_camera_view)
        Log.d(TAG, "onCreate called.")

        playerView = findViewById(R.id.player_view)
        statusTextView = findViewById(R.id.status_textview)

        NetworkSignalService.isRemoteCameraActivityRunning = true

        val remoteIpAddress = intent.getStringExtra("REMOTE_IP_ADDRESS")

        if (remoteIpAddress != null) {
            Log.d(TAG, "Received IP Address: $remoteIpAddress")
            statusTextView.text = "Подключение к: $remoteIpAddress"

            initializePlayer()
            startStreamStatusChecker() // Запускаем проверку статуса

        } else {
            Log.e(TAG, "No IP Address received in Intent. Finishing activity.")
            statusTextView.text = "Ошибка: IP адрес не получен"
            finishWithCleanup()
        }

        val intentFilter = IntentFilter(ACTION_CLOSE_REMOTE_CAMERA_VIEW)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(closeReceiver, intentFilter)
        }
        Log.d(TAG, "Close broadcast receiver registered.")
    }

    // Define the listener as a property to be able to remove it
    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error: ${error.message}", error)
            statusTextView.text = "Player error: ${error.message}"
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateString = when (playbackState) {
                Player.STATE_IDLE -> "Player.STATE_IDLE"
                Player.STATE_BUFFERING -> "Player.STATE_BUFFERING"
                Player.STATE_READY -> "Player.STATE_READY - Playback should start"
                Player.STATE_ENDED -> "Player.STATE_ENDED"
                else -> "UNKNOWN_STATE"
            }
            Log.d(TAG, "onPlaybackStateChanged: $stateString")
            if (playbackState == Player.STATE_READY) {
                statusTextView.text = "Streaming started successfully"
            } else if (playbackState == Player.STATE_BUFFERING) {
                 statusTextView.text = "Buffering..."
            }
        }
    }

    private fun initializePlayer() {
        val remoteIpAddress = intent.getStringExtra("REMOTE_IP_ADDRESS")
        if (remoteIpAddress == null) {
            Log.e(TAG, "Cannot initialize player, IP address is null.")
            statusTextView.text = "Error: IP Address missing for player."
            return
        }

        val streamUrl = "rtsp://$remoteIpAddress:8554/live/stream"
        Log.d(TAG, "Initializing player for URL: $streamUrl")

        try {
            player = ExoPlayer.Builder(this).build().apply {
                addListener(playerListener)
                val mediaItem = MediaItem.fromUri(streamUrl)
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }
            playerView.player = player

            statusTextView.text = "Initializing stream from: $streamUrl" // More accurate initial status
            Log.d(TAG, "ExoPlayer initialized, preparation started, playWhenReady set.")

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing ExoPlayer", e)
            statusTextView.text = "Error initializing player: ${e.message}"
        }
    }

    private fun releasePlayer() {
        player?.removeListener(playerListener) // Make sure to remove the listener
        player?.let {
            it.stop()
            it.release()
            player = null
            Log.d(TAG, "ExoPlayer released.")
        }
    }


    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT > 23) {
            if (player == null && intent.getStringExtra("REMOTE_IP_ADDRESS") != null) {
                initializePlayer()
            }
            playerView.onResume()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT <= 23 || player == null) {
            if (player == null && intent.getStringExtra("REMOTE_IP_ADDRESS") != null) {
                initializePlayer()
            }
        }
        playerView.onResume()
        player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
        playerView.onPause()
        if (isFinishing && Build.VERSION.SDK_INT <= 23) {
            releasePlayer()
        }
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT > 23) {
           releasePlayer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called.")
        NetworkSignalService.isRemoteCameraActivityRunning = false
        unregisterReceiver(closeReceiver)
        Log.d(TAG, "Close broadcast receiver unregistered.")
        releasePlayer()
    }
}

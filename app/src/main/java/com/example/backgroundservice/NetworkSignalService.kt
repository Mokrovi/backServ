package com.example.backgroundservice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

// Action for the broadcast to close the CameraStreamActivity
const val ACTION_CLOSE_CAMERA_STREAM = "com.example.backgroundservice.ACTION_CLOSE_CAMERA_STREAM"
// Action for the broadcast to close the RemoteCameraViewActivity
const val ACTION_CLOSE_REMOTE_CAMERA_VIEW = "com.example.backgroundservice.ACTION_CLOSE_REMOTE_CAMERA_VIEW"

class NetworkSignalService : Service() {

    private val TAG = "NetworkSignalService"
    private var webServer: MyWebServer? = null
    private val PORT = 8080
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "NetworkSignalServiceChannel"

    companion object {
        var isStreamActivityRunning = false
        var isRemoteCameraActivityRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service creating.")

        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        webServer = MyWebServer(PORT)
        try {
            webServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            val ipAddress = getLocalIpAddress()
            Log.d(TAG, "NanoHTTPD server started on: http://$ipAddress:$PORT")
        } catch (e: IOException) {
            Log.e(TAG, "Error starting NanoHTTPD server", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Network Signal Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Network Signal Service")
            .setContentText("Service is running in the background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    inner class MyWebServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            Log.d(TAG, "Received request: ${session.method} $uri from ${session.remoteIpAddress}")

            if (Method.GET.equals(session.method) && "/" == uri) {
                if (!isStreamActivityRunning) {
                    Log.d(TAG, "Starting CameraStreamActivity.")
                    val intent = Intent(this@NetworkSignalService, CameraStreamActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }
                return newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, "Stream started.").apply {
                    addHeader("Access-Control-Allow-Origin", "*")
                }
            }
            return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found").apply {
                addHeader("Access-Control-Allow-Origin", "*")
            }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces: List<NetworkInterface> = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addresses: List<java.net.InetAddress> = Collections.list(intf.inetAddresses)
                for (addr in addresses) {
                    if (addr is Inet4Address) {
                        return addr.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address", e)
        }
        return "0.0.0.0"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started.")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroying.")
        webServer?.stop()
        Log.d(TAG, "NanoHTTPD server stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}

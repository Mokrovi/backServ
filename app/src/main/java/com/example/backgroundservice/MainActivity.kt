package com.example.backgroundservice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Запрашиваем разрешения при запуске
        requestStoragePermission()

        setContent {
            MainScreen(
                startService = ::startNetworkService,
                sharedPreferences = sharedPreferences
            )
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_VIDEO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(permission), 123)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 123) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                android.util.Log.d("MainActivity", "Storage permission granted")
            } else {
                android.util.Log.w("MainActivity", "Storage permission denied")
            }
        }
    }

    private fun startNetworkService() {
        if (!NetworkSignalService.isServiceRunning) {
            val serviceIntent = Intent(this, NetworkSignalService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }
}

@Composable
fun MainScreen(
    startService: () -> Unit,
    sharedPreferences: SharedPreferences
) {
    val context = LocalContext.current

    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    var hasSystemAlertWindowPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        )
    }

    var hasStoragePermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    var isDebugMode by remember {
        mutableStateOf(sharedPreferences.getBoolean("debug_mode", false))
    }

    val isServiceRunning by produceState(initialValue = NetworkSignalService.isServiceRunning) {
        while (true) {
            value = NetworkSignalService.isServiceRunning
            delay(1000)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasNotificationPermission = isGranted
        }
    )

    val systemAlertWindowPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasSystemAlertWindowPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasStoragePermission = isGranted
        }
    )

    // Запрашиваем разрешение на доступ к хранилищу при необходимости
    LaunchedEffect(hasStoragePermission) {
        if (!hasStoragePermission) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                storagePermissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    LaunchedEffect(hasNotificationPermission, hasSystemAlertWindowPermission, hasStoragePermission) {
        if (hasNotificationPermission && hasSystemAlertWindowPermission && hasStoragePermission) {
            startService()
        }
    }

    LaunchedEffect(isDebugMode) {
        sharedPreferences.edit().putBoolean("debug_mode", isDebugMode).apply()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.DarkGray) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                !hasStoragePermission -> {
                    Text(
                        text = "Приложению нужен доступ к хранилищу для поиска видеофайлов.",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp),
                        color = Color.White
                    )
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            storagePermissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO)
                        } else {
                            storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    }) {
                        Text("Разрешить доступ к хранилищу")
                    }
                }

                !hasNotificationPermission -> {
                    Text(
                        text = "Приложению нужны разрешения для уведомлений о входящих потоках.",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp),
                        color = Color.White
                    )
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }) {
                        Text("Разрешить уведомления")
                    }
                }

                !hasSystemAlertWindowPermission -> {
                    Text(
                        text = "Приложению нужно разрешение на отображение поверх других приложений.",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp),
                        color = Color.White
                    )
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            systemAlertWindowPermissionLauncher.launch(intent)
                        }
                    }) {
                        Text("Разрешить отображение поверх")
                    }
                }

                else -> {
                    val serviceStatusText = if (isServiceRunning) {
                        "Фоновый сервис запущен. Приложение будет автоматически открываться при получении потока."
                    } else {
                        "Все разрешения получены. Запуск фонового сервиса..."
                    }
                    Text(
                        text = serviceStatusText,
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 16.dp)
                    ) {
                        Text(
                            text = "Режим отладки",
                            color = Color.White,
                            modifier = Modifier.padding(end = 16.dp)
                        )
                        Switch(
                            checked = isDebugMode,
                            onCheckedChange = { isDebugMode = it }
                        )
                    }

                    if (isDebugMode) {
                        Text(
                            text = "Режим отладки включен. При открытии потока вы увидите детальные логи подключения.",
                            textAlign = TextAlign.Center,
                            color = Color.Yellow,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
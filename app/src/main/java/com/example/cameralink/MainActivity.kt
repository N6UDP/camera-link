package com.example.cameralink

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import androidx.camera.lifecycle.ProcessCameraProvider
import com.example.cameralink.ui.theme.CameraLinkTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Load any user-configured Tailscale peers from persistent storage.
        TailscalePinger.init(this)
        // Load persisted camera/stream settings (lens, resolution, JPEG quality).
        CameraSettings.init(this)

        // The Tailscale keep-alive service is OFF by default and only starts if the
        // user has explicitly opted in. CameraLink works fully on a local network
        // without it.
        if (TailscalePrefs.isEnabled(this)) {
            TailscalePingService.start(this)
        }

        setContent {
            CameraLinkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CameraApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraApp() {
    val permissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.INTERNET
    )

    // Add notification permission for Android 13+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }

    val permissionsState = rememberMultiplePermissionsState(permissions)

    LaunchedEffect(Unit) {
        permissionsState.launchMultiplePermissionRequest()
    }

    when {
        permissionsState.allPermissionsGranted -> {
            ServiceControlScreen()
        }
        permissionsState.shouldShowRationale -> {
            PermissionRationale(
                onRequestPermission = {
                    permissionsState.launchMultiplePermissionRequest()
                }
            )
        }
        else -> {
            PermissionRequest(
                onRequestPermission = {
                    permissionsState.launchMultiplePermissionRequest()
                }
            )
        }
    }
}

@Composable
fun ServiceControlScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isStreaming by remember { mutableStateOf(false) }
    var ipAddresses by remember { mutableStateOf(getIpAddresses()) }
    val port = 8080

    // Auto-start the streaming service when the screen loads
    LaunchedEffect(Unit) {
        if (!isStreaming) {
            CameraStreamingService.startService(context, port)
            ipAddresses = getIpAddresses()
            isStreaming = true
        }
    }

    // Tailscale ping state
    var isPinging by remember { mutableStateOf(false) }
    var lastPingResults by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var lastPingTime by remember { mutableStateOf("") }

    // Tailscale opt-in state (off by default; local-network use needs no Tailscale)
    var tailscaleEnabled by remember { mutableStateOf(TailscalePrefs.isEnabled(context)) }

    // Tailscale peer management
    var showAddIpDialog by remember { mutableStateOf(false) }
    var newIpText by remember { mutableStateOf("") }
    var configuredIps by remember { mutableStateOf(TailscalePinger.getConfiguredIps().toList()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1a1a1a))
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "📹",
            style = MaterialTheme.typography.displayLarge,
            color = Color(0xFF4CAF50)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "CameraLink Background Streaming",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Camera Settings (lens / resolution / quality)
        CameraSettingsCard()

        Spacer(modifier = Modifier.height(16.dp))

        // Tailscale Ping Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2a2a2a))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "🔗 Tailscale Connections",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50)
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Enable keep-alive",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.LightGray
                    )
                    Switch(
                        checked = tailscaleEnabled,
                        onCheckedChange = { enabled ->
                            tailscaleEnabled = enabled
                            TailscalePrefs.setEnabled(context, enabled)
                            if (enabled) {
                                TailscalePingService.start(context)
                            } else {
                                TailscalePingService.stop(context)
                                lastPingResults = emptyMap()
                                lastPingTime = ""
                            }
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Off by default. CameraLink streams over your local network " +
                           "without Tailscale. Enable this only if you use Tailscale and " +
                           "want CameraLink to keep your own peers awake.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (!tailscaleEnabled) {
                    Text(
                        text = "Keep-alive disabled",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                } else if (lastPingResults.isEmpty()) {
                    Text(
                        text = "Auto-pinging every 15 seconds\nTap button below to ping manually",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                } else {
                    val successCount = lastPingResults.values.count { it }
                    val totalCount = lastPingResults.size

                    Text(
                        text = "Status: $successCount/$totalCount connections active",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (successCount == totalCount) Color(0xFF4CAF50) else Color.Yellow
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    lastPingResults.forEach { (ip, success) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = ip,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.LightGray
                            )
                            Text(
                                text = if (success) "✅" else "❌",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    if (lastPingTime.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Last ping: $lastPingTime",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        Log.d("MainActivity", "Ping Now button clicked")
                        coroutineScope.launch {
                            isPinging = true
                            Log.d("MainActivity", "Starting ping operation, isPinging=$isPinging")
                            try {
                                // Run ping on IO dispatcher
                                val results = withContext(Dispatchers.IO) {
                                    TailscalePinger.pingAllTailscaleConnections()
                                }
                                Log.d("MainActivity", "Ping results received: ${results.size} connections")

                                // Update UI on main thread
                                withContext(Dispatchers.Main) {
                                    lastPingResults = results
                                    lastPingTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                        .format(Date())
                                    Log.d("MainActivity", "UI updated with results")
                                }
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Error during ping", e)
                                withContext(Dispatchers.Main) {
                                    lastPingResults = emptyMap()
                                    lastPingTime = "Error: ${e.message}"
                                }
                            } finally {
                                isPinging = false
                                Log.d("MainActivity", "Ping operation complete, isPinging=$isPinging")
                            }
                        }
                    },
                    enabled = !isPinging && tailscaleEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Text(
                        text = if (isPinging) "Pinging..." else "🔄 Ping Now",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Tailscale Peer Management Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2a2a2a))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "⚙️ Manage Tailscale Peers",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50)
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (configuredIps.isEmpty()) {
                    Text(
                        text = "No Tailscale peers configured.\nAdd peer IPs below to ping them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        text = "Configured Peers (${configuredIps.size}):",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    configuredIps.forEach { ip ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = ip,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.LightGray
                            )
                            Button(
                                onClick = {
                                    TailscalePinger.removeTailscaleIp(ip)
                                    configuredIps = TailscalePinger.getConfiguredIps().toList()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Red
                                ),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Remove", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { showAddIpDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Text(
                        text = "➕ Add Peer",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Add IP Dialog
        if (showAddIpDialog) {
            AlertDialog(
                onDismissRequest = {
                    showAddIpDialog = false
                    newIpText = ""
                },
                title = { Text("Add Tailscale Peer") },
                text = {
                    Column {
                        Text(
                            text = "Enter a Tailscale IP or MagicDNS hostname:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Examples:\n• IP: 100.64.1.5\n• Hostname: laptop-abc123",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(
                            value = newIpText,
                            onValueChange = { newIpText = it },
                            label = { Text("IP or Hostname") },
                            placeholder = { Text("e.g., 100.64.1.5 or my-laptop") },
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newIpText.isNotBlank()) {
                                TailscalePinger.addTailscaleIp(newIpText.trim())
                                configuredIps = TailscalePinger.getConfiguredIps().toList()
                            }
                            showAddIpDialog = false
                            newIpText = ""
                        }
                    ) {
                        Text("Add")
                    }
                },
                dismissButton = {
                    Button(
                        onClick = {
                            showAddIpDialog = false
                            newIpText = ""
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isStreaming) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2a2a2a))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "🔴 STREAMING",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.Red
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (ipAddresses.size > 1) "Stream URLs:" else "Stream URL:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (ipAddresses.isEmpty()) {
                        Text(
                            text = "Unable to get IP",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50),
                            textAlign = TextAlign.Center
                        )
                    } else {
                        ipAddresses.forEach { ip ->
                            Text(
                                text = "http://$ip:$port",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF4CAF50),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "✅ Camera is streaming in the background\n" +
                               "✅ Works even when screen is off\n" +
                               "✅ Access from anywhere on your network",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.LightGray,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "You can close this app or turn off the screen.\n" +
                       "The camera will continue streaming.\n" +
                       "Check the notification for the stream URL.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        } else {
            Text(
                text = "Start the background streaming service to access your camera remotely. " +
                       "The stream will continue even when your screen is off.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.LightGray,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                if (isStreaming) {
                    CameraStreamingService.stopService(context)
                    isStreaming = false
                } else {
                    CameraStreamingService.startService(context, port)
                    ipAddresses = getIpAddresses()
                    isStreaming = true
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isStreaming) Color.Red else Color(0xFF4CAF50)
            )
        ) {
            Text(
                text = if (isStreaming) "⏹ Stop Streaming" else "▶ Start Streaming",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (isStreaming) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    ipAddresses = getIpAddresses()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2a2a2a)
                )
            ) {
                Text(
                    text = "🔄 Refresh IP Address",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

private fun getIpAddresses(): List<String> {
    val results = mutableListOf<String>()
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        for (intf in interfaces) {
            if (!intf.isUp || intf.isLoopback) continue
            val addrs = intf.inetAddresses
            for (addr in addrs) {
                if (!addr.isLoopbackAddress) {
                    val hostAddress = addr.hostAddress
                    if (hostAddress != null && hostAddress.indexOf(':') < 0) {
                        results.add(hostAddress)
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return results
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraSettingsCard() {
    val context = LocalContext.current

    var lens by remember { mutableStateOf(CameraSettings.lens) }
    var resolution by remember { mutableStateOf(CameraSettings.resolution) }
    var quality by remember { mutableStateOf(CameraSettings.jpegQuality.toFloat()) }
    var autoFlash by remember { mutableStateOf(CameraSettings.autoFlash) }
    var flashThreshold by remember { mutableStateOf(CameraSettings.autoFlashThreshold.toFloat()) }
    var flashStrength by remember { mutableStateOf(CameraSettings.flashStrengthPercent.toFloat()) }
    var flashAdjustable by remember { mutableStateOf(CameraSettings.maxTorchLevel > 1) }
    var accessKey by remember { mutableStateOf(CameraSettings.accessKey) }
    var availableLenses by remember { mutableStateOf(CameraLens.entries.toSet()) }
    var showPreview by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        availableLenses = try {
            withContext(Dispatchers.IO) {
                val provider = ProcessCameraProvider.getInstance(context).get()
                CameraLensResolver.availableLenses(context, provider)
            }
        } catch (e: Exception) {
            Log.w("CameraSettingsCard", "Could not query lenses: ${e.message}")
            CameraLens.entries.toSet()
        }
        // The service publishes the torch capability after it binds the camera.
        flashAdjustable = CameraSettings.maxTorchLevel > 1
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2a2a2a))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "📷 Camera Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4CAF50)
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = { showPreview = !showPreview },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (showPreview) "👁 Hide Preview" else "👁 Show Preview")
            }
            if (showPreview) {
                Spacer(modifier = Modifier.height(8.dp))
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewClient = WebViewClient()
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            setBackgroundColor(android.graphics.Color.BLACK)
                            // Render the local MJPEG stream via an <img> tag, which auto-updates
                            // as frames arrive and reflects the currently selected lens/resolution.
                            val html = "<html><body style=\"margin:0;background:#000;\">" +
                                "<img src=\"http://127.0.0.1:8080/stream\" " +
                                "style=\"width:100%;height:auto;display:block;\"/></body></html>"
                            loadDataWithBaseURL(
                                "http://127.0.0.1:8080/",
                                html,
                                "text/html",
                                "utf-8",
                                null
                            )
                        }
                    },
                    onRelease = { it.destroy() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Live preview of the local stream. Switch lenses below to compare.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            Text(text = "Lens", style = MaterialTheme.typography.bodyMedium, color = Color.LightGray)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CameraLens.entries.forEach { option ->
                    FilterChip(
                        selected = lens == option,
                        enabled = option in availableLenses,
                        onClick = {
                            lens = option
                            CameraSettings.setLens(option)
                        },
                        label = { Text(option.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(text = "Resolution", style = MaterialTheme.typography.bodyMedium, color = Color.LightGray)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StreamResolution.entries.forEach { option ->
                    FilterChip(
                        selected = resolution == option,
                        onClick = {
                            resolution = option
                            CameraSettings.setResolution(option)
                        },
                        label = { Text(option.id) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "JPEG quality: ${quality.toInt()}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.LightGray
            )
            Slider(
                value = quality,
                onValueChange = { quality = it },
                onValueChangeFinished = { CameraSettings.setJpegQuality(quality.toInt()) },
                valueRange = 10f..100f
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto flash in low light",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.LightGray
                    )
                    Text(
                        text = "Turns the torch on automatically when the scene is dark (back lens with flash only).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                Switch(
                    checked = autoFlash,
                    onCheckedChange = {
                        autoFlash = it
                        CameraSettings.setAutoFlash(it)
                    }
                )
            }

            if (autoFlash) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Low-light threshold: ${flashThreshold.toInt()} (higher = turns on sooner)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.LightGray
                )
                Slider(
                    value = flashThreshold,
                    onValueChange = { flashThreshold = it },
                    onValueChangeFinished = { CameraSettings.setAutoFlashThreshold(flashThreshold.toInt()) },
                    valueRange = CameraSettings.MIN_AUTO_FLASH_THRESHOLD.toFloat()..CameraSettings.MAX_AUTO_FLASH_THRESHOLD.toFloat()
                )
                Text(
                    text = "Tune this for your camera position - raise it if the torch stays off in dim light, lower it if it comes on too eagerly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                if (flashAdjustable) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Flash brightness: ${flashStrength.toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.LightGray
                    )
                    Slider(
                        value = flashStrength,
                        onValueChange = { flashStrength = it },
                        onValueChangeFinished = { CameraSettings.setFlashStrengthPercent(flashStrength.toInt()) },
                        valueRange = 1f..100f
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Access key (optional)",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.LightGray
            )
            OutlinedTextField(
                value = accessKey,
                onValueChange = {
                    accessKey = it
                    CameraSettings.setAccessKey(it)
                },
                singleLine = true,
                placeholder = { Text("Leave blank to allow any device on the LAN") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "When set, viewers must append ?key=<key> (or send an X-Access-Key header). " +
                    "The in-app preview and this device are always allowed. Sent in cleartext over HTTP - a LAN gate, not encryption.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Tip: append ?camera=telephoto&res=1080&q=70&autoflash=1 to the stream/snapshot URL to override.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun PermissionRequest(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1a1a1a))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "📹",
            style = MaterialTheme.typography.displayLarge,
            color = Color(0xFF4CAF50)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Camera Permission Required",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "CameraLink needs camera permission to stream video to other devices on your network.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.LightGray,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onRequestPermission,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF4CAF50)
            )
        ) {
            Text(
                text = "Grant Permission",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun PermissionRationale(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1a1a1a))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "⚠️",
            style = MaterialTheme.typography.displayLarge,
            color = Color(0xFFFFA726)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Permission Denied",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Camera permission is essential for this app to function. Please grant the permission to continue.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.LightGray,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onRequestPermission,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF4CAF50)
            )
        ) {
            Text(
                text = "Try Again",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
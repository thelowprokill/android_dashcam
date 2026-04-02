package com.jon_is_awesome.android_dashcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.jon_is_awesome.android_dashcam.ui.theme.AndroidDashcamTheme
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val viewModel: DashcamViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("Dashcam", "MainActivity onCreate")
        
        enableEdgeToEdge()
        
        setContent {
            AndroidDashcamTheme {
                val navController = rememberNavController()
                
                NavHost(navController = navController, startDestination = "dashcam") {
                    composable("dashcam") {
                        DashcamScreen(
                            viewModel = viewModel,
                            onNavigateToSettings = { navController.navigate("settings") }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DashcamScreen(
    viewModel: DashcamViewModel,
    onNavigateToSettings: () -> Unit
) {
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val isDualCameraSupported by viewModel.isDualCameraSupported.collectAsStateWithLifecycle()
    val useDualCamera by viewModel.useDualCamera.collectAsStateWithLifecycle()
    val telemetryData by viewModel.telemetryData.collectAsStateWithLifecycle()
    val isLocked by viewModel.isLocked.collectAsStateWithLifecycle()
    val totalDuration by viewModel.totalDurationMillis.collectAsStateWithLifecycle()
    val segmentDuration by viewModel.segmentDurationMillis.collectAsStateWithLifecycle()

    // UI Toggles from Settings
    val showSpeed by viewModel.showSpeed.collectAsStateWithLifecycle()
    val showCoordinates by viewModel.showCoordinates.collectAsStateWithLifecycle()
    val showAltitude by viewModel.showAltitude.collectAsStateWithLifecycle()
    val showTimestamp by viewModel.showTimestamp.collectAsStateWithLifecycle()
    val useMetric by viewModel.useMetric.collectAsStateWithLifecycle()

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted) {
            viewModel.checkDualCameraSupport(context)
            viewModel.startLocationUpdates(context)
        } else {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        "Android Dashcam", 
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    ) 
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.3f),
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (isRecording) {
                    FloatingActionButton(
                        onClick = { viewModel.onLockClick() },
                        containerColor = if (isLocked) Color.Yellow else MaterialTheme.colorScheme.tertiary,
                        contentColor = if (isLocked) Color.Black else MaterialTheme.colorScheme.onTertiary,
                        shape = CircleShape,
                        modifier = Modifier.padding(bottom = 16.dp).size(56.dp)
                    ) {
                        Icon(
                            imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = "Lock Recording"
                        )
                    }
                }
                
                FloatingActionButton(
                    onClick = { viewModel.onRecordClick() },
                    containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    contentColor = if (isRecording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape,
                    modifier = Modifier.size(80.dp)
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        contentDescription = if (isRecording) "Stop Recording" else "Start Recording",
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (permissionsState.allPermissionsGranted) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            viewModel.bindCamera(ctx, lifecycleOwner, this)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                
                // In-App Telemetry Overlay (Independent of recording overlay)
                TelemetryOverlay(
                    data = telemetryData,
                    showSpeed = showSpeed,
                    showCoordinates = showCoordinates,
                    showAltitude = showAltitude,
                    showTimestamp = showTimestamp,
                    useMetric = useMetric,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = 120.dp, start = 16.dp, end = 16.dp)
                )

                // Recording and Dual Camera Status
                Column(
                    modifier = Modifier
                        .padding(innerPadding)
                        .padding(16.dp)
                        .align(Alignment.TopStart)
                ) {
                    if (isRecording) {
                        Surface(
                            color = Color.Red.copy(alpha = 0.8f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(Color.White, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("RECORDING", color = Color.White, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Total: ${formatDuration(totalDuration)}",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    text = "Seg: ${formatDuration(segmentDuration)}",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    
                    if (isDualCameraSupported) {
                        Spacer(modifier = Modifier.height(12.dp))
                        FilledTonalButton(
                            onClick = { viewModel.toggleDualCamera(context) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Videocam, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (useDualCamera) "Dual Camera: ON" else "Dual Camera: OFF")
                        }
                    }
                }
                
                AnimatedVisibility(
                    visible = isLocked,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Surface(
                        color = Color.Yellow.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = Color.Black, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("SEGMENT LOCKED", style = MaterialTheme.typography.headlineSmall, color = Color.Black)
                        }
                    }
                }
            } else {
                PermissionRequestScreen(permissionsState)
            }
        }
    }
}

@Composable
fun TelemetryOverlay(
    data: TelemetryData,
    showSpeed: Boolean,
    showCoordinates: Boolean,
    showAltitude: Boolean,
    showTimestamp: Boolean,
    useMetric: Boolean,
    modifier: Modifier = Modifier
) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val timeString = dateFormat.format(Date(data.timestamp))

    if (!showSpeed && !showCoordinates && !showAltitude && !showTimestamp) return

    Surface(
        color = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            if (showSpeed) {
                val displaySpeed = if (useMetric) data.speedKmh else data.speedKmh * 0.621371f
                val unit = if (useMetric) "km/h" else "mph"
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = String.format("%.0f", displaySpeed),
                        style = MaterialTheme.typography.displayMedium.copy(
                            color = Color.Yellow,
                            fontWeight = FontWeight.Black,
                            fontSize = 48.sp
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelLarge.copy(color = Color.White),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            if (showCoordinates) {
                TelemetryRow(label = "LAT:", value = String.format("%.6f", data.latitude))
                TelemetryRow(label = "LON:", value = String.format("%.6f", data.longitude))
            }
            
            if (showAltitude) {
                val displayAltitude = if (useMetric) data.altitude else data.altitude * 3.28084
                val unit = if (useMetric) "m" else "ft"
                TelemetryRow(label = "ALT:", value = String.format("%.1f %s", displayAltitude, unit))
            }
            
            if (showTimestamp) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = timeString,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = Color.White.copy(alpha = 0.8f),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                )
            }
        }
    }
}

@Composable
fun TelemetryRow(label: String, value: String) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(color = Color.White.copy(alpha = 0.6f)),
            modifier = Modifier.width(40.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(
                color = Color.White,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        )
    }
}

fun formatDuration(millis: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionRequestScreen(permissionsState: com.google.accompanist.permissions.MultiplePermissionsState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Security, 
            contentDescription = null, 
            modifier = Modifier.size(80.dp), 
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "Permissions Required", 
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "This dashcam app needs Camera, Microphone, and Location permissions to function correctly.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = { permissionsState.launchMultiplePermissionRequest() },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Grant Permissions")
        }
    }
}

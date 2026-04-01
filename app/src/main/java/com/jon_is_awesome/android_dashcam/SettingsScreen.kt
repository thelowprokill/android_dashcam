package com.jon_is_awesome.android_dashcam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: DashcamViewModel,
    onBack: () -> Unit
) {
    val segmentLength by viewModel.segmentLengthMinutes.collectAsStateWithLifecycle()
    val showSpeed by viewModel.showSpeed.collectAsStateWithLifecycle()
    val showCoordinates by viewModel.showCoordinates.collectAsStateWithLifecycle()
    val showAltitude by viewModel.showAltitude.collectAsStateWithLifecycle()
    val showTimestamp by viewModel.showTimestamp.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Recording",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            
            SettingsSliderItem(
                title = "Video Segment Length",
                value = segmentLength.toFloat(),
                onValueChange = { viewModel.setSegmentLength(it.toInt()) },
                valueRange = 1f..10f,
                steps = 8,
                label = "$segmentLength min",
                icon = Icons.Default.Timer
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                "Telemetry Overlay",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            SettingsSwitchItem(
                title = "Show Speed",
                checked = showSpeed,
                onCheckedChange = { viewModel.setShowSpeed(it) },
                icon = Icons.Default.Speed
            )

            SettingsSwitchItem(
                title = "Show GPS Coordinates",
                checked = showCoordinates,
                onCheckedChange = { viewModel.setShowCoordinates(it) },
                icon = Icons.Default.LocationOn
            )

            SettingsSwitchItem(
                title = "Show Altitude",
                checked = showAltitude,
                onCheckedChange = { viewModel.setShowAltitude(it) },
                icon = Icons.Default.Height
            )

            SettingsSwitchItem(
                title = "Show Timestamp",
                checked = showTimestamp,
                onCheckedChange = { viewModel.setShowTimestamp(it) },
                icon = Icons.Default.Schedule
            )
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            
            Text(
                "Storage",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            ListItem(
                headlineContent = { Text("Storage Location") },
                supportingContent = { Text("Movies/Dashcam") },
                leadingContent = { Icon(Icons.Default.SdStorage, contentDescription = null) }
            )
        }
    }
}

@Composable
fun SettingsSliderItem(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    label: String,
    icon: ImageVector? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.secondary)
                Spacer(modifier = Modifier.width(16.dp))
            }
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
fun SettingsSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        leadingContent = icon?.let { { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) } },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
}

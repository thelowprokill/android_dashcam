package com.jon_is_awesome.android_dashcam

import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

class RecordingManager(
    private val context: Context,
    private var primaryVideoCapture: VideoCapture<Recorder>,
    private var secondaryVideoCapture: VideoCapture<Recorder>?,
    private val settingsManager: SettingsManager,
    private val telemetryData: StateFlow<TelemetryData>
) {
    private var primaryRecording: Recording? = null
    private var secondaryRecording: Recording? = null
    private var recordingJob: Job? = null
    private val telemetryLoggingJobs = mutableListOf<Job>()
    private var durationJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked

    private val _totalDurationMillis = MutableStateFlow(0L)
    val totalDurationMillis: StateFlow<Long> = _totalDurationMillis

    private val _segmentDurationMillis = MutableStateFlow(0L)
    val segmentDurationMillis: StateFlow<Long> = _segmentDurationMillis

    private var segmentLengthMinutes: Int = 5
    private var storageLocation: String = "Movies/Dashcam"
    private var useMetric: Boolean = true
    private var isCurrentSegmentLocked = false
    
    private var recordingStartTime = 0L
    private var segmentStartTime = 0L

    init {
        scope.launch {
            settingsManager.segmentLengthMinutes.collect {
                segmentLengthMinutes = it
            }
        }
        scope.launch {
            settingsManager.storageLocation.collect {
                storageLocation = it ?: "Movies/Dashcam"
            }
        }
        scope.launch {
            settingsManager.useMetric.collect {
                useMetric = it
            }
        }
    }

    fun updateVideoCaptures(primary: VideoCapture<Recorder>, secondary: VideoCapture<Recorder>?) {
        val wasRecording = _isRecording.value
        if (wasRecording) {
            stopRecording()
        }
        this.primaryVideoCapture = primary
        this.secondaryVideoCapture = secondary
        if (wasRecording) {
            // Give a small delay for recorders to release
            scope.launch {
                delay(500)
                startRecording()
            }
        }
    }

    fun startRecording() {
        if (_isRecording.value) return
        _isRecording.value = true
        recordingStartTime = System.currentTimeMillis()
        _totalDurationMillis.value = 0L
        startNewSegment()
        startDurationUpdates()
    }

    private fun startNewSegment() {
        isCurrentSegmentLocked = false
        _isLocked.value = false
        segmentStartTime = System.currentTimeMillis()
        _segmentDurationMillis.value = 0L
        
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        
        val backName = "Dashcam-Back-$timestamp"
        val frontName = "Dashcam-Front-$timestamp"
        
        try {
            primaryRecording = startRecordingForCamera(primaryVideoCapture, backName)
            secondaryVideoCapture?.let { 
                secondaryRecording = startRecordingForCamera(it, frontName)
            }
            
            // Cancel previous logging jobs if any
            telemetryLoggingJobs.forEach { it.cancel() }
            telemetryLoggingJobs.clear()
            
            telemetryLoggingJobs.add(startTelemetryLogging(backName))
            if (secondaryVideoCapture != null) {
                telemetryLoggingJobs.add(startTelemetryLogging(frontName))
            }
            
            startLoopTimer()
        } catch (e: Exception) {
            Log.e("RecordingManager", "Failed to start segment", e)
            _isRecording.value = false
        }
    }

    private fun startRecordingForCamera(videoCapture: VideoCapture<Recorder>, name: String): Recording {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, storageLocation)
            }
        }

        val data = telemetryData.value
        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .apply {
                if (data.latitude != 0.0 || data.longitude != 0.0) {
                    try {
                        val location = Location("fused").apply {
                            latitude = data.latitude
                            longitude = data.longitude
                            altitude = data.altitude
                            time = data.timestamp
                        }
                        setLocation(location)
                    } catch (e: Exception) {
                        Log.e("RecordingManager", "Error setting location", e)
                    }
                }
            }
            .build()

        return videoCapture.output
            .prepareRecording(context, mediaStoreOutputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { recordEvent: VideoRecordEvent ->
                if (recordEvent is VideoRecordEvent.Finalize) {
                    val uri = recordEvent.outputResults.outputUri
                    if (recordEvent.hasError()) {
                        Log.e("RecordingManager", "Recording error: ${recordEvent.error}, message: ${recordEvent.cause?.message}")
                    }
                    if (!recordEvent.hasError() && isCurrentSegmentLocked) {
                        protectSegment(uri)
                    }
                }
            }
    }

    private fun startTelemetryLogging(videoName: String): Job {
        val srtName = "$videoName.srt"
        
        return scope.launch(Dispatchers.IO) {
            var outputStream: OutputStream? = null
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, srtName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, storageLocation)
                    }
                    val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                    if (uri != null) {
                        outputStream = context.contentResolver.openOutputStream(uri)
                    }
                }
                
                if (outputStream == null) {
                    val logDir = File(context.getExternalFilesDir(null), "telemetry")
                    if (!logDir.exists()) logDir.mkdirs()
                    outputStream = java.io.FileOutputStream(File(logDir, srtName))
                    Log.d("RecordingManager", "Logging telemetry to fallback location: ${logDir.absolutePath}/$srtName")
                } else {
                    Log.d("RecordingManager", "Logging telemetry to MediaStore: $srtName")
                }

                outputStream?.use { stream ->
                    val out = stream.bufferedWriter()
                    var index = 1
                    val startTime = System.currentTimeMillis()
                    while (isActive && _isRecording.value) {
                        val data = telemetryData.value
                        val currentTime = System.currentTimeMillis() - startTime
                        val nextTime = currentTime + 1000
                        
                        out.write("${index++}\n")
                        out.write("${formatTime(currentTime)} --> ${formatTime(nextTime)}\n")
                        
                        if (useMetric) {
                            out.write(String.format(Locale.getDefault(), "Speed: %.1f km/h\n", data.speedKmh))
                            out.write(String.format(Locale.getDefault(), "Alt: %.1f m\n", data.altitude))
                        } else {
                            out.write(String.format(Locale.getDefault(), "Speed: %.1f mph\n", data.speedKmh * 0.621371f))
                            out.write(String.format(Locale.getDefault(), "Alt: %.1f ft\n", data.altitude * 3.28084))
                        }
                        
                        out.write(String.format(Locale.getDefault(), "Pos: %.6f, %.6f\n\n", data.latitude, data.longitude))
                        out.flush()
                        
                        delay(1000)
                    }
                }
            } catch (e: Exception) {
                Log.e("RecordingManager", "Failed to write telemetry log: $srtName", e)
            } finally {
                try { outputStream?.close() } catch (e: Exception) {}
            }
        }
    }

    private fun startDurationUpdates() {
        durationJob?.cancel()
        durationJob = scope.launch {
            while (isActive && _isRecording.value) {
                val now = System.currentTimeMillis()
                _totalDurationMillis.value = now - recordingStartTime
                _segmentDurationMillis.value = now - segmentStartTime
                delay(1000)
            }
        }
    }

    private fun formatTime(millis: Long): String {
        val h = millis / 3600000
        val m = (millis % 3600000) / 60000
        val s = (millis % 60000) / 1000
        val ms = millis % 1000
        return String.format(Locale.getDefault(), "%02d:%02d:%02d,%03d", h, m, s, ms)
    }

    private fun startLoopTimer() {
        recordingJob?.cancel()
        recordingJob = scope.launch {
            delay(segmentLengthMinutes * 60 * 1000L)
            if (_isRecording.value) {
                stopAndRestart()
            }
        }
    }

    private fun stopAndRestart() {
        primaryRecording?.stop()
        secondaryRecording?.stop()
        startNewSegment()
    }

    fun stopRecording() {
        _isRecording.value = false
        recordingJob?.cancel()
        telemetryLoggingJobs.forEach { it.cancel() }
        telemetryLoggingJobs.clear()
        durationJob?.cancel()
        primaryRecording?.stop()
        secondaryRecording?.stop()
        primaryRecording = null
        secondaryRecording = null
    }

    fun lockCurrentSegment() {
        if (!_isRecording.value) return
        isCurrentSegmentLocked = true
        _isLocked.value = true
        Log.d("RecordingManager", "Current segment marked as LOCKED")
    }

    private fun protectSegment(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "$storageLocation/Locked")
                    }
                    context.contentResolver.update(uri, contentValues, null, null)
                }
            } catch (e: Exception) {
                Log.e("RecordingManager", "Failed to protect segment", e)
            }
        }
    }
}

package com.jon_is_awesome.android_dashcam

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.camera.core.*
import androidx.camera.core.ConcurrentCamera.SingleCameraConfig
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DashcamViewModel : ViewModel() {

    private val _isDualCameraSupported = MutableStateFlow<Boolean?>(null)
    val isDualCameraSupported: StateFlow<Boolean> = _isDualCameraSupported
        .map { it ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _useDualCamera = MutableStateFlow(false)
    val useDualCamera: StateFlow<Boolean> = _useDualCamera.asStateFlow()

    private val _telemetryData = MutableStateFlow(TelemetryData())
    val telemetryData: StateFlow<TelemetryData> = _telemetryData.asStateFlow()

    private var primaryVideoCapture: VideoCapture<Recorder>? = null
    private var secondaryVideoCapture: VideoCapture<Recorder>? = null
    private var locationTracker: LocationTracker? = null
    private var recordingManager: RecordingManager? = null
    private var settingsManager: SettingsManager? = null

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    // Settings
    private val _segmentLengthMinutes = MutableStateFlow(5)
    val segmentLengthMinutes: StateFlow<Int> = _segmentLengthMinutes.asStateFlow()

    private val _showSpeed = MutableStateFlow(true)
    val showSpeed: StateFlow<Boolean> = _showSpeed.asStateFlow()

    private val _showCoordinates = MutableStateFlow(true)
    val showCoordinates: StateFlow<Boolean> = _showCoordinates.asStateFlow()

    private val _showAltitude = MutableStateFlow(true)
    val showAltitude: StateFlow<Boolean> = _showAltitude.asStateFlow()

    private val _showTimestamp = MutableStateFlow(true)
    val showTimestamp: StateFlow<Boolean> = _showTimestamp.asStateFlow()

    private val _useMetric = MutableStateFlow(true)
    val useMetric: StateFlow<Boolean> = _useMetric.asStateFlow()

    private val _maxStorageGb = MutableStateFlow(10)
    val maxStorageGb: StateFlow<Int> = _maxStorageGb.asStateFlow()

    private val _pipPositionX = MutableStateFlow(0.7f) // 0.0 to 1.0 (left to right)
    val pipPositionX: StateFlow<Float> = _pipPositionX.asStateFlow()

    val isRecording: StateFlow<Boolean> = flow {
        while (true) {
            emit(recordingManager?.isRecording?.value ?: false)
            kotlinx.coroutines.delay(500)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val totalDurationMillis: StateFlow<Long> = flow {
        while (true) {
            emit(recordingManager?.totalDurationMillis?.value ?: 0L)
            kotlinx.coroutines.delay(1000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val segmentDurationMillis: StateFlow<Long> = flow {
        while (true) {
            emit(recordingManager?.segmentDurationMillis?.value ?: 0L)
            kotlinx.coroutines.delay(1000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private var currentLifecycleOwner: LifecycleOwner? = null
    private var currentPreviewView: PreviewView? = null
    
    private var lastFrontBitmap: Bitmap? = null
    private val bitmapLock = Any()
    private val frameCount = AtomicInteger(0)
    private val drawCount = AtomicInteger(0)

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val effectHandlerThread = HandlerThread("CameraEffectThread").apply { start() }
    private val effectHandler = Handler(effectHandlerThread.looper)
    
    private var activeOverlayEffect: OverlayEffect? = null
    private var bindJob: Job? = null

    fun startLocationUpdates(context: Context) {
        if (_isInitialized.value) return
        val settings = SettingsManager(context)
        settingsManager = settings
        locationTracker = LocationTracker(context)
        
        settings.segmentLengthMinutes.onEach { _segmentLengthMinutes.value = it }.launchIn(viewModelScope)
        settings.showSpeed.onEach { _showSpeed.value = it }.launchIn(viewModelScope)
        settings.showCoordinates.onEach { _showCoordinates.value = it }.launchIn(viewModelScope)
        settings.showAltitude.onEach { _showAltitude.value = it }.launchIn(viewModelScope)
        settings.showTimestamp.onEach { _showTimestamp.value = it }.launchIn(viewModelScope)
        settings.useMetric.onEach { _useMetric.value = it }.launchIn(viewModelScope)
        settings.maxStorageGb.onEach { _maxStorageGb.value = it }.launchIn(viewModelScope)
        settings.pipPositionX.onEach { _pipPositionX.value = it }.launchIn(viewModelScope)
        
        // Load dual camera setting
        settings.useDualCamera.onEach { use -> 
            if (_useDualCamera.value != use) {
                _useDualCamera.value = use
                Log.d("Dashcam", "Dual camera setting updated: $use")
                triggerRebind(context)
            }
        }.launchIn(viewModelScope)

        _isInitialized.value = true
        
        // Start telemetry updates
        locationTracker?.getLocationUpdates()?.onEach { data ->
            _telemetryData.value = data
        }?.launchIn(viewModelScope)

        // Perform initial support check
        checkDualCameraSupport(context)
    }

    fun checkDualCameraSupport(context: Context) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val concurrentCameras = cameraProvider.availableConcurrentCameraInfos
                val supported = concurrentCameras.isNotEmpty()
                Log.d("Dashcam", "Support check result: $supported")
                
                val wasSupported = _isDualCameraSupported.value
                _isDualCameraSupported.value = supported
                
                // If support status changed while we were waiting, trigger a rebind
                if (wasSupported != supported && _useDualCamera.value) {
                    triggerRebind(context)
                }
            } catch (e: Exception) {
                Log.e("Dashcam", "Error checking dual camera support", e)
                _isDualCameraSupported.value = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun toggleDualCamera(context: Context) {
        val supported = _isDualCameraSupported.value ?: false
        if (supported) {
            val newValue = !_useDualCamera.value
            setUseDualCamera(context, newValue)
        }
    }

    fun setUseDualCamera(context: Context, use: Boolean) {
        viewModelScope.launch {
            settingsManager?.setUseDualCamera(use)
            if (_useDualCamera.value != use) {
                _useDualCamera.value = use
                triggerRebind(context)
            }
        }
    }

    private fun triggerRebind(context: Context) {
        val owner = currentLifecycleOwner ?: return
        val view = currentPreviewView ?: return
        
        bindJob?.cancel()
        bindJob = viewModelScope.launch {
            // Debounce rapid triggers
            delay(150)
            
            // Wait for support check to finish if it's still null (max 1s)
            if (_isDualCameraSupported.value == null) {
                withTimeoutOrNull(1000) {
                    _isDualCameraSupported.first { it != null }
                }
            }
            
            performBindCamera(context, owner, view)
        }
    }

    fun bindCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        currentLifecycleOwner = lifecycleOwner
        currentPreviewView = previewView
        
        if (!_isInitialized.value) startLocationUpdates(context)
        
        triggerRebind(context)
    }

    private fun performBindCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
            
            // Reset frame state
            synchronized(bitmapLock) {
                lastFrontBitmap?.recycle()
                lastFrontBitmap = null
            }
            
            activeOverlayEffect?.close()
            activeOverlayEffect = createOverlayEffect()
            val overlayEffect = activeOverlayEffect!!

            val primaryVC = VideoCapture.withOutput(Recorder.Builder().build())
            primaryVideoCapture = primaryVC

            val primaryPreview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

            var frontAnalysis: ImageAnalysis? = null
            val useDual = _useDualCamera.value && (_isDualCameraSupported.value == true)
            
            if (useDual) {
                frontAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER))
                            .build()
                    )
                    .build()
                
                frontAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    try {
                        val bitmap = imageProxy.toBitmap()
                        val rotation = imageProxy.imageInfo.rotationDegrees
                        
                        // Rotate and Mirror the front camera for intuitive PiP
                        val matrix = Matrix()
                        matrix.postRotate(rotation.toFloat())
                        // Mirror horizontally: Scale -1 on X axis relative to center
                        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        val mirrorMatrix = Matrix().apply { postScale(-1f, 1f, rotated.width / 2f, rotated.height / 2f) }
                        val mirrored = Bitmap.createBitmap(rotated, 0, 0, rotated.width, rotated.height, mirrorMatrix, true)
                        
                        var old: Bitmap? = null
                        synchronized(bitmapLock) {
                            old = lastFrontBitmap
                            lastFrontBitmap = mirrored
                        }
                        bitmap.recycle()
                        rotated.recycle()
                        old?.recycle()
                    } catch (e: Exception) {
                        Log.e("Dashcam", "Front analyzer error", e)
                    } finally {
                        imageProxy.close()
                    }
                }
            }

            val settings = settingsManager
            if (recordingManager == null && settings != null) {
                recordingManager = RecordingManager(context, primaryVC, null, settings, telemetryData)
                viewModelScope.launch {
                    recordingManager?.isLocked?.collect { _isLocked.value = it }
                }
            } else {
                recordingManager?.updateVideoCaptures(primaryVC, null)
            }

            try {
                if (useDual && frontAnalysis != null) {
                    val concurrentInfos = cameraProvider.availableConcurrentCameraInfos
                    if (concurrentInfos.isNotEmpty()) {
                        val pair = concurrentInfos[0]
                        val configs = mutableListOf<SingleCameraConfig>()
                        val viewPort = previewView.viewPort
                        
                        for (info in pair) {
                            val selector = CameraSelector.Builder().requireLensFacing(info.lensFacing).build()
                            val groupBuilder = UseCaseGroup.Builder()
                            viewPort?.let { groupBuilder.setViewPort(it) }
                            
                            if (info.lensFacing == CameraSelector.LENS_FACING_BACK) {
                                groupBuilder.addEffect(overlayEffect).addUseCase(primaryPreview).addUseCase(primaryVC)
                            } else {
                                groupBuilder.addUseCase(frontAnalysis)
                            }
                            configs.add(SingleCameraConfig(selector, groupBuilder.build(), lifecycleOwner))
                        }
                        
                        if (configs.size == 2) {
                            cameraProvider.bindToLifecycle(configs)
                            Log.d("Dashcam", "Bound dual cameras")
                            return@addListener
                        }
                    }
                }
                
                // Fallback to back camera
                val viewPort = previewView.viewPort
                val useCaseGroup = UseCaseGroup.Builder()
                    .addUseCase(primaryPreview)
                    .addUseCase(primaryVC)
                    .addEffect(overlayEffect)
                    .apply { viewPort?.let { setViewPort(it) } }
                    .build()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, useCaseGroup)
                Log.d("Dashcam", "Bound single camera")
                
            } catch (e: Exception) {
                Log.e("Dashcam", "Binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun createOverlayEffect(): OverlayEffect {
        val paint = Paint().apply {
            color = Color.YELLOW
            textSize = 24f
            typeface = Typeface.MONOSPACE
            style = Paint.Style.FILL
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }
        val bgPaint = Paint().apply { color = Color.argb(128, 0, 0, 0); style = Paint.Style.FILL }
        val borderPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f }

        val overlayEffect = OverlayEffect(CameraEffect.VIDEO_CAPTURE or CameraEffect.PREVIEW, 2, effectHandler) { throwable ->
            Log.e("Dashcam", "Overlay error", throwable)
        }

        overlayEffect.setOnDrawListener { frame ->
            val canvas = frame.overlayCanvas
            val width = canvas.width.toFloat()
            val height = canvas.height.toFloat()
            val rotation = frame.rotationDegrees.toFloat()

            val isRotated = frame.rotationDegrees == 90 || frame.rotationDegrees == 270
            val vWidth = if (isRotated) height else width
            val vHeight = if (isRotated) width else height
            
            canvas.save()
            canvas.translate(width / 2f, height / 2f)
            canvas.rotate(rotation + 180f)
            canvas.translate(-vWidth / 2f, -vHeight / 2f)

            if (_useDualCamera.value) {
                synchronized(bitmapLock) {
                    val bitmap = lastFrontBitmap
                    if (bitmap != null && !bitmap.isRecycled) {
                        val pipHeight = vHeight * 0.25f
                        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                        val pipWidth = ratio * pipHeight
                        
                        val currentPipX = _pipPositionX.value
                        val availableWidth = (vWidth - pipWidth - 160f).coerceAtLeast(0f)
                        val left = 80f + (currentPipX * availableWidth)
                        val rect = RectF(left, vHeight - pipHeight - 80f, left + pipWidth, vHeight - 80f)
                        
                        val solidBlack = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
                        canvas.drawRect(rect.left - 4f, rect.top - 4f, rect.right + 4f, rect.bottom + 4f, solidBlack)
                        canvas.drawBitmap(bitmap, null, rect, null)
                        canvas.drawRect(rect, borderPaint)
                    }
                }
            }

            val data = telemetryData.value
            val lines = mutableListOf<String>()
            if (_showSpeed.value) lines.add(if (_useMetric.value) String.format(Locale.getDefault(), "%.1f km/h", data.speedKmh) else String.format(Locale.getDefault(), "%.1f mph", data.speedKmh * 0.621371f))
            if (_showCoordinates.value) lines.add(String.format(Locale.getDefault(), "%.6f, %.6f", data.latitude, data.longitude))
            if (_showAltitude.value) lines.add(if (_useMetric.value) String.format(Locale.getDefault(), "Alt: %.1f m", data.altitude) else String.format(Locale.getDefault(), "Alt: %.1f ft", data.altitude * 3.28084))
            if (_showTimestamp.value) lines.add(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))

            if (lines.isNotEmpty()) {
                val textSize = vHeight * 0.015f
                paint.textSize = textSize
                val margin = 100f
                var yPos = vHeight - margin
                for (line in lines.reversed()) {
                    val textWidth = paint.measureText(line)
                    canvas.drawRect(margin - 10f, yPos - textSize + 5f, margin + textWidth + 10f, yPos + 5f, bgPaint)
                    canvas.drawText(line, margin, yPos, paint)
                    yPos -= textSize * 1.4f
                }
            }
            canvas.restore()
            true
        }
        return overlayEffect
    }

    fun onRecordClick() {
        recordingManager?.let { if (it.isRecording.value) it.stopRecording() else it.startRecording() }
    }

    fun onLockClick() = recordingManager?.lockCurrentSegment()

    fun setSegmentLength(m: Int) = viewModelScope.launch { settingsManager?.setSegmentLength(m); _segmentLengthMinutes.value = m }
    fun setShowSpeed(s: Boolean) = viewModelScope.launch { settingsManager?.setShowSpeed(s); _showSpeed.value = s }
    fun setShowCoordinates(s: Boolean) = viewModelScope.launch { settingsManager?.setShowCoordinates(s); _showCoordinates.value = s }
    fun setShowAltitude(s: Boolean) = viewModelScope.launch { settingsManager?.setShowAltitude(s); _showAltitude.value = s }
    fun setShowTimestamp(s: Boolean) = viewModelScope.launch { settingsManager?.setShowTimestamp(s); _showTimestamp.value = s }
    fun setUseMetric(m: Boolean) = viewModelScope.launch { settingsManager?.setUseMetric(m); _useMetric.value = m }
    fun setMaxStorageGb(g: Int) = viewModelScope.launch { settingsManager?.setMaxStorageGb(g); _maxStorageGb.value = g }
    fun setPipPositionX(x: Float) = viewModelScope.launch { settingsManager?.setPipPositionX(x); _pipPositionX.value = x }

    override fun onCleared() {
        super.onCleared()
        recordingManager?.stopRecording()
        activeOverlayEffect?.close()
        cameraExecutor.shutdown()
        effectHandlerThread.quitSafely()
        synchronized(bitmapLock) { lastFrontBitmap?.recycle(); lastFrontBitmap = null }
    }
}

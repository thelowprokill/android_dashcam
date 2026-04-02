package com.jon_is_awesome.android_dashcam

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.HandlerThread
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DashcamViewModel : ViewModel() {

    private val _isDualCameraSupported = MutableStateFlow(false)
    val isDualCameraSupported: StateFlow<Boolean> = _isDualCameraSupported.asStateFlow()

    private val _useDualCamera = MutableStateFlow(false)
    val useDualCamera: StateFlow<Boolean> = _useDualCamera.asStateFlow()

    private val _telemetryData = MutableStateFlow(TelemetryData())
    val telemetryData: StateFlow<TelemetryData> = _telemetryData.asStateFlow()

    private var primaryVideoCapture: VideoCapture<Recorder>? = null
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
    private var lastLogTime = 0L

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

        _isInitialized.value = true
        
        // Start telemetry updates
        locationTracker?.getLocationUpdates()?.onEach { data ->
            _telemetryData.value = data
        }?.launchIn(viewModelScope)
    }

    fun checkDualCameraSupport(context: Context) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val concurrentCameras = cameraProvider.availableConcurrentCameraInfos
                _isDualCameraSupported.value = concurrentCameras.isNotEmpty()
                Log.d("Dashcam", "Dual camera supported: ${_isDualCameraSupported.value}. Pair count: ${concurrentCameras.size}")
            } catch (e: Exception) {
                Log.e("Dashcam", "Error checking dual camera support", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun toggleDualCamera(context: Context) {
        if (_isDualCameraSupported.value) {
            _useDualCamera.value = !_useDualCamera.value
            Log.d("Dashcam", "Toggled Dual Camera: ${_useDualCamera.value}")
            val lifecycleOwner = currentLifecycleOwner
            val previewView = currentPreviewView
            if (lifecycleOwner != null && previewView != null) {
                bindCamera(context, lifecycleOwner, previewView)
            }
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

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
            
            // Reset state
            frameCount.set(0)
            drawCount.set(0)
            synchronized(bitmapLock) {
                lastFrontBitmap?.recycle()
                lastFrontBitmap = null
            }
            
            // Recreate effect to ensure fresh state
            activeOverlayEffect?.close()
            activeOverlayEffect = createOverlayEffect()
            val overlayEffect = activeOverlayEffect!!

            // Setup Primary (Back) Camera UseCases
            val primaryRecorder = Recorder.Builder().build()
            val primaryVC = VideoCapture.withOutput(primaryRecorder)
            primaryVideoCapture = primaryVC

            val primaryPreview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

            // Setup Secondary (Front) Camera UseCase for PiP
            var frontAnalysis: ImageAnalysis? = null
            if (_useDualCamera.value && _isDualCameraSupported.value) {
                Log.d("Dashcam", "Setting up front camera analyzer")
                
                frontAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(640, 480))
                    .build()
                
                frontAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    try {
                        val count = frameCount.incrementAndGet()
                        if (count % 30 == 1) {
                            Log.d("Dashcam", "Analyzer received frame #$count: ${imageProxy.width}x${imageProxy.height}")
                        }
                        
                        val bitmap = imageProxy.toBitmap()
                        val rotation = imageProxy.imageInfo.rotationDegrees
                        
                        val rotatedBitmap = if (rotation != 0) {
                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                                bitmap.recycle()
                            }
                        } else {
                            bitmap
                        }

                        var old: Bitmap? = null
                        synchronized(bitmapLock) {
                            old = lastFrontBitmap
                            lastFrontBitmap = rotatedBitmap
                        }
                        old?.recycle()
                    } catch (e: Exception) {
                        Log.e("Dashcam", "Error analyzing front camera frame", e)
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
                if (_useDualCamera.value && _isDualCameraSupported.value && frontAnalysis != null) {
                    val concurrentInfos = cameraProvider.availableConcurrentCameraInfos
                    Log.d("Dashcam", "Binding concurrent. Available pairs: ${concurrentInfos.size}")
                    
                    if (concurrentInfos.isNotEmpty()) {
                        val pair = concurrentInfos[0]
                        val configs = mutableListOf<SingleCameraConfig>()
                        val viewPort = previewView.viewPort
                        
                        for (info in pair) {
                            val selector = CameraSelector.Builder().addCameraFilter { cameras ->
                                cameras.filter { it == info }
                            }.build()
                            
                            val groupBuilder = UseCaseGroup.Builder()
                                .addEffect(overlayEffect) // Both MUST share the same instance
                            
                            if (info.lensFacing == CameraSelector.LENS_FACING_BACK) {
                                groupBuilder.addUseCase(primaryPreview).addUseCase(primaryVC)
                            } else {
                                groupBuilder.addUseCase(frontAnalysis)
                            }
                            
                            viewPort?.let { groupBuilder.setViewPort(it) }
                            configs.add(SingleCameraConfig(selector, groupBuilder.build(), lifecycleOwner))
                        }
                        
                        if (configs.size == 2) {
                            cameraProvider.bindToLifecycle(configs)
                            Log.d("Dashcam", "Successfully bound concurrent cameras")
                        } else {
                            Log.w("Dashcam", "Falling back to back camera only")
                            val backGroup = UseCaseGroup.Builder()
                                .addUseCase(primaryPreview)
                                .addUseCase(primaryVC)
                                .addEffect(overlayEffect)
                                .apply { viewPort?.let { setViewPort(it) } }
                                .build()
                            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, backGroup)
                        }
                    }
                } else {
                    val useCaseGroup = UseCaseGroup.Builder()
                        .addUseCase(primaryPreview)
                        .addUseCase(primaryVC)
                        .addEffect(overlayEffect)
                        .apply { previewView.viewPort?.let { setViewPort(it) } }
                        .build()
                    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, useCaseGroup)
                    Log.d("Dashcam", "Bound primary camera only")
                }
            } catch (e: Exception) {
                Log.e("Dashcam", "Use case binding failed", e)
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
        
        val bgPaint = Paint().apply {
            color = Color.argb(128, 0, 0, 0)
            style = Paint.Style.FILL
        }

        val borderPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        // Apply only to VIDEO_CAPTURE to hide from app view
        val overlayEffect = OverlayEffect(
            CameraEffect.VIDEO_CAPTURE,
            2,
            effectHandler
        ) { throwable ->
            Log.e("Dashcam", "OverlayEffect error", throwable)
        }

        overlayEffect.setOnDrawListener { frame ->
            val canvas = frame.overlayCanvas
            val width = canvas.width.toFloat()
            val height = canvas.height.toFloat()
            val rotation = frame.rotationDegrees.toFloat()

            // Normalize coordinate system so (0,0) is visually top-left
            val isRotated = frame.rotationDegrees == 90 || frame.rotationDegrees == 270
            val vWidth = if (isRotated) height else width
            val vHeight = if (isRotated) width else height
            
            canvas.save()
            // 1. Normalize to "visually upright" coordinate system and flip 180
            canvas.translate(width / 2f, height / 2f)
            canvas.rotate(rotation + 180f)
            canvas.translate(-vWidth / 2f, -vHeight / 2f)

            // 2. Draw PiP (Front Camera) in visual Bottom Right
            if (_useDualCamera.value) {
                synchronized(bitmapLock) {
                    val bitmap = lastFrontBitmap
                    val pipHeight = vHeight * 0.25f
                    val pipWidth = if (bitmap != null) {
                        (bitmap.width.toFloat() / bitmap.height.toFloat() * pipHeight)
                    } else {
                        pipHeight * 1.33f
                    }
                    val margin = 100f
                    val rect = RectF(vWidth - pipWidth - margin, vHeight - pipHeight - margin, vWidth - margin, vHeight - margin)
                    if (bitmap != null && !bitmap.isRecycled) {
                        val solidBlack = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
                        canvas.drawRect(rect.left - 4f, rect.top - 4f, rect.right + 4f, rect.bottom + 4f, solidBlack)
                        canvas.drawBitmap(bitmap, null, rect, null)
                        canvas.drawRect(rect, borderPaint)
                    } else {
                        val dGray = Paint().apply { color = Color.DKGRAY; style = Paint.Style.FILL }
                        canvas.drawRect(rect, dGray)
                        canvas.drawRect(rect, borderPaint)
                    }
                }
            }

            // 3. Draw Telemetry in visual Bottom Left, stacked upright
            val data = telemetryData.value
            val isMetric = _useMetric.value
            val lines = mutableListOf<String>()
            
            if (_showSpeed.value) {
                if (isMetric) {
                    lines.add(String.format(Locale.getDefault(), "%.1f km/h", data.speedKmh))
                } else {
                    val speedMph = data.speedKmh * 0.621371f
                    lines.add(String.format(Locale.getDefault(), "%.1f mph", speedMph))
                }
            }
            if (_showCoordinates.value) lines.add(String.format(Locale.getDefault(), "%.6f, %.6f", data.latitude, data.longitude))
            if (_showAltitude.value) {
                if (isMetric) {
                    lines.add(String.format(Locale.getDefault(), "Alt: %.1f m", data.altitude))
                } else {
                    val altitudeFt = data.altitude * 3.28084
                    lines.add(String.format(Locale.getDefault(), "Alt: %.1f ft", altitudeFt))
                }
            }
            if (_showTimestamp.value) {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                lines.add(sdf.format(Date()))
            }

            if (lines.isNotEmpty()) {
                // Reduced text size further
                val textSize = vHeight * 0.015f
                paint.textSize = textSize
                
                // Draw stacked from bottom up in the visual Bottom Left with larger margin
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
        val manager = recordingManager ?: return
        if (manager.isRecording.value) {
            manager.stopRecording()
        } else {
            manager.startRecording()
        }
    }

    fun onLockClick() {
        recordingManager?.lockCurrentSegment()
    }

    fun setSegmentLength(minutes: Int) {
        viewModelScope.launch { 
            settingsManager?.setSegmentLength(minutes)
            _segmentLengthMinutes.value = minutes
        }
    }
    
    fun setShowSpeed(show: Boolean) {
        viewModelScope.launch { 
            settingsManager?.setShowSpeed(show)
            _showSpeed.value = show
        }
    }
    
    fun setShowCoordinates(show: Boolean) {
        viewModelScope.launch { 
            settingsManager?.setShowCoordinates(show)
            _showCoordinates.value = show
        }
    }
    
    fun setShowAltitude(show: Boolean) {
        viewModelScope.launch { 
            settingsManager?.setShowAltitude(show)
            _showAltitude.value = show
        }
    }
    
    fun setShowTimestamp(show: Boolean) {
        viewModelScope.launch { 
            settingsManager?.setShowTimestamp(show)
            _showTimestamp.value = show
        }
    }

    fun setUseMetric(metric: Boolean) {
        viewModelScope.launch {
            settingsManager?.setUseMetric(metric)
            _useMetric.value = metric
        }
    }

    override fun onCleared() {
        super.onCleared()
        recordingManager?.stopRecording()
        activeOverlayEffect?.close()
        cameraExecutor.shutdown()
        effectHandlerThread.quitSafely()
        synchronized(bitmapLock) {
            lastFrontBitmap?.recycle()
            lastFrontBitmap = null
        }
    }
}

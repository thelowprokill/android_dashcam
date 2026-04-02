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
                    // Concurrent camera requires identical ViewPort and Effects across groups
                    val viewPort = previewView.viewPort
                    
                    val backGroup = UseCaseGroup.Builder()
                        .addUseCase(primaryPreview)
                        .addUseCase(primaryVC)
                        .addEffect(overlayEffect)
                        .apply { viewPort?.let { setViewPort(it) } }
                        .build()
                        
                    val frontGroup = UseCaseGroup.Builder()
                        .addUseCase(frontAnalysis)
                        .addEffect(overlayEffect) // Must add same effect instance
                        .apply { viewPort?.let { setViewPort(it) } }
                        .build()
                    
                    val configs = mutableListOf<SingleCameraConfig>()
                    val concurrentInfos = cameraProvider.availableConcurrentCameraInfos
                    Log.d("Dashcam", "Binding concurrent. Available pairs: ${concurrentInfos.size}")
                    
                    if (concurrentInfos.isNotEmpty()) {
                        val pair = concurrentInfos[0]
                        for (info in pair) {
                            val selector = CameraSelector.Builder().addCameraFilter { cameras ->
                                cameras.filter { it == info }
                            }.build()
                            
                            val group = if (info.lensFacing == CameraSelector.LENS_FACING_BACK) backGroup else frontGroup
                            configs.add(SingleCameraConfig(selector, group, lifecycleOwner))
                            Log.d("Dashcam", "  Added config for lens: ${info.lensFacing}")
                        }
                    }
                    
                    if (configs.size == 2) {
                        cameraProvider.bindToLifecycle(configs)
                        Log.d("Dashcam", "Successfully bound concurrent cameras")
                    } else {
                        Log.w("Dashcam", "Could not form configs for concurrent camera (size: ${configs.size}), falling back to back only")
                        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, backGroup)
                    }
                } else {
                    val useCaseGroup = UseCaseGroup.Builder()
                        .addUseCase(primaryPreview)
                        .addUseCase(primaryVC)
                        .addEffect(overlayEffect)
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
            textSize = 40f
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
            strokeWidth = 4f
        }

        val solidBlackPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }

        val overlayEffect = OverlayEffect(
            CameraEffect.VIDEO_CAPTURE or CameraEffect.PREVIEW,
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
            
            // Visually upright dimensions
            val isRotated = frame.rotationDegrees == 90 || frame.rotationDegrees == 270
            val vWidth = if (isRotated) height else width
            val vHeight = if (isRotated) width else height
            
            canvas.save()
            
            // Coordinate system normalization
            canvas.translate(width / 2f, height / 2f)
            canvas.rotate(rotation)
            canvas.translate(-vWidth / 2f, -vHeight / 2f)

            // 1. Draw Telemetry
            val data = telemetryData.value
            val lines = mutableListOf<String>()
            if (_showSpeed.value) lines.add(String.format(Locale.getDefault(), "%.1f km/h", data.speedKmh))
            if (_showCoordinates.value) lines.add(String.format(Locale.getDefault(), "%.6f, %.6f", data.latitude, data.longitude))
            if (_showAltitude.value) lines.add(String.format(Locale.getDefault(), "Alt: %.1f m", data.altitude))
            if (_showTimestamp.value) {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                lines.add(sdf.format(Date()))
            }
            
            var yOffset = vHeight - 40f
            for (line in lines.reversed()) {
                val textWidth = paint.measureText(line)
                canvas.drawRect(20f, yOffset - 35f, 30f + textWidth, yOffset + 10f, bgPaint)
                canvas.drawText(line, 25f, yOffset, paint)
                yOffset -= 50f
            }
            
            // 2. Draw PiP (Front Camera)
            if (_useDualCamera.value) {
                synchronized(bitmapLock) {
                    val bitmap = lastFrontBitmap
                    val dCount = drawCount.incrementAndGet()
                    val now = System.currentTimeMillis()
                    if (now - lastLogTime > 5000) {
                        Log.d("Dashcam", "Overlay draw #$dCount. Has Bitmap: ${bitmap != null}, count: ${frameCount.get()}, canvas: ${width}x${height}, rot: $rotation")
                        lastLogTime = now
                    }

                    // piP dimensions: approx 40% of width
                    val pipWidth = vWidth * 0.4f
                    val pipHeight = if (bitmap != null) {
                        (bitmap.height.toFloat() / bitmap.width.toFloat() * pipWidth)
                    } else {
                        pipWidth * 1.33f
                    }
                    
                    // Position: Middle Right, clear of TopAppBar
                    val rect = RectF(vWidth - pipWidth - 40f, vHeight / 4f, vWidth - 40f, vHeight / 4f + pipHeight)
                    
                    if (bitmap != null && !bitmap.isRecycled) {
                        // Draw a black background border
                        val solidBlack = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
                        canvas.drawRect(rect.left - 6f, rect.top - 6f, rect.right + 6f, rect.bottom + 6f, solidBlack)
                        canvas.drawBitmap(bitmap, null, rect, null)
                        canvas.drawRect(rect, borderPaint)
                    } else {
                        // Very visible placeholder
                        val dGray = Paint().apply { color = Color.DKGRAY; style = Paint.Style.FILL }
                        canvas.drawRect(rect, dGray)
                        canvas.drawRect(rect, borderPaint)
                        paint.textSize = 30f
                        val msg = if (frameCount.get() > 0) "PROCESSING..." else "STARTING..."
                        val tw = paint.measureText(msg)
                        canvas.drawText(msg, rect.centerX() - tw/2, rect.centerY(), paint)
                        paint.textSize = 40f
                    }
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

/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reilandeubank.unprocess.fragments

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.drawable.toDrawable
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import com.reilandeubank.unprocess.utils.computeExifOrientation
import com.reilandeubank.unprocess.utils.getPreviewOutputSize
import com.reilandeubank.unprocess.utils.OrientationLiveData
import com.reilandeubank.unprocess.CameraActivity
import com.reilandeubank.unprocess.R
import com.reilandeubank.unprocess.databinding.FragmentCameraBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import java.util.Date
import java.util.Locale
import kotlin.RuntimeException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.graphics.Bitmap
import android.hardware.camera2.CameraMetadata
import android.media.MediaScannerConnection
import android.widget.SeekBar
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

class CameraFragment : Fragment() {

    /** Android ViewBinding */
    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    /** AndroidX navigation arguments */
    private val args: CameraFragmentArgs by navArgs()

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(args.cameraId)
    }

    /** Readers used as buffers for camera still shots */
    private lateinit var imageReader: ImageReader

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** Performs recording animation of flashing screen */
    private val animationTask: Runnable by lazy {
        Runnable {
            // Flash white animation
            val ui = bindingOrNull() ?: return@Runnable
            ui.overlay.background = Color.argb(150, 255, 255, 255).toDrawable()
            // Wait for ANIMATION_FAST_MILLIS
            ui.overlay.postDelayed({
                // Remove white flash animation
                bindingOrNull()?.overlay?.background = null
            }, CameraActivity.ANIMATION_FAST_MILLIS)
        }
    }

    /** [HandlerThread] where all buffer reading operations run */
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }

    /** [Handler] corresponding to [imageReaderThread] */
    private val imageReaderHandler = Handler(imageReaderThread.looper)

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    /** Internal reference to the ongoing [CameraCaptureSession] configured with our parameters */
    private lateinit var session: CameraCaptureSession
    private var sessionReady = false
    private lateinit var previewRequest: CaptureRequest.Builder
    private var flashModes = listOf(CaptureRequest.CONTROL_AE_MODE_ON)
    private var flashModeIndex = 0
    private var focusRegion: MeteringRectangle? = null
    private var exposureCompensation = 0
    private enum class ProControl { ISO, SHUTTER, FOCUS, WB }
    @Volatile private var proEnabled = false
    private var selectedProControl = ProControl.ISO
    @Volatile private var manualIso: Int? = null
    @Volatile private var manualShutterNs: Long? = null
    private var manualFocusDiopters: Float? = null
    private var whiteBalanceMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
    private var gridEnabled = false
    @Volatile private var lastAutoIso = 100
    @Volatile private var lastAutoShutterNs = 16_666_667L

    private val isoRange by lazy {
        characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    }
    private val shutterRange by lazy {
        characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    }
    private val manualSensorSupported by lazy {
        characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true &&
            isoRange != null && shutterRange != null
    }
    private val manualFocusSupported by lazy {
        (characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f &&
            characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.contains(CameraMetadata.CONTROL_AF_MODE_OFF) == true
    }
    private val whiteBalanceOptions by lazy {
        val available = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?.toSet() ?: emptySet()
        listOf(
            CameraMetadata.CONTROL_AWB_MODE_AUTO to "Auto",
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT to "Sol",
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "Nubes",
            CameraMetadata.CONTROL_AWB_MODE_SHADE to "Sombra",
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT to "Interior",
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT to "Flúor"
        ).filter { it.first in available }.ifEmpty {
            listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO to "Auto")
        }
    }

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fragmentCameraBinding.formatLabel.text = if (args.convertToJpeg) "JPG desde RAW" else "RAW / DNG"
        fragmentCameraBinding.backButton.setOnClickListener { findNavController().navigateUp() }
        fragmentCameraBinding.galleryShortcut.setOnClickListener {
            findNavController().navigate(CameraFragmentDirections.actionCameraToGallery())
        }
        configureControls()
        configureProControls()

        fragmentCameraBinding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {
                // Selects appropriate preview size and configures view finder
                val previewSize = getPreviewOutputSize(
                    fragmentCameraBinding.viewFinder.display,
                    characteristics,
                    SurfaceHolder::class.java
                )
                Log.d(
                    TAG,
                    "View finder size: ${fragmentCameraBinding.viewFinder.width} x ${fragmentCameraBinding.viewFinder.height}"
                )
                Log.d(TAG, "Selected preview size: $previewSize")
                fragmentCameraBinding.viewFinder.setAspectRatio(
                    previewSize.width,
                    previewSize.height
                )

                // To ensure that size is set, initialize camera in the view's thread
                view.post { initializeCamera() }
            }
        })

        // Used to rotate the output media to match device orientation
        relativeOrientation = OrientationLiveData(requireContext(), characteristics).apply {
            observe(viewLifecycleOwner, Observer { orientation ->
                Log.d(TAG, "Orientation changed: $orientation")
            })
        }
    }

    private fun configureControls() {
        val ui = fragmentCameraBinding
        val supportedAeModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
            ?.toSet() ?: emptySet()
        if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
            flashModes = listOf(CaptureRequest.CONTROL_AE_MODE_ON) +
                listOf(CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH,
                    CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH).filter { it in supportedAeModes }
        }
        refreshFlashButton()
        ui.flashButton.setOnClickListener {
            flashModeIndex = (flashModeIndex + 1) % flashModes.size
            refreshFlashButton()
            updatePreview()
        }

        val range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        val min = range?.lower ?: 0
        val max = range?.upper ?: 0
        ui.focusExposureOverlay.setExposureEnabled(min < max)
        ui.focusExposureOverlay.setExposure(
            if (min < max) (-min).toFloat() / (max - min) else 0.5f,
            "0.0 EV"
        )
        ui.focusExposureOverlay.onFocusTap = { x, y -> focusAt(x, y) }
        ui.focusExposureOverlay.onExposureChange = { fraction ->
            exposureCompensation = (min + fraction * (max - min)).roundToInt().coerceIn(min, max)
            val progress = if (min < max) (exposureCompensation - min).toFloat() / (max - min) else 0.5f
            ui.focusExposureOverlay.setExposure(progress,
                "%+.1f EV".format(Locale.getDefault(), exposureCompensation * step))
            updatePreview()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLatestPhoto()
    }

    private fun refreshLatestPhoto() {
        val ui = bindingOrNull() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val latest = withContext(Dispatchers.IO) {
                runCatching {
                    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.MIME_TYPE)
                    val selection = if (Build.VERSION.SDK_INT >= 29)
                        "${MediaStore.Images.Media.RELATIVE_PATH}=?" else
                        "${MediaStore.Images.Media.DATA} LIKE ?"
                    val selectionArgs = if (Build.VERSION.SDK_INT >= 29)
                        arrayOf("DCIM/Unprocess/") else arrayOf("%/DCIM/Unprocess/%")
                    val resolver = requireContext().contentResolver
                    resolver.query(collection, projection, selection,
                        selectionArgs, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                            val type = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE))
                            val uri = ContentUris.withAppendedId(collection, id)
                            val thumbnail = if (type == "image/jpeg") null else
                                runCatching { loadDngThumbnail(resolver, uri) }.getOrNull()
                            Triple(uri, type, thumbnail)
                        } else null
                    }
                }.getOrNull()
            }
            if (bindingOrNull() !== ui) return@launch
            Glide.with(ui.latestPhoto).clear(ui.latestPhoto)
            if (latest != null && latest.second == "image/jpeg") {
                ui.latestPhotoLabel.visibility = View.GONE
                Glide.with(ui.latestPhoto).load(latest.first).centerCrop().into(ui.latestPhoto)
            } else if (latest?.third != null) {
                ui.latestPhoto.setImageBitmap(latest.third)
                ui.latestPhotoLabel.visibility = View.GONE
            } else {
                ui.latestPhoto.setImageDrawable(null)
                ui.latestPhotoLabel.text = if (latest == null) "FOTOS" else "RAW"
                ui.latestPhotoLabel.visibility = View.VISIBLE
            }
        }
    }

    private fun refreshFlashButton() {
        val ui = bindingOrNull() ?: return
        val enabled = flashModes.size > 1 && !manualExposureActive()
        val evRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        ui.focusExposureOverlay.setExposureEnabled(
            (evRange?.lower ?: 0) < (evRange?.upper ?: 0) && !manualExposureActive())
        ui.flashButton.isEnabled = enabled
        ui.flashButton.alpha = if (enabled) 1f else 0.45f
        ui.flashButton.text = when {
            manualExposureActive() -> "Flash: —"
            flashModes.size == 1 -> "Sin flash"
            flashModes[flashModeIndex] == CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH -> "Flash: Auto"
            flashModes[flashModeIndex] == CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> "Flash: Sí"
            else -> "Flash: No"
        }
    }

    private fun manualExposureActive(): Boolean =
        proEnabled && manualSensorSupported && (manualIso != null || manualShutterNs != null)

    private fun shutterLimits(): Pair<Long, Long> {
        val range = shutterRange ?: return 1_000_000L to 1_000_000_000L
        val maximumFrame = characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
            ?: range.upper
        val lower = range.lower.coerceAtLeast(1L)
        return lower to range.upper.coerceAtMost(maximumFrame).coerceAtLeast(lower)
    }

    private fun logValue(progress: Int, low: Double, high: Double): Double {
        if (high <= low) return low
        return exp(ln(low) + (ln(high) - ln(low)) * progress.coerceIn(0, 100) / 100.0)
    }

    private fun logProgress(value: Double, low: Double, high: Double): Int {
        if (high <= low) return 0
        return ((ln(value.coerceIn(low, high)) - ln(low)) / (ln(high) - ln(low)) * 100)
            .roundToInt().coerceIn(0, 100)
    }

    private fun formatShutter(nanoseconds: Long): String {
        val seconds = nanoseconds / 1_000_000_000.0
        return if (seconds < 1) "1/${(1 / seconds).roundToInt()}" else
            if (seconds < 10) "%.1fs".format(Locale.getDefault(), seconds) else
                "%.0fs".format(Locale.getDefault(), seconds)
    }

    private fun configureProControls() {
        val ui = fragmentCameraBinding
        if (!manualSensorSupported) selectedProControl =
            if (manualFocusSupported) ProControl.FOCUS else ProControl.WB
        ui.proToggle.setOnClickListener {
            proEnabled = !proEnabled
            renderProControls()
            refreshFlashButton()
            updatePreview()
        }
        ui.isoControl.setOnClickListener { selectedProControl = ProControl.ISO; renderProControls() }
        ui.shutterControl.setOnClickListener { selectedProControl = ProControl.SHUTTER; renderProControls() }
        ui.focusControl.setOnClickListener { selectedProControl = ProControl.FOCUS; renderProControls() }
        ui.wbControl.setOnClickListener { selectedProControl = ProControl.WB; renderProControls() }
        ui.gridControl.setOnClickListener {
            gridEnabled = !gridEnabled
            renderProControls()
        }
        ui.proAuto.setOnClickListener {
            when (selectedProControl) {
                ProControl.ISO -> manualIso = null
                ProControl.SHUTTER -> manualShutterNs = null
                ProControl.FOCUS -> manualFocusDiopters = null
                ProControl.WB -> whiteBalanceMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
            }
            renderProControls()
            refreshFlashButton()
            updatePreview()
        }
        ui.proSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                when (selectedProControl) {
                    ProControl.ISO -> isoRange?.let {
                        manualIso = logValue(progress, it.lower.toDouble(), it.upper.toDouble())
                            .roundToInt().coerceIn(it.lower, it.upper)
                    }
                    ProControl.SHUTTER -> {
                        val (low, high) = shutterLimits()
                        manualShutterNs = logValue(progress, low.toDouble(), high.toDouble())
                            .toLong().coerceIn(low, high)
                    }
                    ProControl.FOCUS -> {
                        val near = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                        manualFocusDiopters = near * progress / 100f
                        focusRegion = null
                    }
                    ProControl.WB -> whiteBalanceMode = whiteBalanceOptions[
                        progress.coerceIn(0, whiteBalanceOptions.lastIndex)].first
                }
                renderProControls()
                refreshFlashButton()
                updatePreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        renderProControls()
    }

    private fun renderProControls() {
        val ui = bindingOrNull() ?: return
        ui.proPanel.visibility = if (proEnabled) View.VISIBLE else View.GONE
        ui.proToggle.setBackgroundResource(if (proEnabled) R.drawable.pro_chip_active else R.drawable.pro_chip_idle)
        ui.proToggle.setTextColor(if (proEnabled) Color.rgb(16, 32, 25) else Color.rgb(216, 237, 176))
        ui.proToggle.contentDescription = if (proEnabled) "Cerrar controles Pro" else "Abrir controles Pro"
        ui.compositionGrid.visibility = if (proEnabled && gridEnabled) View.VISIBLE else View.GONE
        ui.proHint.text = if (manualSensorSupported) "PRO  ·  Controles de captura" else
            "PRO  ·  ISO y obturación no disponibles en este sensor"

        val buttons = listOf(ui.isoControl, ui.shutterControl, ui.focusControl, ui.wbControl)
        buttons.forEachIndexed { index, button ->
            val selected = selectedProControl.ordinal == index
            button.setBackgroundResource(if (selected) R.drawable.pro_chip_active else R.drawable.pro_chip_idle)
            button.setTextColor(if (selected) Color.rgb(16, 32, 25) else Color.rgb(216, 237, 176))
        }
        ui.isoControl.isEnabled = manualSensorSupported
        ui.shutterControl.isEnabled = manualSensorSupported
        ui.focusControl.isEnabled = manualFocusSupported
        ui.wbControl.isEnabled = whiteBalanceOptions.size > 1
        buttons.forEach { it.alpha = if (it.isEnabled) 1f else 0.35f }
        ui.gridControl.setBackgroundResource(if (gridEnabled) R.drawable.pro_chip_active else R.drawable.pro_chip_idle)
        ui.gridControl.setTextColor(if (gridEnabled) Color.rgb(16, 32, 25) else Color.rgb(216, 237, 176))

        when (selectedProControl) {
            ProControl.ISO -> {
                val range = isoRange
                ui.proSlider.isEnabled = manualSensorSupported
                ui.proSlider.max = 100
                ui.proSlider.progress = if (range == null) 0 else logProgress(
                    (manualIso ?: lastAutoIso).toDouble(), range.lower.toDouble(), range.upper.toDouble())
                ui.proValue.text = manualIso?.let { "ISO $it" } ?: "ISO A"
                ui.proDetail.text = if (range == null || !manualSensorSupported) "ISO manual no compatible" else
                    "Rango del sensor: ISO ${range.lower}–${range.upper}"
            }
            ProControl.SHUTTER -> {
                val (low, high) = shutterLimits()
                ui.proSlider.isEnabled = manualSensorSupported
                ui.proSlider.max = 100
                ui.proSlider.progress = logProgress((manualShutterNs ?: lastAutoShutterNs).toDouble(),
                    low.toDouble(), high.toDouble())
                ui.proValue.text = manualShutterNs?.let(::formatShutter) ?: "S A"
                ui.proDetail.text = if (!manualSensorSupported) "Obturación manual no compatible" else
                    if ((manualShutterNs ?: 0L) > 100_000_000L)
                        "Vista previa aproximada; la foto usa el tiempo completo" else
                        "Rango: ${formatShutter(low)} a ${formatShutter(high)}"
            }
            ProControl.FOCUS -> {
                val near = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                ui.proSlider.isEnabled = manualFocusSupported
                ui.proSlider.max = 100
                ui.proSlider.progress = if (near > 0f) ((manualFocusDiopters ?: 0f) / near * 100).roundToInt() else 0
                ui.proValue.text = manualFocusDiopters?.let {
                    if (it == 0f) "MF ∞" else "%.2fm".format(Locale.getDefault(), 1f / it)
                } ?: "MF A"
                ui.proDetail.text = if (manualFocusSupported) "Izquierda: infinito · derecha: cerca" else
                    "Foco manual no compatible"
            }
            ProControl.WB -> {
                ui.proSlider.isEnabled = whiteBalanceOptions.size > 1
                ui.proSlider.max = whiteBalanceOptions.lastIndex
                ui.proSlider.progress = whiteBalanceOptions.indexOfFirst { it.first == whiteBalanceMode }
                    .coerceAtLeast(0)
                ui.proValue.text = whiteBalanceOptions.firstOrNull { it.first == whiteBalanceMode }
                    ?.second ?: "Auto"
                ui.proDetail.text = "Balance de blancos según los perfiles del teléfono"
            }
        }
        ui.proAuto.alpha = if (ui.proSlider.isEnabled) 1f else 0.4f
        ui.proAuto.isEnabled = ui.proSlider.isEnabled
    }

    private fun applyControls(request: CaptureRequest.Builder, stillCapture: Boolean = false) {
        if (manualExposureActive()) {
            val range = isoRange!!
            val (low, high) = shutterLimits()
            val selectedExposure = (manualShutterNs ?: lastAutoShutterNs).coerceIn(low, high)
            val exposure = if (stillCapture) selectedExposure else
                selectedExposure.coerceAtMost(100_000_000L).coerceAtLeast(low)
            val maxFrame = characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
                ?: high
            request.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            request.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            request.set(CaptureRequest.SENSOR_SENSITIVITY,
                (manualIso ?: lastAutoIso).coerceIn(range.lower, range.upper))
            request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            request.set(CaptureRequest.SENSOR_FRAME_DURATION,
                maxOf(exposure, 33_333_333L).coerceAtMost(maxFrame))
        } else {
            request.set(CaptureRequest.CONTROL_AE_MODE, flashModes[flashModeIndex])
            request.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation)
        }
        request.set(CaptureRequest.CONTROL_AWB_MODE,
            if (proEnabled) whiteBalanceMode else CameraMetadata.CONTROL_AWB_MODE_AUTO)
        if (characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
            request.set(CaptureRequest.CONTROL_AWB_LOCK,
                manualExposureActive() && whiteBalanceMode == CameraMetadata.CONTROL_AWB_MODE_AUTO)
        }
        val manualFocus = if (proEnabled) manualFocusDiopters else null
        request.set(CaptureRequest.CONTROL_AF_MODE, when {
            manualFocus != null -> CameraMetadata.CONTROL_AF_MODE_OFF
            focusRegion != null -> CameraMetadata.CONTROL_AF_MODE_AUTO
            else -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        })
        if (manualFocus != null) request.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocus)
        focusRegion?.let { region ->
            if (manualFocus == null &&
                (characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
                request.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }
            if (!manualExposureActive() &&
                (characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
                request.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            }
        }
    }

    private fun focusAt(x: Float, y: Float) {
        if (proEnabled && manualFocusDiopters != null) {
            manualFocusDiopters = null
            renderProControls()
        }
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val finder = fragmentCameraBinding.viewFinder
        if (finder.width == 0 || finder.height == 0) return

        // Camera2 metering regions use sensor coordinates. Account for the centered preview crop
        // and for the rotation between sensor and display before building a region.
        val rotation = (characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!! -
            when (finder.display.rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            } + 360) % 360
        val orientedWidth = if (rotation % 180 == 0) active.width().toFloat() else active.height().toFloat()
        val orientedHeight = if (rotation % 180 == 0) active.height().toFloat() else active.width().toFloat()
        val viewRatio = finder.width.toFloat() / finder.height
        val imageRatio = orientedWidth / orientedHeight
        var nx = ((x - finder.left) / finder.width).coerceIn(0f, 1f)
        var ny = ((y - finder.top) / finder.height).coerceIn(0f, 1f)
        if (imageRatio > viewRatio) {
            val visibleFraction = viewRatio / imageRatio
            nx = (1f - visibleFraction) / 2f + nx * visibleFraction
        } else {
            val visibleFraction = imageRatio / viewRatio
            ny = (1f - visibleFraction) / 2f + ny * visibleFraction
        }
        val (sensorX, sensorY) = when (rotation) {
            90 -> ny to (1f - nx)
            180 -> (1f - nx) to (1f - ny)
            270 -> (1f - ny) to nx
            else -> nx to ny
        }
        val regionWidth = (active.width() * 0.12f).roundToInt().coerceAtLeast(1)
        val regionHeight = (active.height() * 0.12f).roundToInt().coerceAtLeast(1)
        val left = (active.left + sensorX * active.width() - regionWidth / 2)
            .roundToInt().coerceIn(active.left, active.right - regionWidth)
        val top = (active.top + sensorY * active.height() - regionHeight / 2)
            .roundToInt().coerceIn(active.top, active.bottom - regionHeight)
        focusRegion = MeteringRectangle(Rect(left, top, left + regionWidth, top + regionHeight),
            MeteringRectangle.METERING_WEIGHT_MAX)

        if (!sessionReady) return
        try {
            val trigger = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(finder.holder.surface)
                applyControls(this)
            }
            trigger.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            session.capture(trigger.build(), null, cameraHandler)
            trigger.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            session.capture(trigger.build(), null, cameraHandler)
            updatePreview()
        } catch (exc: Exception) {
            Log.w(TAG, "Could not focus at touch point", exc)
        }
    }

    private fun updatePreview() {
        if (sessionReady && ::previewRequest.isInitialized) {
            applyControls(previewRequest)
            session.setRepeatingRequest(previewRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    if (!manualExposureActive()) {
                        result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastAutoIso = it }
                        result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastAutoShutterNs = it }
                    }
                }
            }, cameraHandler)
        }
    }

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating capture request
     * - Sets up the still image capture listeners
     */
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
        // Open the selected camera
        camera = openCamera(cameraManager, args.cameraId, cameraHandler)

        // Initialize an image reader which will be used to capture still photos
        Log.d(TAG, "Initializing image reader")
        Log.d(TAG, CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP.toString())
        val size = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )!!
            .getOutputSizes(args.pixelFormat).maxByOrNull { it.height * it.width }!!
        imageReader = ImageReader.newInstance(
            size.width, size.height, args.pixelFormat, IMAGE_BUFFER_SIZE
        )

        // Creates list of Surfaces where the camera will output frames
        val targets = listOf(fragmentCameraBinding.viewFinder.holder.surface, imageReader.surface)

        // Start a capture session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, targets, cameraHandler)

        previewRequest = camera.createCaptureRequest(
            CameraDevice.TEMPLATE_PREVIEW
        ).apply { addTarget(fragmentCameraBinding.viewFinder.holder.surface) }

        sessionReady = true

        // This will keep sending the capture request as frequently as possible until the
        // session is torn down or session.stopRepeating() is called
        updatePreview()

        // Listen to the capture button
        fragmentCameraBinding.captureButton.setOnClickListener {
            it.isEnabled = false
            fragmentCameraBinding.captureStatus.text = "Guardando…"
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    takePhoto().use { result ->
                        val output = saveResult(result)
                        Log.d(TAG, "Image saved: ${output.absolutePath}")
                    }
                    withContext(Dispatchers.Main) {
                        bindingOrNull()?.captureStatus?.text = "Foto guardada"
                        refreshLatestPhoto()
                    }
                } catch (exc: Exception) {
                    Log.e(TAG, "Unable to save image", exc)
                    withContext(Dispatchers.Main) { bindingOrNull()?.captureStatus?.text = "No se pudo guardar la foto" }
                } finally {
                    it.post { it.isEnabled = true }
                }
            }
        }
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Starts a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        // Create a capture session using the predefined targets; this also involves defining the
        // session state callback to be notified of when the session is ready
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Helper function used to capture a still image using the [CameraDevice.TEMPLATE_STILL_CAPTURE]
     * template. It performs synchronization between the [CaptureResult] and the [Image] resulting
     * from the single capture, and outputs a [CombinedCaptureResult] object.
     */
    private suspend fun takePhoto():
            CombinedCaptureResult = suspendCoroutine { cont ->

        // Flush any images left in the image reader
        @Suppress("ControlFlowWithEmptyBody")
        while (true) {
            val oldImage = imageReader.acquireNextImage() ?: break
            oldImage.close()
        }

        // Start a new image queue
        val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            Log.d(TAG, "Image available in queue: ${image.timestamp}")
            imageQueue.add(image)
        }, imageReaderHandler)

        val captureRequest = session.device.createCaptureRequest(
            CameraDevice.TEMPLATE_STILL_CAPTURE
        ).apply {
            addTarget(imageReader.surface)
            applyControls(this, stillCapture = true)
        }
        session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {

            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long
            ) {
                super.onCaptureStarted(session, request, timestamp, frameNumber)
                bindingOrNull()?.viewFinder?.post(animationTask)
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                super.onCaptureCompleted(session, request, result)
                val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                Log.d(TAG, "Capture result received: $resultTimestamp")

                // Set a timeout in case image captured is dropped from the pipeline
                val exc = TimeoutException("Image dequeuing took too long")
                val timeoutRunnable = Runnable { cont.resumeWithException(exc) }
                imageReaderHandler.postDelayed(timeoutRunnable, IMAGE_CAPTURE_TIMEOUT_MILLIS)

                // Loop in the coroutine's context until an image with matching timestamp comes
                // We need to launch the coroutine context again because the callback is done in
                //  the handler provided to the `capture` method, not in our coroutine context
                @Suppress("BlockingMethodInNonBlockingContext")
                lifecycleScope.launch(cont.context) {
                    while (true) {

                        // Dequeue images while timestamps don't match
                        val image = imageQueue.take()
                        // TODO(owahltinez): b/142011420
                        // if (image.timestamp != resultTimestamp) continue
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            image.format != ImageFormat.DEPTH_JPEG &&
                            image.timestamp != resultTimestamp
                        ) {
                            image.close()
                            continue
                        }
                        Log.d(TAG, "Matching image dequeued: ${image.timestamp}")

                        // Unset the image reader listener
                        imageReaderHandler.removeCallbacks(timeoutRunnable)
                        imageReader.setOnImageAvailableListener(null, null)

                        // Clear the queue of images, if there are left
                        while (imageQueue.size > 0) {
                            imageQueue.take().close()
                        }

                        // Compute EXIF orientation metadata
                        val rotation = relativeOrientation.value ?: 0
                        val mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                                CameraCharacteristics.LENS_FACING_FRONT
                        val exifOrientation = computeExifOrientation(rotation, mirrored)

                        // Build the result and resume progress
                        cont.resume(
                            CombinedCaptureResult(
                                image, result, exifOrientation, imageReader.imageFormat
                            )
                        )
                        return@launch
                    }
                }
            }
        }, cameraHandler)
    }

    /** Helper function used to save a [CombinedCaptureResult] into a [File] */
    private suspend fun saveResult(result: CombinedCaptureResult): File = suspendCoroutine { cont ->
        when (result.format) {
            // Only expecting RAW sensor data
            ImageFormat.RAW_SENSOR -> {
                try {
                    if (args.convertToJpeg) {
                        val bitmap = RawJpegConverter.convert(result.image, characteristics, result.metadata)

                        // Save as JPEG
                        val filename = "IMG_${
                            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                                .format(Date())
                        }.jpg"

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val contentValues = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                                put(
                                    MediaStore.MediaColumns.RELATIVE_PATH,
                                    "${Environment.DIRECTORY_DCIM}/Unprocess"
                                )
                            }

                            val resolver = requireContext().contentResolver
                            val uri = resolver.insert(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                contentValues
                            ) ?: throw IOException("Failed to create MediaStore entry")

                            (resolver.openOutputStream(uri) ?: throw IOException("No se pudo abrir la foto")).use { stream ->
                                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream))
                                    throw IOException("Falló la compresión JPG")
                            }

                            // Add EXIF orientation data using the URI
                            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                                ExifInterface(pfd.fileDescriptor).apply {
                                    setAttribute(ExifInterface.TAG_ORIENTATION, result.orientation.toString())
                                    saveAttributes()
                                }
                            }

                            // Create a reference file in the DCIM directory
                            val dcim = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DCIM
                            )
                            val appFolder = File(dcim, "Unprocess")
                            val savedFile = File(appFolder, filename)
                            cont.resume(savedFile)
                        } else {
                            val dcim = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DCIM
                            )
                            val appFolder = File(dcim, "Unprocess").apply {
                                if (!exists()) mkdirs()
                            }
                            val file = File(appFolder, filename)

                            FileOutputStream(file).use { stream ->
                                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream))
                                    throw IOException("Falló la compresión JPG")
                            }

                            // Add EXIF orientation data
                            ExifInterface(file.absolutePath).apply {
                                setAttribute(ExifInterface.TAG_ORIENTATION, result.orientation.toString())
                                saveAttributes()
                            }

                            MediaScannerConnection.scanFile(requireContext(), arrayOf(file.absolutePath),
                                arrayOf("image/jpeg"), null)

                            cont.resume(file)
                        }

                        bitmap.recycle()
                    } else {
                        val dngCreator = DngCreator(characteristics, result.metadata)
                        dngCreator.setOrientation(result.orientation)
                        var thumbnail: Bitmap? = null
                        runCatching {
                            thumbnail = RawJpegConverter.convert(result.image, characteristics,
                                result.metadata, DngCreator.MAX_THUMBNAIL_DIMENSION)
                            dngCreator.setThumbnail(thumbnail!!)
                        }.onFailure {
                            thumbnail?.recycle()
                            thumbnail = null
                            Log.w(TAG, "DNG thumbnail unavailable", it)
                        }
                        val filename = "RAW_${
                            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                                .format(Date())
                        }.dng"

                        try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            // Android 10 and above: Use MediaStore
                            val contentValues = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                                put(
                                    MediaStore.MediaColumns.RELATIVE_PATH,
                                    "${Environment.DIRECTORY_DCIM}/Unprocess"
                                )
                            }

                            val resolver = requireContext().contentResolver
                            val uri = resolver.insert(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                contentValues
                            ) ?: throw IOException("Failed to create MediaStore entry")

                            val outputStream = resolver.openOutputStream(uri)
                                ?: throw IOException("Failed to open output stream")

                            outputStream.use { stream ->
                                dngCreator.writeImage(stream, result.image)
                            }

                            // Create a reference file in the DCIM directory
                            val dcim = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DCIM
                            )
                            val appFolder = File(dcim, "Unprocess")
                            val savedFile = File(appFolder, filename)
                            cont.resume(savedFile)

                        } else {
                            // Below Android 10: Use direct file access
                            val dcim = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DCIM
                            )
                            val appFolder = File(dcim, "Unprocess").apply {
                                if (!exists()) {
                                    mkdirs()
                                }
                            }
                            val file = File(appFolder, filename)

                            FileOutputStream(file).use { outputStream ->
                                dngCreator.writeImage(outputStream, result.image)
                            }
                            MediaScannerConnection.scanFile(requireContext(), arrayOf(file.absolutePath),
                                arrayOf("image/x-adobe-dng"), null)
                            cont.resume(file)
                        }
                        } finally {
                            thumbnail?.recycle()
                            dngCreator.close()
                        }
                    }

                } catch (exc: Exception) {
                    Log.e(TAG, "Unable to write image to external storage", exc)
                    cont.resumeWithException(exc)
                }
            }

            // No other formats are supported
            else -> {
                val exc = RuntimeException("Unknown image format: ${result.image.format}")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        sessionReady = false
        try {
            if (::session.isInitialized) session.close()
            if (::camera.isInitialized) camera.close()
            if (::imageReader.isInitialized) imageReader.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        imageReaderThread.quitSafely()
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
    }

    private fun bindingOrNull(): FragmentCameraBinding? = _fragmentCameraBinding

    companion object {
        private val TAG = CameraFragment::class.java.simpleName

        /** Maximum number of images that will be held in the reader's buffer */
        private const val IMAGE_BUFFER_SIZE: Int = 3

        /** Maximum time allowed to wait for the result of an image capture */
        private const val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000

        /** Helper data class used to hold capture metadata with their associated image */
        data class CombinedCaptureResult(
            val image: Image,
            val metadata: CaptureResult,
            val orientation: Int,
            val format: Int
        ) : Closeable {
            override fun close() = image.close()
        }

        /**
         * Create a [File] named a using formatted timestamp with the current date and time.
         *
         * @return [File] created.
         */
        private fun createFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            return File(context.filesDir, "IMG_${sdf.format(Date())}.$extension")
        }
    }
}

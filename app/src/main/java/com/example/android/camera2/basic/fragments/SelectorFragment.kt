package com.reilandeubank.unprocess.fragments

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Bundle
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import com.reilandeubank.unprocess.databinding.FragmentSelectorBinding

/** The two output choices share the same RAW capture pipeline. */
class SelectorFragment : Fragment() {
    private var binding: FragmentSelectorBinding? = null
    private var pendingCameraId: String? = null
    private var pendingJpeg = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        return FragmentSelectorBinding.inflate(inflater, container, false).also { binding = it }.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val ui = binding ?: return
        val manager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            val info = manager.getCameraCharacteristics(id)
            val capabilities = info.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val formats = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.outputFormats
            info.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true &&
                formats?.contains(ImageFormat.RAW_SENSOR) == true
        }
        if (cameraId == null) {
            ui.deviceMessage.visibility = View.VISIBLE
            ui.rawOption.isEnabled = false
            ui.jpgOption.isEnabled = false
            ui.rawOption.alpha = 0.4f
            ui.jpgOption.alpha = 0.4f
        } else {
            ui.deviceMessage.visibility = View.GONE
            ui.rawOption.setOnClickListener { openCamera(cameraId, false) }
            ui.jpgOption.setOnClickListener { openCamera(cameraId, true) }
        }
        ui.galleryOption.setOnClickListener {
            findNavController().navigate(SelectorFragmentDirections.actionSelectorToGallery())
        }
    }

    private fun openCamera(cameraId: String, convertToJpeg: Boolean) {
        val required = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT < 29) required.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val missing = required.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            pendingCameraId = cameraId
            pendingJpeg = convertToJpeg
            requestPermissions(missing.toTypedArray(), 41)
            return
        }
        findNavController().navigate(
            SelectorFragmentDirections.actionSelectorToCamera(cameraId, ImageFormat.RAW_SENSOR, convertToJpeg)
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            pendingCameraId?.let { openCamera(it, pendingJpeg) }
        }
        pendingCameraId = null
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}

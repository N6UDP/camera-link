package com.example.cameralink

import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider

/**
 * Maps a [CameraLens] choice to a CameraX [CameraSelector] by inspecting the physical
 * back cameras' focal lengths (shortest = ultra-wide, longest = telephoto). Falls back
 * gracefully to the default camera when a requested lens isn't available.
 */
object CameraLensResolver {
    private const val TAG = "CameraLensResolver"

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun cameraId(info: CameraInfo): String =
        Camera2CameraInfo.from(info).cameraId

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun minFocalLength(info: CameraInfo): Float? =
        try {
            Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.minOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read focal lengths: ${e.message}")
            null
        }

    /**
     * True only for regular color cameras that can produce a normal preview/analysis stream.
     * Excludes auxiliary sensors that phones expose as extra back cameras - monochrome (produces
     * black-and-white frames), near-infrared, and depth-only sensors - which must never be picked
     * as the ultra-wide/telephoto lens.
     */
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun isSelectableColorCamera(info: CameraInfo): Boolean =
        try {
            val c2 = Camera2CameraInfo.from(info)

            val caps = c2.getCameraCharacteristic(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            )
            val backwardCompatible = caps?.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE
            ) ?: false

            val colorArrangement = c2.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
            )
            val isMonoOrNir = colorArrangement ==
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO ||
                colorArrangement ==
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR

            backwardCompatible && !isMonoOrNir
        } catch (e: Exception) {
            Log.w(TAG, "Could not read camera capabilities: ${e.message}")
            // If we can't tell, keep it rather than hide a usable lens.
            true
        }

    /** Back cameras sorted by ascending focal length (ultra-wide first, telephoto last). */
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun sortedBackCameras(provider: ProcessCameraProvider): List<CameraInfo> =
        provider.availableCameraInfos
            .filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            .filter { isSelectableColorCamera(it) }
            .mapNotNull { info -> minFocalLength(info)?.let { info to it } }
            .sortedBy { it.second }
            .map { it.first }

    private fun hasFront(provider: ProcessCameraProvider): Boolean =
        provider.availableCameraInfos.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }

    /** The set of lenses that actually exist on this device. */
    fun availableLenses(provider: ProcessCameraProvider): Set<CameraLens> {
        val result = linkedSetOf<CameraLens>()
        val back = sortedBackCameras(provider)
        if (back.isNotEmpty()) result.add(CameraLens.WIDE)
        if (back.size >= 2) result.add(CameraLens.ULTRA_WIDE)
        if (back.size >= 3) result.add(CameraLens.TELEPHOTO)
        if (hasFront(provider)) result.add(CameraLens.FRONT)
        return result
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    fun selectorFor(provider: ProcessCameraProvider, lens: CameraLens): CameraSelector {
        if (lens == CameraLens.FRONT) return CameraSelector.DEFAULT_FRONT_CAMERA

        val back = sortedBackCameras(provider)
        // WIDE / main, or any case we can't distinguish -> let CameraX pick the default back lens.
        if (back.size <= 1 || lens == CameraLens.WIDE) return CameraSelector.DEFAULT_BACK_CAMERA

        val target = when (lens) {
            CameraLens.ULTRA_WIDE -> back.first()
            CameraLens.TELEPHOTO -> back.last()
            else -> return CameraSelector.DEFAULT_BACK_CAMERA
        }
        val targetId = cameraId(target)

        return CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter { infos -> infos.filter { cameraId(it) == targetId } }
            .build()
    }
}

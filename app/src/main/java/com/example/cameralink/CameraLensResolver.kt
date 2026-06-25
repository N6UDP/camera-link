package com.example.cameralink

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
 *
 * Two safeguards keep monochrome auxiliary sensors from being picked as a usable lens:
 *  1. Ultra-wide / telephoto are only offered when a camera's focal length is meaningfully
 *     shorter / longer than the main (default) back camera. Devices without a real telephoto
 *     (e.g. OnePlus 8T) therefore won't expose a bogus "telephoto".
 *  2. Logical multi-cameras whose physical sub-sensors include a monochrome / near-infrared
 *     sensor are excluded from ultra-wide / telephoto selection, because such a logical camera
 *     can stream grayscale frames even though it reports a color filter at the logical level.
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

    private fun isMonoOrNir(cfa: Int?): Boolean =
        cfa == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO ||
            cfa == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR

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

            backwardCompatible && !isMonoOrNir(colorArrangement)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read camera capabilities: ${e.message}")
            // If we can't tell, keep it rather than hide a usable lens.
            true
        }

    /**
     * True if [cameraId] is a logical multi-camera that bundles a monochrome / near-infrared
     * physical sensor. Such a logical camera can emit grayscale frames, so it must not be used
     * for ultra-wide / telephoto. Returns false (don't exclude) when characteristics can't be read.
     */
    private fun hasExcludedPhysicalSensor(cm: CameraManager, cameraId: String): Boolean =
        try {
            val physicalIds = cm.getCameraCharacteristics(cameraId).physicalCameraIds
            physicalIds.any { physId ->
                try {
                    val cfa = cm.getCameraCharacteristics(physId)
                        .get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                    isMonoOrNir(cfa)
                } catch (e: Exception) {
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read physical sensors for camera $cameraId: ${e.message}")
            false
        }

    private fun cameraManager(context: Context): CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** Color back cameras (logical-level), regardless of physical sub-sensor makeup. */
    private fun backColorCameras(provider: ProcessCameraProvider): List<CameraInfo> =
        provider.availableCameraInfos
            .filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            .filter { isSelectableColorCamera(it) }

    /** Focal length of the main (default) back camera, used as the ultra-wide/telephoto pivot. */
    private fun mainBackFocal(backColor: List<CameraInfo>): Float? =
        CameraSelector.DEFAULT_BACK_CAMERA.filter(backColor)
            .firstOrNull()
            ?.let { minFocalLength(it) }

    /**
     * Back cameras usable as a distinct ultra-wide/telephoto lens, sorted by ascending focal
     * length. Excludes logical cameras tied to a monochrome/NIR physical sensor (safeguard #2).
     */
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun lensCandidates(
        cm: CameraManager,
        backColor: List<CameraInfo>
    ): List<Pair<CameraInfo, Float>> =
        backColor
            .filter { !hasExcludedPhysicalSensor(cm, cameraId(it)) }
            .mapNotNull { info -> minFocalLength(info)?.let { info to it } }
            .sortedBy { it.second }

    /** The ultra-wide camera, i.e. a candidate with focal strictly shorter than the main. */
    private fun ultraWideCamera(
        candidates: List<Pair<CameraInfo, Float>>,
        mainFocal: Float?
    ): CameraInfo? {
        if (mainFocal == null) return null
        val shortest = candidates.firstOrNull() ?: return null
        return if (shortest.second < mainFocal) shortest.first else null
    }

    /** The telephoto camera, i.e. a candidate with focal strictly longer than the main. */
    private fun telephotoCamera(
        candidates: List<Pair<CameraInfo, Float>>,
        mainFocal: Float?
    ): CameraInfo? {
        if (mainFocal == null) return null
        val longest = candidates.lastOrNull() ?: return null
        return if (longest.second > mainFocal) longest.first else null
    }

    private fun hasFront(provider: ProcessCameraProvider): Boolean =
        provider.availableCameraInfos.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }

    /** The set of lenses that actually exist on this device. */
    fun availableLenses(context: Context, provider: ProcessCameraProvider): Set<CameraLens> {
        val cm = cameraManager(context)
        val backColor = backColorCameras(provider)
        val mainFocal = mainBackFocal(backColor)
        val candidates = lensCandidates(cm, backColor)

        val result = linkedSetOf<CameraLens>()
        if (backColor.isNotEmpty()) result.add(CameraLens.WIDE)
        if (ultraWideCamera(candidates, mainFocal) != null) result.add(CameraLens.ULTRA_WIDE)
        if (telephotoCamera(candidates, mainFocal) != null) result.add(CameraLens.TELEPHOTO)
        if (hasFront(provider)) result.add(CameraLens.FRONT)
        return result
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    fun selectorFor(
        context: Context,
        provider: ProcessCameraProvider,
        lens: CameraLens
    ): CameraSelector {
        if (lens == CameraLens.FRONT) return CameraSelector.DEFAULT_FRONT_CAMERA
        if (lens == CameraLens.WIDE) return CameraSelector.DEFAULT_BACK_CAMERA

        val cm = cameraManager(context)
        val backColor = backColorCameras(provider)
        val mainFocal = mainBackFocal(backColor)
        val candidates = lensCandidates(cm, backColor)

        val target = when (lens) {
            CameraLens.ULTRA_WIDE -> ultraWideCamera(candidates, mainFocal)
            CameraLens.TELEPHOTO -> telephotoCamera(candidates, mainFocal)
            else -> null
        } ?: return CameraSelector.DEFAULT_BACK_CAMERA

        val targetId = cameraId(target)

        return CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter { infos -> infos.filter { cameraId(it) == targetId } }
            .build()
    }
}

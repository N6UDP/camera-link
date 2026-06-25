package com.example.cameralink

import android.content.Context
import android.util.Size

/** Which physical lens to stream from. */
enum class CameraLens(val id: String, val label: String) {
    ULTRA_WIDE("ultrawide", "Ultra-wide"),
    WIDE("wide", "Wide (main)"),
    TELEPHOTO("telephoto", "Telephoto"),
    FRONT("front", "Front");

    companion object {
        fun fromId(value: String?): CameraLens? {
            val v = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.id == v }
                ?: when (v) {
                    "normal", "main", "back" -> WIDE
                    "ultra", "uw", "0.5x" -> ULTRA_WIDE
                    "tele", "zoom" -> TELEPHOTO
                    "selfie" -> FRONT
                    else -> null
                }
        }
    }
}

/** Stream capture resolution. */
enum class StreamResolution(val id: String, val size: Size, val label: String) {
    VGA("480p", Size(640, 480), "480p (640×480)"),
    HD("720p", Size(1280, 720), "720p (1280×720)"),
    FHD("1080p", Size(1920, 1080), "1080p (1920×1080)");

    companion object {
        fun fromId(value: String?): StreamResolution? {
            val v = value?.trim()?.lowercase()?.removeSuffix("p") ?: return null
            return entries.firstOrNull {
                it.id.removeSuffix("p") == v || it.size.height.toString() == v
            }
        }
    }
}

/**
 * Persistent, app-wide camera/stream configuration.
 *
 * Capture-affecting changes (lens, resolution) invoke [onChanged] so the running
 * [CameraStreamingService] can rebind the camera live. JPEG quality is read directly
 * by [StreamingServer] when encoding frames, so it takes effect on the next frame.
 */
object CameraSettings {
    private const val PREFS_NAME = "camera_settings"
    private const val KEY_LENS = "lens"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_QUALITY = "jpeg_quality"

    const val DEFAULT_QUALITY = 80
    val DEFAULT_LENS = CameraLens.WIDE
    val DEFAULT_RESOLUTION = StreamResolution.HD

    private var appContext: Context? = null

    @Volatile var lens: CameraLens = DEFAULT_LENS
        private set
    @Volatile var resolution: StreamResolution = DEFAULT_RESOLUTION
        private set
    @Volatile var jpegQuality: Int = DEFAULT_QUALITY
        private set

    /**
     * Lenses actually usable on this device, populated by [CameraStreamingService] once the
     * camera provider is available. Defaults to all lenses until then.
     */
    @Volatile var availableLenses: Set<CameraLens> = CameraLens.entries.toSet()

    /** Invoked when a capture-affecting setting (lens/resolution) changes. */
    var onChanged: (() -> Unit)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = prefs() ?: return
        lens = CameraLens.fromId(prefs.getString(KEY_LENS, null)) ?: DEFAULT_LENS
        resolution = StreamResolution.fromId(prefs.getString(KEY_RESOLUTION, null)) ?: DEFAULT_RESOLUTION
        jpegQuality = prefs.getInt(KEY_QUALITY, DEFAULT_QUALITY).coerceIn(1, 100)
    }

    private fun prefs() =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setLens(value: CameraLens) {
        if (value == lens) return
        lens = value
        prefs()?.edit()?.putString(KEY_LENS, value.id)?.apply()
        onChanged?.invoke()
    }

    fun setResolution(value: StreamResolution) {
        if (value == resolution) return
        resolution = value
        prefs()?.edit()?.putString(KEY_RESOLUTION, value.id)?.apply()
        onChanged?.invoke()
    }

    fun setJpegQuality(value: Int) {
        val clamped = value.coerceIn(1, 100)
        if (clamped == jpegQuality) return
        jpegQuality = clamped
        prefs()?.edit()?.putInt(KEY_QUALITY, clamped)?.apply()
        // No rebind needed; the encoder reads jpegQuality per frame.
    }
}

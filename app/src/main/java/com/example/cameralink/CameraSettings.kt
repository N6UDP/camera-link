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
    private const val KEY_AUTO_FLASH = "auto_flash"
    private const val KEY_AUTO_FLASH_THRESHOLD = "auto_flash_threshold"
    private const val KEY_FLASH_STRENGTH = "flash_strength"
    private const val KEY_ACCESS_KEY = "access_key"

    const val DEFAULT_QUALITY = 80
    const val DEFAULT_AUTO_FLASH = false
    const val DEFAULT_AUTO_FLASH_THRESHOLD = 40
    const val MIN_AUTO_FLASH_THRESHOLD = 5
    const val MAX_AUTO_FLASH_THRESHOLD = 150
    const val DEFAULT_FLASH_STRENGTH = 100
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
     * When enabled, the streaming service turns the camera torch on automatically while the
     * scene is too dark (and the active lens has a flash unit). Read live by the frame
     * analyzer, so it takes effect without a camera rebind.
     */
    @Volatile var autoFlash: Boolean = DEFAULT_AUTO_FLASH
        private set

    /**
     * Average luma (0..255) below which auto-flash considers the scene "dark" and turns the
     * torch on. The off-threshold is derived from this with a fixed gap to preserve hysteresis.
     * Adjustable because the right value is position/scene dependent.
     */
    @Volatile var autoFlashThreshold: Int = DEFAULT_AUTO_FLASH_THRESHOLD
        private set

    /**
     * Desired torch brightness as a percentage (1..100) of the device's maximum torch strength.
     * Only has an effect on devices that support torch strength control (see [maxTorchLevel]).
     */
    @Volatile var flashStrengthPercent: Int = DEFAULT_FLASH_STRENGTH
        private set

    /**
     * Optional shared access key. When non-empty, the HTTP server requires clients to present
     * this key (via `?key=` or the `X-Access-Key` header) for non-loopback requests.
     */
    @Volatile var accessKey: String = ""
        private set

    /**
     * Maximum torch strength level reported by the active camera, populated by
     * [CameraStreamingService] after binding. 0 = unknown (not yet queried), values <= 1 mean the
     * device does not support adjustable torch brightness. > 1 means brightness is adjustable.
     */
    @Volatile var maxTorchLevel: Int = 0

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
        autoFlash = prefs.getBoolean(KEY_AUTO_FLASH, DEFAULT_AUTO_FLASH)
        autoFlashThreshold = prefs.getInt(KEY_AUTO_FLASH_THRESHOLD, DEFAULT_AUTO_FLASH_THRESHOLD)
            .coerceIn(MIN_AUTO_FLASH_THRESHOLD, MAX_AUTO_FLASH_THRESHOLD)
        flashStrengthPercent = prefs.getInt(KEY_FLASH_STRENGTH, DEFAULT_FLASH_STRENGTH).coerceIn(1, 100)
        accessKey = prefs.getString(KEY_ACCESS_KEY, "") ?: ""
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

    fun setAutoFlash(value: Boolean) {
        if (value == autoFlash) return
        autoFlash = value
        prefs()?.edit()?.putBoolean(KEY_AUTO_FLASH, value)?.apply()
        // No rebind needed; the frame analyzer reads autoFlash live and toggles the torch.
        // When turned off, the analyzer turns the torch off on its next frame.
    }

    fun setAutoFlashThreshold(value: Int) {
        val clamped = value.coerceIn(MIN_AUTO_FLASH_THRESHOLD, MAX_AUTO_FLASH_THRESHOLD)
        if (clamped == autoFlashThreshold) return
        autoFlashThreshold = clamped
        prefs()?.edit()?.putInt(KEY_AUTO_FLASH_THRESHOLD, clamped)?.apply()
        // No rebind; the analyzer reads autoFlashThreshold live.
    }

    fun setFlashStrengthPercent(value: Int) {
        val clamped = value.coerceIn(1, 100)
        if (clamped == flashStrengthPercent) return
        flashStrengthPercent = clamped
        prefs()?.edit()?.putInt(KEY_FLASH_STRENGTH, clamped)?.apply()
        // No rebind; the analyzer applies the new strength to the torch live.
    }

    fun setAccessKey(value: String) {
        val trimmed = value.trim()
        if (trimmed == accessKey) return
        accessKey = trimmed
        prefs()?.edit()?.putString(KEY_ACCESS_KEY, trimmed)?.apply()
        // Read live by StreamingServer on each request; no restart needed.
    }
}

package com.example.cameralink

import android.content.Context

/**
 * Persistent storage for the optional Tailscale keep-alive feature.
 *
 * The feature is OFF by default: CameraLink is intended for local-network use only,
 * so nothing is pinged and no background service runs unless the user explicitly
 * enables it and adds their own peers.
 */
object TailscalePrefs {
    private const val PREFS_NAME = "tailscale_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PEERS = "peers"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether the user has opted in to the Tailscale keep-alive service. Default: false. */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** User-configured peers to ping. Default: empty (no hardcoded/third-party hosts). */
    fun getPeers(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PEERS, emptySet())?.toSet() ?: emptySet()

    fun setPeers(context: Context, peers: Set<String>) {
        prefs(context).edit().putStringSet(KEY_PEERS, peers).apply()
    }
}

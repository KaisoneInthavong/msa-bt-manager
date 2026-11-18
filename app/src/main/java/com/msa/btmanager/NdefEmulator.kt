package com.msa.btmanager

import android.content.Context
import android.util.Base64

/**
 * Holds the NDEF message that [NdefEmulatorService] presents to readers.
 *
 * Kept in a singleton (same process as the service) and mirrored into
 * SharedPreferences so it survives the service being created later or the
 * activity being recreated.
 */
object NdefEmulator {

    private const val PREFS = "ndef_emulator"
    private const val KEY_MESSAGE = "message"

    /** Largest NDEF file we advertise in the Type 4 Tag capability container. */
    const val MAX_NDEF_BYTES = 1024

    @Volatile
    private var cached: ByteArray? = null

    fun save(context: Context, message: ByteArray) {
        cached = message
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MESSAGE, Base64.encodeToString(message, Base64.NO_WRAP))
            .apply()
    }

    fun clear(context: Context) {
        cached = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_MESSAGE)
            .apply()
    }

    fun load(context: Context): ByteArray? {
        cached?.let { return it }
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MESSAGE, null) ?: return null
        return try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

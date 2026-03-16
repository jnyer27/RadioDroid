package com.radiodroid.app.radio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages custom CHIRP driver .py files sideloaded by the user.
 *
 * Responsibilities:
 *  - Copy a user-selected URI into app-private internal storage under
 *    `filesDir/custom_drivers/` so the Python runtime can read it at any path.
 *  - Persist the set of imported filenames in SharedPreferences so they survive
 *    app restarts and can be re-registered on next launch.
 *  - Enumerate and remove previously imported drivers.
 *
 * The actual Python import is done by [com.radiodroid.app.bridge.ChirpBridge.loadCustomDriver].
 * This class only handles file lifecycle; it has no Python dependency.
 */
class CustomDriverManager(private val context: Context) {

    /** App-private directory where imported .py driver files are stored. */
    val driversDir: File = File(context.filesDir, "custom_drivers").also { it.mkdirs() }

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    // ─────────────────────────────────────────────────────────────────────────
    // Import
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Copy the file at [uri] into [driversDir] and persist its name.
     *
     * Must be called from a coroutine (uses [Dispatchers.IO] internally).
     *
     * @return The resulting [File] in internal storage, ready to pass to Python.
     * @throws IllegalArgumentException if the URI cannot be opened or has no content.
     */
    suspend fun importDriver(uri: Uri): File = withContext(Dispatchers.IO) {
        val fileName = resolveFileName(uri)
        val dest = File(driversDir, fileName)

        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open URI: $uri")

        inputStream.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }

        persistDriver(fileName)
        dest
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Query
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Return all previously imported driver files that still exist on disk,
     * sorted by filename.
     */
    fun listDriverFiles(): List<File> {
        val saved = prefs.getStringSet(PREF_DRIVER_FILES, emptySet()) ?: emptySet()
        return saved
            .mapNotNull { name -> File(driversDir, name).takeIf { it.exists() } }
            .sortedBy { it.name }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Remove
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Delete a previously imported driver from disk and remove it from prefs.
     * The corresponding radio model will no longer appear after the next app restart.
     */
    fun removeDriver(file: File) {
        file.delete()
        val current = prefs.getStringSet(PREF_DRIVER_FILES, mutableSetOf())
            ?.toMutableSet() ?: mutableSetOf()
        current.remove(file.name)
        prefs.edit().putStringSet(PREF_DRIVER_FILES, current).apply()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internals
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Attempt to read the display name from the ContentResolver; fall back to
     * a timestamped default.  Sanitises the name to safe filesystem characters.
     */
    private fun resolveFileName(uri: Uri): String {
        val rawName: String? = context.contentResolver
            .query(uri, null, null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
            }

        val safeName = (rawName ?: "custom_driver_${System.currentTimeMillis()}.py")
            .replace(Regex("[^a-zA-Z0-9._\\-]"), "_")

        // Ensure the extension is .py so Python can import it
        return if (safeName.endsWith(".py")) safeName else "$safeName.py"
    }

    private fun persistDriver(fileName: String) {
        val current = prefs.getStringSet(PREF_DRIVER_FILES, mutableSetOf())
            ?.toMutableSet() ?: mutableSetOf()
        current.add(fileName)
        prefs.edit().putStringSet(PREF_DRIVER_FILES, current).apply()
    }

    companion object {
        private const val PREFS_FILE        = "radiodroid_custom_drivers"
        private const val PREF_DRIVER_FILES = "driver_files"
    }
}

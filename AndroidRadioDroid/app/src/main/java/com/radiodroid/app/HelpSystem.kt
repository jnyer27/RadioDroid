package com.radiodroid.app

import android.content.Context
import androidx.appcompat.app.AlertDialog
import org.xmlpull.v1.XmlPullParser

/**
 * Loads help content from res/xml/help_content.xml and displays per-setting
 * help dialogs throughout the app.
 *
 * Uses Android's built-in XmlPullParser via Resources.getXml() —
 * zero external dependencies, works on every Android API level.
 *
 * The XML resource is generated from docs/help_reference/help_content.yaml
 * by running:  python scripts/yaml_to_xml.py
 *
 * Call [init] once per Activity (it is idempotent) then call [show] from any
 * help-button click listener.
 */
object HelpSystem {

    data class HelpEntry(
        val title: String,
        val range: String?,
        val default: String?,
        val description: String,
        val notes: String?
    )

    @Volatile
    private var entries: Map<String, HelpEntry> = emptyMap()

    /**
     * Loads and parses res/xml/help_content.xml.  Safe to call multiple times —
     * skips reload if already loaded.
     */
    fun init(context: Context) {
        if (entries.isNotEmpty()) return
        try {
            val parser = context.resources.getXml(R.xml.help_content)
            val map    = mutableMapOf<String, HelpEntry>()

            var key          = ""
            var title        = ""
            var range        : String? = null
            var entryDefault : String? = null
            val descBuf      = StringBuilder()
            val notesBuf     = StringBuilder()
            var currentTag   = ""
            var inEntry      = false

            var ev = parser.next()                      // skip START_DOCUMENT
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        if (parser.name == "entry") {
                            inEntry      = true
                            key          = parser.getAttributeValue(null, "key") ?: ""
                            title        = parser.getAttributeValue(null, "title") ?: key
                            range        = parser.getAttributeValue(null, "range")
                                               ?.takeIf { it.isNotEmpty() }
                            entryDefault = parser.getAttributeValue(null, "default")
                                               ?.takeIf { it.isNotEmpty() }
                            descBuf.clear()
                            notesBuf.clear()
                        }
                    }
                    XmlPullParser.TEXT -> if (inEntry) when (currentTag) {
                        "description" -> descBuf.append(parser.text)
                        "notes"       -> notesBuf.append(parser.text)
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "description", "notes" -> currentTag = "entry"
                        "entry" -> {
                            if (key.isNotEmpty()) {
                                map[key] = HelpEntry(
                                    title       = title,
                                    range       = range,
                                    default     = entryDefault,
                                    description = descBuf.toString().trim(),
                                    notes       = notesBuf.toString().trim().ifEmpty { null }
                                )
                            }
                            inEntry = false
                        }
                    }
                }
                ev = parser.next()
            }
            parser.close()
            entries = map
        } catch (e: Exception) {
            // Don't crash the app if help content fails to load
        }
    }

    /**
     * Shows an AlertDialog with help content for [key].
     * Silently no-ops if the key is not found or [init] hasn't been called.
     */
    fun show(context: Context, key: String) {
        val e = entries[key] ?: return
        val message = buildString {
            e.range?.let   { appendLine("Range: $it") }
            e.default?.let { appendLine("Default: $it") }
            if (e.range != null || e.default != null) appendLine()
            append(e.description)
            e.notes?.let { append("\n\n\u26a0 $it") }   // ⚠
        }
        AlertDialog.Builder(context)
            .setTitle("\uD83D\uDCA1 ${e.title}")          // 💡
            .setMessage(message.trim())
            .setPositiveButton("Got it", null)
            .show()
    }
}

package com.radiodroid.app.radio

import android.content.Context
import android.content.SharedPreferences
import com.radiodroid.app.model.RadioInfo
import org.json.JSONObject

/**
 * Per-driver mapping between CHIRP driver mode strings and the app's
 * universal display/edit representation for Mode and Bandwidth.
 *
 * Keys in [modeMap] / [bandwidthMap] are driver-specific mode strings
 * (as reported in RadioFeatures.validModes and Memory.mode), values are
 * the universal strings used by the UI.
 */
data class ParamMapping(
    val modeMap: Map<String, String>,
    val bandwidthMap: Map<String, String>,
) {
    /** Returns the first driver mode that maps to [universalMode], or null if none. */
    fun reverseMode(universalMode: String): String? =
        modeMap.entries.firstOrNull { it.value == universalMode }?.key

    companion object {
        /** Built-in fallback when there is no stored mapping. */
        val DEFAULT = ParamMapping(
            modeMap = emptyMap(),
            bandwidthMap = emptyMap(),
        )
    }
}

/**
 * Central access point for parameter mappings. Uses a small SharedPreferences
 * file to store one global default mapping plus optional per-model overrides.
 *
 * Keys:
 *  - param_mapping_default           — JSON blob for the default mapping
 *  - param_mapping_<vendor>_<model> — JSON blob for a specific radio model
 */
object ParamMappingStore {

    private const val PREFS_FILE = "radiodroid_param_mapping"
    private const val KEY_DEFAULT = "param_mapping_default"
    private const val KEY_PREFIX_MODEL = "param_mapping_"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /**
     * Returns the active mapping for [radio], or the global default mapping if
     * there is no per-model override. If neither exists, returns [ParamMapping.DEFAULT].
     */
    fun getMapping(context: Context, radio: RadioInfo?): ParamMapping {
        val p = prefs(context)
        // Per-model override
        radio?.let {
            val modelKey = modelKey(it)
            p.getString(modelKey, null)?.let { json ->
                parse(json)?.let { return it }
            }
        }
        // Global default
        p.getString(KEY_DEFAULT, null)?.let { json ->
            parse(json)?.let { return it }
        }
        return ParamMapping.DEFAULT
    }

    /**
     * Saves a new global default mapping.
     */
    fun saveDefault(context: Context, mapping: ParamMapping) {
        prefs(context).edit()
            .putString(KEY_DEFAULT, toJson(mapping))
            .apply()
    }

    /**
     * Saves or clears the per-model override for [radio]. Passing null for
     * [mapping] removes the override so the global default is used.
     */
    fun saveForModel(context: Context, radio: RadioInfo, mapping: ParamMapping?) {
        val key = modelKey(radio)
        val editor = prefs(context).edit()
        if (mapping == null) {
            editor.remove(key)
        } else {
            editor.putString(key, toJson(mapping))
        }
        editor.apply()
    }

    private fun modelKey(radio: RadioInfo): String =
        KEY_PREFIX_MODEL + (radio.vendor + "_" + radio.model)
            .replace(Regex("\\s+"), "_")

    private fun toJson(mapping: ParamMapping): String =
        JSONObject().apply {
            put("mode_map", JSONObject(mapping.modeMap))
            put("bandwidth_map", JSONObject(mapping.bandwidthMap))
        }.toString()

    private fun parse(json: String): ParamMapping? =
        runCatching {
            val obj = JSONObject(json)
            val modeMap = obj.optJSONObject("mode_map")?.let { mapFromJsonObject(it) } ?: emptyMap()
            val bwMap = obj.optJSONObject("bandwidth_map")?.let { mapFromJsonObject(it) } ?: emptyMap()
            ParamMapping(modeMap, bwMap)
        }.getOrNull()

    private fun mapFromJsonObject(obj: JSONObject): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val names = obj.names() ?: return emptyMap()
        for (i in 0 until names.length()) {
            val key = names.getString(i)
            val value = obj.optString(key, "")
            if (key.isNotEmpty() && value.isNotEmpty()) {
                result[key] = value
            }
        }
        return result
    }
}


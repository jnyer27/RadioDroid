package com.radiodroid.app.model

import org.json.JSONObject

/**
 * Schema for one channel-extra (radio-specific) parameter from the driver.
 * Parsed from chirp_bridge.get_channel_extra_schema(); used to render
 * Spinner / Switch / EditText in the channel editor instead of free-text.
 */
data class ChannelExtraSetting(
    val name: String,
    val type: String,
    val value: String,
    val options: List<String>? = null,
    val min: Int? = null,
    val max: Int? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val readOnly: Boolean = false,
) {
    companion object {
        fun fromJson(obj: JSONObject): ChannelExtraSetting {
            val options = obj.optJSONArray("options")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it, "") }
            }
            val value = when (obj.optString("type", "string")) {
                "int" -> obj.optInt("value", 0).toString()
                "float" -> obj.optDouble("value", 0.0).toString()
                "bool" -> if (obj.optBoolean("value", false)) "True" else "False"
                else -> obj.optString("value", "")
            }
            return ChannelExtraSetting(
                name = obj.optString("name", ""),
                type = obj.optString("type", "string"),
                value = value,
                options = options?.takeIf { it.isNotEmpty() },
                min = if (obj.has("min")) obj.optInt("min", 0) else null,
                max = if (obj.has("max")) obj.optInt("max", 0) else null,
                minLength = if (obj.has("minLength")) obj.optInt("minLength", 0) else null,
                maxLength = if (obj.has("maxLength")) obj.optInt("maxLength", 255) else null,
                readOnly = obj.optBoolean("readOnly", false),
            )
        }
    }
}

package com.gushypushy.diffusereborn

import android.content.SharedPreferences
import kotlin.math.roundToInt

/**
 * Reads preferences defensively across old Diffuse builds.
 *
 * SharedPreferences getters throw ClassCastException when a key was written with a
 * different type by an older build or imported preset. Convert common historical
 * representations and normalize them back to the current type instead of crashing.
 */
fun SharedPreferences.getIntCompat(key: String, defaultValue: Int): Int {
    val raw = all[key] ?: return defaultValue
    val value = when (raw) {
        is Int -> return raw
        is Long -> raw.toInt()
        is Float -> raw.roundToInt()
        is Double -> raw.roundToInt()
        is Number -> raw.toInt()
        is String -> raw.toFloatOrNull()?.roundToInt() ?: defaultValue
        is Boolean -> if (raw) 1 else 0
        else -> defaultValue
    }
    edit().putInt(key, value).apply()
    return value
}

fun SharedPreferences.getBooleanCompat(key: String, defaultValue: Boolean): Boolean {
    val raw = all[key] ?: return defaultValue
    val value = when (raw) {
        is Boolean -> return raw
        is Number -> raw.toDouble() != 0.0
        is String -> when (raw.trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> defaultValue
        }
        else -> defaultValue
    }
    edit().putBoolean(key, value).apply()
    return value
}

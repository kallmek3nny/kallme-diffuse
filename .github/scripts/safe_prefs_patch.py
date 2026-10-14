from pathlib import Path

root = Path('.')

compat = root / 'app/src/main/java/com/gushypushy/diffusereborn/PreferenceCompat.kt'
if compat.exists():
    raise SystemExit('PreferenceCompat.kt already exists')
compat.write_text('''package com.gushypushy.diffusereborn

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
''')

files = [
    root / 'app/src/main/java/DiffusePreviewView.kt',
    root / 'app/src/main/java/com/gushypushy/diffusereborn/MainActivity.kt',
    root / 'app/src/main/java/com/gushypushy/diffusereborn/ClassicActivity.kt',
    root / 'app/src/main/java/com/gushypushy/diffusereborn/DiffuseWallpaperService.kt',
]

for path in files:
    text = path.read_text()
    before = text
    text = text.replace('prefs.getInt(', 'prefs.getIntCompat(')
    text = text.replace('prefs.getBoolean(', 'prefs.getBooleanCompat(')
    if text == before:
        print(f'no strict numeric/boolean prefs in {path}')
    else:
        path.write_text(text)
        print(f'patched {path}')

# The preview is inflated during setContentView, so this assertion protects the exact
# crash-prone constructor path from ever regressing to strict typed reads.
preview = (root / 'app/src/main/java/DiffusePreviewView.kt').read_text()
if 'prefs.getInt(' in preview or 'prefs.getBoolean(' in preview:
    raise SystemExit('strict preference getter remains in DiffusePreviewView')

main = (root / 'app/src/main/java/com/gushypushy/diffusereborn/MainActivity.kt').read_text()
if 'prefs.getInt(' in main or 'prefs.getBoolean(' in main:
    raise SystemExit('strict preference getter remains in MainActivity')

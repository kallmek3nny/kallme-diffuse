from pathlib import Path

p = Path('app/src/main/java/com/gushypushy/diffusereborn/MainActivity.kt')
s = p.read_text()

old = 'import android.app.Activity\n'
new = 'import androidx.appcompat.app.AppCompatActivity\n'
if s.count(old) != 1:
    raise SystemExit(f'expected one Activity import, found {s.count(old)}')
s = s.replace(old, new, 1)

old = 'class MainActivity : Activity() {'
new = 'class MainActivity : AppCompatActivity() {'
if s.count(old) != 1:
    raise SystemExit(f'expected one MainActivity superclass, found {s.count(old)}')
s = s.replace(old, new, 1)

old = '''    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n        // Material You dynamic colors follow the user's system wallpaper/theme on Android 12+.\n        // Older versions simply keep the Material 3 fallback palette from the app theme.\n        DynamicColors.applyToActivityIfAvailable(this)\n'''
new = '''    override fun onCreate(savedInstanceState: Bundle?) {\n        // Apply the Material You overlay before AppCompat initializes the themed window/views.\n        DynamicColors.applyToActivityIfAvailable(this)\n        super.onCreate(savedInstanceState)\n'''
if s.count(old) != 1:
    raise SystemExit(f'expected one onCreate dynamic-color block, found {s.count(old)}')
s = s.replace(old, new, 1)

p.write_text(s)

# Remove the stale duplicate Material dependency. Gradle was resolving 1.12.0 anyway,
# but keeping one source of truth avoids version surprises.
g = Path('app/build.gradle.kts')
t = g.read_text()
t = t.replace('    implementation("com.google.android.material:material:1.9.0")\n// Or any recent version\n', '')
g.write_text(t)

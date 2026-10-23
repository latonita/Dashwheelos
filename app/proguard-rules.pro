# Dashwheel release keep rules.
#
# Most libraries ship their own consumer rules (Compose, MapLibre, OkHttp,
# coroutines). The entries below cover the ones that reach into native code or
# reflection and are not fully covered by consumer rules.

# Filament (Car3D.kt): JNI callbacks into Java by name.
-keep class com.google.android.filament.** { *; }
-dontwarn com.google.android.filament.**

# MapLibre GL + navigation: native bridge and Gson-mapped route models.
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# The map SDK is the 10.x line (OpenGL ES 2.0 — see app/build.gradle.kts), and
# that line still carries the pre-rename package. Its JNI bridge looks classes,
# fields and methods up by name from C++, so R8 renaming any of them breaks the
# map with a crash that points into native code and names nothing.
-keep class com.mapbox.** { *; }

# The navigation UI module is built against the map SDK's 11.x names, which are
# absent here. Nothing that refers to them is used — the route line is drawn by
# RouteLine.kt and only ManeuverView is taken from that module, which touches no
# map class — but R8 still sees the references while walking the module.
-dontwarn com.mapbox.**

# Keep the app's own JSON-facing enums by name: tile kinds and theme modes are
# persisted with Enum.name / valueOf, which R8 would otherwise rename.
-keepclassmembers enum com.openauto.dash.** { *; }

# Kotlin metadata / coroutines internals that R8 may warn about.
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.coroutines.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# The QF car app's Parcelables, received in its broadcasts and read back by
# class name (CarBox): keep the name, the CREATOR and the fields.
-keep class com.qf.vehicle.entity.** { *; }

# The tile displays' helper runs by name in a process of its own (app_process, SystemDisplays).
-keep class com.openauto.dash.SystemDisplayHelper { public static void main(java.lang.String[]); }

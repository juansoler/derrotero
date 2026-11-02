# MapLibre Native keeps its JNI entry points via reflection.
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.**
-dontwarn okhttp3.**
-dontwarn kotlinx.serialization.**

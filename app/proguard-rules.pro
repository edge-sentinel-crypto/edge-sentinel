# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Room entities and DAOs
-keep class com.bp22intel.edgesentinel.data.local.** { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }

# osmdroid (uses reflection/resources for the map view)
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Strip verbose/debug/info logging from release builds so operational detail
# (cell IDs, locations, detection internals) is not emitted on shipping builds.
# Error/warn logs are retained for crash triage.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

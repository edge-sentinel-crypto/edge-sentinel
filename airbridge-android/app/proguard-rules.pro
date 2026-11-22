# AirBridge release rules (the shipped build is the debug APK; these keep a future release build working).
# BouncyCastle lightweight crypto classes are referenced directly (no reflection), but keep them intact.
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.math.ec.rfc7748.** { *; }
-keep class org.bouncycastle.math.ec.rfc8032.** { *; }
-dontwarn org.bouncycastle.**

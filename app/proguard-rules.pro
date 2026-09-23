# Keep R8 shrinking and optimization, but preserve class and member names.
-dontobfuscate

-keep class libv2ray.** { *; }
-keep class go.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn libv2ray.**

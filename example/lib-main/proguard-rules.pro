# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
-keepparameternames
-dontoptimize
-dontshrink
-dontobfuscate
-dontpreverify

# glide is embedded with `transitive = false`, so its own transitive dependencies
# (disklrucache, gifdecoder) are intentionally not bundled. Suppress R8 full-mode
# missing-class errors for them (and for the merged R class referenced by fresco).
-dontwarn com.bumptech.glide.disklrucache.**
-dontwarn com.bumptech.glide.gifdecoder.**
-dontwarn com.kezong.demo.lib.R$styleable

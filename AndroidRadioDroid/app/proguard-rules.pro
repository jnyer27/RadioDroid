# Add project specific ProGuard rules here.
-keepattributes *Annotation*

# Chaquopy / Cython JNI (aligns with build/python/proguard-rules.pro generated at build time)
-keep class com.chaquo.python.** { *; }
-keep class com.chaquo.** { *; }

# See get_sam in Chaquopy class.pxi
-keep class kotlin.jvm.functions.** { *; }
-keep class kotlin.jvm.internal.FunctionBase { *; }
-keep class kotlin.reflect.KAnnotatedElement { *; }

# https://github.com/chaquo/chaquopy/issues/842
-dontwarn org.jetbrains.annotations.NotNull

# USB Serial for Android (reflection / drivers)
-keep class com.hoho.android.usbserial.** { *; }

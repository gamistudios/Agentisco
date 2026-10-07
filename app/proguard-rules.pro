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

# ---- the local model engine's native binding ----
#
# The linker binds libawaki-llm.so's symbols to Kotlin by NAME: Java_com_awaki_local_jni_
# NativeLlama_nativeComplete is a string in the library, and a renamed class or method means the
# engine reports itself unavailable at runtime rather than failing the build. Nothing at the call
# site tells R8 that, so it has to be said here.
-keepclasseswithmembernames class * {
    native <methods>;
}

# The same name binding runs the other way: the decode loop calls back into Kotlin through a
# method ID looked up as "onToken" ([B)Z, so the interface and its method keep their names too.
-keep interface com.awaki.local.jni.NativeLlama$TokenSink {
    boolean onToken(byte[]);
}

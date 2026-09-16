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

# ==================== kotlinx.serialization ====================
# 批次配置（BatchConfig）以 JSON 形式持久化在 DataStore 中，
# release 构建开启了 R8 混淆，必须保留序列化器与被 @Serializable 标注的类，
# 否则混淆后按类名查找序列化器会抛 SerializationException。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.qihao.filtercamera.domain.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.qihao.filtercamera.domain.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.qihao.filtercamera.domain.model.**$$serializer { *; }

-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

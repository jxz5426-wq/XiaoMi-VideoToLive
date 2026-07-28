# VideoToLive ProGuard Rules

# 保留所有 Activity 和 Service
-keep class com.videotolive.app.** { *; }

# 保留 MediaCodec 相关
-keep class android.media.** { *; }

# 保留 Native 方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep LiteRT-LM Kotlin/JNI entry points intact in release builds.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

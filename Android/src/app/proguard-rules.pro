# JNI-callable Kotlin/Java surfaces. R8 had been renaming TokenCallback.onToken
# in release builds, which made llama_jni's GetMethodID return null and aborted
# the process the moment the model emitted its first token. Keep the whole JNI
# bridge intact so name lookups from native code resolve.
-keep class com.google.ai.edge.gallery.llm.LlamaCpp { *; }
-keep class com.google.ai.edge.gallery.llm.LlamaCpp$* { *; }
-keep interface com.google.ai.edge.gallery.llm.TokenCallback { *; }
-keep class * implements com.google.ai.edge.gallery.llm.TokenCallback { *; }

# General JNI rule — methods invoked from C++ via env->GetMethodID must keep
# their name and signature.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ONNX Runtime — same failure mode as the LlamaCpp.TokenCallback bug, on a
# different native library. libonnxruntime4j_jni does FindClass+GetMethodID
# lookups by name into ai.onnxruntime.*; R8 was stripping/renaming those
# classes in release, the first GetMethodID returned java_class==null, and
# the process aborted (`JNI DETECTED ERROR IN APPLICATION: java_class == null`)
# the moment the user tapped Voice Note → Stop and we tried OrtSession.run.
# Keep the whole ONNX surface intact.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

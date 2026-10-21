# App-owned R8 rules. Dependencies are expected to ship consumer rules; add any
# further keep/dontwarn rule only in response to a reproduced release failure.

# Preserve runtime-visible annotations used by generated serializers and DI.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod

# MediaPipe LLM: its native layer calls back into this package by name.
# Reproduced release failure (2026-10-21, v292 on SM-S928B / Android 16):
# "JNI DETECTED ERROR IN APPLICATION: mid == null" in
# libllm_inference_engine_jni.so — the native code looks up
# LlmTaskRunnerDelegate.onAsyncResponse([B)V, which R8 had renamed; only
# LlmInference and LlmInferenceSession were kept before. The package has
# about 70 classes, so keep all of it rather than chase single callbacks.
# Not all MediaPipe/protobuf/Guava classes: only this API package.
-keep class com.google.mediapipe.tasks.genai.llminference.** { *; }

# ONNX Runtime (wake word engine): its JNI code finds the result and error
# classes by name (FindClass/GetMethodID) on every OrtSession.run.
# Reproduced release failure (2026-10-21, v300 on SM-S928B / Android 16):
# "JNI DETECTED ERROR IN APPLICATION: java_class == null from boolean[]
# ai.onnxruntime.OrtSession.run(...)" — the app died the moment OMNIX was
# turned on. The AAR ships no consumer rules for this.
-keep class ai.onnxruntime.** { *; }

# Wake phrase «Окей, Омни»: libsherpa-onnx-jni.so reads config fields
# (KeywordSpotterConfig, OnlineModelConfig, FeatureConfig, ...) and builds
# KeywordSpotterResult by name. The AAR ships no consumer rules.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Reproduced release failure (2026-09-21, v93 on a real device):
# "RuntimeException: Field modelPath_ for c2.g not found" — R8 renamed the
# fields of LlmOptionsProto$LlmModelSettings. The protobuf runtime reaches
# these GeneratedMessageLite fields BY NAME through reflection, so the whole
# jni.proto package must keep its members: LlmOptionsProto (engine options,
# modelPath_/maxTokens_/backend), LlmSessionConfig (per-request session:
# temperature/topK/topP) and LlmResponseContext (parsing native responses).
# Debug builds are unminified, which is why CI never caught it.
-keep class com.google.mediapipe.tasks.genai.llminference.jni.proto.** { *; }
# scripts/verify-r8-native-keep.sh checks the R8 mapping on every PR, so a
# rename in either package fails CI instead of the phone.

# Generic JNI entry points may be invoked by symbol/name from native code.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# tasks-genai references compile-only AutoValue annotations and its optional
# multimodal MPImage API. This app uses text-only inference and never calls
# addImage/createImage, so these exact absent types are safe to ignore. Keep the
# suppression narrow: a new missing class must still fail the release build.
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.mediapipe.framework.image.BitmapExtractor
-dontwarn com.google.mediapipe.framework.image.ByteBufferExtractor
-dontwarn com.google.mediapipe.framework.image.MPImage
-dontwarn com.google.mediapipe.framework.image.MPImageProperties
-dontwarn com.google.mediapipe.framework.image.MediaImageExtractor

# No blanket DTO/entity/domain keeps and no broad warning suppression:
# kotlinx.serialization, Room, OkHttp and AndroidX publish generated references or
# consumer rules. A release build must expose any genuinely missing rule.

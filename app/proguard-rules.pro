# Keep bitmap / engine classes intact; they are accessed reflectively only by JSON.
-keep class com.yume.app.engine.** { *; }

# ONNX Runtime (AI tools): its native code finds these classes, fields and
# methods by name, so none may be renamed or removed.
-keep class ai.onnxruntime.** { *; }

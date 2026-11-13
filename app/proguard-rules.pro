# Keep bitmap / engine classes intact; they are accessed reflectively only by JSON.
-keep class com.yume.app.engine.** { *; }

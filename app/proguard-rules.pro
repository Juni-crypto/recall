# JNI: native code looks these up by name.
-keep class app.recall.model.LlamaNative { *; }
-keep interface app.recall.model.LlamaNative$TokenCallback { *; }
-keepclassmembers class * implements app.recall.model.LlamaNative$TokenCallback { void onToken(byte[]); }

# WorkManager keeps its job queue in a Room database it creates by reflection.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.** { *; }
# Glance widget receiver and workers are created by the system by name.
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

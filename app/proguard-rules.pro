# The app's own classes (the link's frames and models are read by position, and workers are made by name).
-keep class io.github.arnavdugad.arnavisland.** { *; }

# WorkManager (the twice-a-day update check) opens its Room database by reflection on the generated class's
# no-argument constructor, which R8 would otherwise remove: the optimised build then crashes at start.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

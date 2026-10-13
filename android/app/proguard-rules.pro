# Proguard / R8 rules for LED Matrix 360 Production
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep data models used for JSON parsing & BLE communication
-keep class com.esp32.ledmatrix360.model.** { *; }

# Keep WorkManager worker classes initialized via reflection by WorkManager
-keep public class com.esp32.ledmatrix360.worker.** extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Keep Foreground Service, Notification Listener & Receivers
-keep class com.esp32.ledmatrix360.service.** { *; }
-keep class com.esp32.ledmatrix360.receiver.** { *; }
-keep class com.esp32.ledmatrix360.widget.** { *; }

# Kotlin Coroutines and DataStore
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

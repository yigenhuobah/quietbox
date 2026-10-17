# The framework instantiates the entry class reflectively and invokes the
# lifecycle callbacks by name, so neither may be renamed or removed.
-keep public class com.quietbox.app.QuietBoxModule { public <init>(); }

-keepclassmembers class * extends io.github.libxposed.api.XposedModule {
    public void onModuleLoaded(...);
    public void onPackageLoaded(...);
    public void onPackageReady(...);
    public void onSystemServerStarting(...);
    public boolean onHotReloading(...);
    public void onHotReloaded(...);
}

# compileOnly artifacts are absent at runtime; R8 must not complain about them.
-dontwarn io.github.libxposed.api.**
-dontwarn io.github.libxposed.annotation.**

# 自定义 Preference 由 res/xml/prefs.xml 按类名反射实例化，R8 看不到这层引用。
-keep class com.quietbox.app.MaterialSwitchPreference {
    public <init>(android.content.Context, android.util.AttributeSet);
}

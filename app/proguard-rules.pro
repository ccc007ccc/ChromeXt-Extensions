# The framework loads this class by name from META-INF/xposed/java_init.list.
# Keep the name and public no-argument constructor in the minified APK too.
-keep,allowoptimization class org.matrix.chromext.MainHook extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-dontwarn io.github.libxposed.annotation.**
-keepattributes SourceFile,LineNumberTable

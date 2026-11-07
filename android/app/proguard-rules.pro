# Spec 7.4: no logging in release. R8 removes these calls (and their argument
# construction when side-effect free) because they are declared side-effect free.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}

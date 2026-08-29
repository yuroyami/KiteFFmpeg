-keep class io.github.yuroyami.kiteffmpeg.Internals {
    native <methods>;
}

# kj_util.c resolves these binary names with FindClass and explicitly invokes the exact
# (Ljava/lang/String;)V constructor. Neither lookup is visible to R8's ordinary reachability
# analysis, so both the class names and constructors are part of the JNI ABI.
-keep class io.github.yuroyami.kiteffmpeg.JniHandleException {
    <init>(java.lang.String);
}

-keep class io.github.yuroyami.kiteffmpeg.JniNativeException {
    <init>(java.lang.String);
}

# kj_format.c resolves these two by GetMethodID on the object's class at custom-io open time
# (M1, the AVIO bridge). Renamed or stripped members break the bridge at open, so both method
# shapes are part of the JNI ABI exactly like the exception constructors above.
-keep class io.github.yuroyami.kiteffmpeg.JniByteIo {
    int read(byte[], int);
    long seek(long, int);
}

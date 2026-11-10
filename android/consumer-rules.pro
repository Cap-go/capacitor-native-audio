# Consumer rules for apps that minify with R8 (default in Capacitor 9 release builds).
# Applied via consumerProguardFiles in the library module.

# NativeAudio is loaded by Capacitor using the class name from @CapacitorPlugin and methods are discovered reflectively.
# Keep Capacitor annotation types PluginHandle reads through getAnnotation.
-keep @interface com.getcapacitor.annotation.** { *; }
-keep @interface com.getcapacitor.PluginMethod { *; }

-keep @com.getcapacitor.annotation.CapacitorPlugin class ee.forgr.audio.NativeAudio {
    *;
}

# HLS path loads this class only when optional media3-exoplayer-hls is present; NativeAudio uses Class.forName and getConstructor.
-keep class ee.forgr.audio.StreamAudioAsset {
    public <init>(ee.forgr.audio.NativeAudio, java.lang.String, android.net.Uri, float, java.util.Map);
}

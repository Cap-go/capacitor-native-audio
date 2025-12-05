package ee.forgr.audio;

import android.util.Log;

/**
 * Utility class to check if HLS (m3u8) streaming dependencies are available at runtime.
 *
 * This allows the plugin to gracefully handle cases where the HLS dependency
 * (media3-exoplayer-hls) is excluded to reduce APK size.
 *
 * Users who don't need HLS streaming support can disable it in capacitor.config.ts:
 *
 * plugins: {
 *   NativeAudio: {
 *     hls: false  // Reduces APK size by ~4MB
 *   }
 * }
 */
public class HlsAvailabilityChecker {

    private static final String TAG = "HlsAvailabilityChecker";
    private static Boolean hlsAvailable = null;

    /**
     * Check if a class is available at runtime using reflection.
     */
    private static boolean isClassAvailable(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error checking class availability: " + className, e);
            return false;
        }
    }

    /**
     * Check if HLS streaming dependencies are available.
     * Results are cached for performance.
     *
     * @return true if HLS classes are available, false otherwise
     */
    public static boolean isHlsAvailable() {
        if (hlsAvailable != null) {
            return hlsAvailable;
        }

        // Check for the critical HLS classes from media3-exoplayer-hls
        String[] hlsClasses = { "androidx.media3.exoplayer.hls.HlsMediaSource", "androidx.media3.exoplayer.hls.HlsMediaSource$Factory" };

        boolean allAvailable = true;
        for (String className : hlsClasses) {
            if (!isClassAvailable(className)) {
                allAvailable = false;
                Log.w(TAG, "HLS dependency class not available: " + className);
            }
        }

        hlsAvailable = allAvailable;

        if (!allAvailable) {
            Log.i(
                TAG,
                "HLS streaming support is not available. " +
                    "To enable m3u8 streaming, set 'hls: true' in capacitor.config.ts under NativeAudio plugin config " +
                    "and run 'npx cap sync'."
            );
        } else {
            Log.d(TAG, "HLS streaming support is available.");
        }

        return allAvailable;
    }

    /**
     * Reset the cached availability check.
     * Useful for testing purposes.
     */
    public static void resetCache() {
        hlsAvailable = null;
    }
}

package ee.forgr.audio;

import android.util.Log;
import androidx.media3.common.util.UnstableApi;

@UnstableApi
public class Logger {

    private String logTag;

    // constructor
    public Logger(String logTag) {
        this.logTag = logTag;
    }

    public void setLogTag(String logTag) {
        this.logTag = logTag;
    }

    public void error(String message) {
        if (NativeAudio.debugEnabled) {
            Log.e(logTag, message);
        }
    }

    public void error(String message, Throwable throwable) {
        if (NativeAudio.debugEnabled) {
            Log.e(logTag, message, throwable);
        }
    }

    public void warning(String message) {
        if (NativeAudio.debugEnabled) {
            Log.w(logTag, message);
        }
    }

    public void info(String message) {
        if (NativeAudio.debugEnabled) {
            Log.i(logTag, message);
        }
    }

    public void debug(String message) {
        if (NativeAudio.debugEnabled) {
            Log.d(logTag, message);
        }
    }

    public void verbose(String message) {
        if (NativeAudio.debugEnabled) {
            Log.v(logTag, message);
        }
    }
}

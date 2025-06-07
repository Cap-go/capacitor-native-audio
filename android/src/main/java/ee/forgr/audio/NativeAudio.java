package ee.forgr.audio;

import static ee.forgr.audio.Constant.ASSET_ID;
import static ee.forgr.audio.Constant.ASSET_PATH;
import static ee.forgr.audio.Constant.AUDIO_CHANNEL_NUM;
import static ee.forgr.audio.Constant.DELAY;
import static ee.forgr.audio.Constant.DURATION;
import static ee.forgr.audio.Constant.ERROR_ASSET_NOT_LOADED;
import static ee.forgr.audio.Constant.ERROR_ASSET_PATH_MISSING;
import static ee.forgr.audio.Constant.ERROR_AUDIO_ASSET_MISSING;
import static ee.forgr.audio.Constant.ERROR_AUDIO_EXISTS;
import static ee.forgr.audio.Constant.ERROR_AUDIO_ID_MISSING;
import static ee.forgr.audio.Constant.FADE_IN;
import static ee.forgr.audio.Constant.FADE_IN_DURATION;
import static ee.forgr.audio.Constant.FADE_OUT;
import static ee.forgr.audio.Constant.FADE_OUT_DURATION;
import static ee.forgr.audio.Constant.FADE_OUT_START_TIME;
import static ee.forgr.audio.Constant.LOOP;
import static ee.forgr.audio.Constant.OPT_FOCUS_AUDIO;
import static ee.forgr.audio.Constant.PLAY;
import static ee.forgr.audio.Constant.RATE;
import static ee.forgr.audio.Constant.TIME;
import static ee.forgr.audio.Constant.VOLUME;

import android.Manifest;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.media3.common.util.UnstableApi;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@UnstableApi
@CapacitorPlugin(
    permissions = {
        @Permission(strings = { Manifest.permission.MODIFY_AUDIO_SETTINGS }),
        @Permission(strings = { Manifest.permission.WRITE_EXTERNAL_STORAGE }),
        @Permission(strings = { Manifest.permission.READ_PHONE_STATE })
    }
)
public class NativeAudio extends Plugin implements AudioManager.OnAudioFocusChangeListener {

    public static final String TAG = "NativeAudio";

    private static HashMap<String, AudioAsset> audioAssetList = new HashMap<>();
    private static ArrayList<AudioAsset> resumeList = new ArrayList<>(); // Always initialized
    private AudioManager audioManager;
    private final Map<String, PluginCall> pendingDurationCalls = new HashMap<>();

    private Handler fadeOutHandler = new Handler(Looper.getMainLooper());
    private Runnable fadeOutRunnable;

    private final Map<String, Handler> pendingPlayHandlers = new HashMap<>();
    private final Map<String, Runnable> pendingPlayRunnables = new HashMap<>();

    @Override
    public void load() {
        super.load();

        this.audioManager = (AudioManager) this.getActivity().getSystemService(Context.AUDIO_SERVICE);

        audioAssetList = new HashMap<>();
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        try {
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                // Pause playback - temporary loss
                Log.d(TAG, "Audio focus lost transiently - pausing playback");
                for (AudioAsset audio : audioAssetList.values()) {
                    if (audio.isPlaying()) {
                        audio.pause();
                        // Ensure resumeList is not null
                        if (resumeList == null) resumeList = new ArrayList<>();
                        resumeList.add(audio);
                    }
                }
            } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                // Resume playback
                Log.d(TAG, "Audio focus gained - resuming playback");
                if (resumeList != null) {
                    while (!resumeList.isEmpty()) {
                        AudioAsset audio = resumeList.remove(0);
                        audio.resume();
                    }
                }
            } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                // Stop playback - permanent loss
                Log.d(TAG, "Audio focus lost permanently - stopping playback");
                for (AudioAsset audio : audioAssetList.values()) {
                    audio.stop();
                }
                audioManager.abandonAudioFocus(this);
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error handling audio focus change", ex);
        }
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();

        try {
            if (audioAssetList != null) {
                Log.d(TAG, "Application paused - pausing all audio assets");
                for (HashMap.Entry<String, AudioAsset> entry : audioAssetList.entrySet()) {
                    AudioAsset audio = entry.getValue();

                    if (audio != null) {
                        boolean wasPlaying = audio.pause();

                        if (wasPlaying) {
                            if (resumeList == null) resumeList = new ArrayList<>();
                            resumeList.add(audio);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "Exception caught while listening for handleOnPause: " + ex.getLocalizedMessage());
        }
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();

        try {
            if (resumeList != null) {
                while (!resumeList.isEmpty()) {
                    Log.d(TAG, "Application resumed - resuming audio assets");
                    AudioAsset audio = resumeList.remove(0);

                    if (audio != null) {
                        audio.resume();
                    }
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "Exception caught while listening for handleOnResume: " + ex.getLocalizedMessage());
        }
    }

    @PluginMethod
    public void configure(PluginCall call) {
        try {
            initSoundPool();

            if (this.audioManager == null) {
                call.resolve();
                return;
            }

            boolean focus = call.getBoolean(OPT_FOCUS_AUDIO, false);
            boolean background = call.getBoolean("background", false);

            Log.d(TAG, "Configuring audio focus: " + focus + ", background: " + background);

            if (focus) {
                // Request audio focus for playback with ducking
                int result =
                    this.audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK); // Allow other audio to play quietly
            } else {
                this.audioManager.abandonAudioFocus(this);
            }

            if (background) {
                // Set playback to continue in background
                this.audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            } else {
                this.audioManager.setMode(AudioManager.MODE_NORMAL);
            }
            call.resolve();
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void isPreloaded(final PluginCall call) {
        new Thread(
            new Runnable() {
                @Override
                public void run() {
                    try {
                        initSoundPool();

                        String audioId = call.getString(ASSET_ID);

                        if (!isStringValid(audioId)) {
                            call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                            return;
                        }
                        call.resolve(new JSObject().put("found", audioAssetList.containsKey(audioId)));
                    } catch (Exception ex) {
                        call.reject(ex.getMessage());
                    }
                }
            }
        ).start();
    }

    @PluginMethod
    public void preload(final PluginCall call) {
        this.getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        preloadAsset(call);
                    }
                }
            );
    }

    @PluginMethod
    public void play(final PluginCall call) {
        try {
            double delay = call.getDouble(DELAY, 0.0);
            String assetId = call.getString(ASSET_ID);

            // Cancel any pending play before scheduling a new one
            cancelPendingPlay(assetId);

            this.getActivity()
                .runOnUiThread(() -> {
                    long delayMillis = (long) (delay * 1000);
                    Handler handler = new Handler(Looper.getMainLooper());
                    Runnable runnable = new Runnable() {
                        @Override
                        public void run() {
                            playOrLoop(PLAY, call);
                            cancelPendingPlay(assetId);
                        }
                    };
                    pendingPlayHandlers.put(assetId, handler);
                    pendingPlayRunnables.put(assetId, runnable);
                    handler.postDelayed(runnable, delayMillis);
                });
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    private void cancelPendingPlay(String assetId) {
        Handler handler = pendingPlayHandlers.remove(assetId);
        Runnable runnable = pendingPlayRunnables.remove(assetId);
        if (handler != null && runnable != null) {
            handler.removeCallbacks(runnable);
        }
    }

    @PluginMethod
    public void getCurrentTime(final PluginCall call) {
        try {
            initSoundPool();

            String audioId = call.getString(ASSET_ID);

            if (!isStringValid(audioId)) {
                call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                return;
            }

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    call.resolve(new JSObject().put("currentTime", asset.getCurrentPosition()));
                }
            } else {
                call.reject(ERROR_AUDIO_ASSET_MISSING + " - " + audioId);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void getDuration(PluginCall call) {
        try {
            String audioId = call.getString(ASSET_ID);
            if (!isStringValid(audioId)) {
                call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                return;
            }

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    double duration = asset.getDuration();
                    if (duration > 0) {
                        JSObject ret = new JSObject();
                        ret.put("duration", duration);
                        call.resolve(ret);
                    } else {
                        // Save the call to resolve it later when duration is available
                        saveDurationCall(audioId, call);
                    }
                } else {
                    call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
                }
            } else {
                call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void loop(final PluginCall call) {
        try {
            String audioId = call.getString(ASSET_ID);
            cancelPendingPlay(audioId);
            this.getActivity()
                .runOnUiThread(
                    new Runnable() {
                        @Override
                        public void run() {
                            playOrLoop("loop", call);
                        }
                    }
                );
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void pause(PluginCall call) {
        try {
            initSoundPool();
            String audioId = call.getString(ASSET_ID);

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    boolean wasPlaying = asset.pause();

                    if (wasPlaying) {
                        resumeList.add(asset);
                    }
                    call.resolve();
                } else {
                    call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
                }
            } else {
                call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void resume(PluginCall call) {
        try {
            initSoundPool();
            String audioId = call.getString(ASSET_ID);

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    asset.resume();
                    resumeList.add(asset);
                    call.resolve();
                } else {
                    call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
                }
            } else {
                call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void stop(final PluginCall call) {
        try {
            String audioId = call.getString(ASSET_ID);
            cancelPendingPlay(audioId);

            boolean fadeOut = call.getBoolean(FADE_OUT, false);
            double fadeOutDurationSecs = call.getDouble(FADE_OUT_DURATION, AudioAsset.DEFAULT_FADE_DURATION_MS / 1000);
            double fadeOutDurationMs = fadeOutDurationSecs * 1000;
            this.getActivity()
                .runOnUiThread(
                    new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (!isStringValid(audioId)) {
                                    call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                                    return;
                                }
                                stopAudio(audioId, fadeOut, fadeOutDurationMs);
                                call.resolve();
                            } catch (Exception ex) {
                                call.reject(ex.getMessage());
                            }
                        }
                    }
                );
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void unload(PluginCall call) {
        try {
            initSoundPool();
            new JSObject();
            JSObject status;

            if (isStringValid(call.getString(ASSET_ID))) {
                String audioId = call.getString(ASSET_ID);
                cancelPendingPlay(audioId);
                if (audioAssetList.containsKey(audioId)) {
                    AudioAsset asset = audioAssetList.get(audioId);
                    if (asset != null) {
                        cancelFadeOut();
                        asset.unload();
                        audioAssetList.remove(audioId);
                        call.resolve();
                    } else {
                        call.reject(ERROR_AUDIO_ASSET_MISSING + " - " + audioId);
                    }
                } else {
                    call.reject(ERROR_AUDIO_ASSET_MISSING + " - " + audioId);
                }
            } else {
                call.reject(ERROR_AUDIO_ID_MISSING);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void setVolume(PluginCall call) {
        try {
            initSoundPool();

            String audioId = call.getString(ASSET_ID);
            float volume = call.getFloat(VOLUME, 1F);
            double durationSecs = call.getDouble(DURATION, 0.0);

            if (durationSecs > 0) {
                Log.d(TAG, "setVolume " + volume + " over duration " + durationSecs + " seconds");
            } else {
                Log.d(TAG, "setVolume " + volume);
            }

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    double durationMs = durationSecs * 1000;
                    asset.setVolume(volume, durationMs);
                    call.resolve();
                } else {
                    call.reject(ERROR_AUDIO_ASSET_MISSING);
                }
            } else {
                call.reject(ERROR_AUDIO_ASSET_MISSING);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void setRate(PluginCall call) {
        try {
            initSoundPool();

            String audioId = call.getString(ASSET_ID);
            float rate = call.getFloat(RATE, 1F);

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    asset.setRate(rate);
                }
                call.resolve();
            } else {
                call.reject(ERROR_AUDIO_ASSET_MISSING);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void isPlaying(final PluginCall call) {
        try {
            initSoundPool();

            String audioId = call.getString(ASSET_ID);

            if (!isStringValid(audioId)) {
                call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                return;
            }

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    call.resolve(new JSObject().put("isPlaying", asset.isPlaying()));
                } else {
                    call.reject(ERROR_AUDIO_ASSET_MISSING + " - " + audioId);
                }
            } else {
                call.reject(ERROR_AUDIO_ASSET_MISSING + " - " + audioId);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void clearCache(PluginCall call) {
        try {
            RemoteAudioAsset.clearCache(getContext());
            call.resolve();
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void setCurrentTime(final PluginCall call) {
        try {
            initSoundPool();
            cancelFadeOut();

            String audioId = call.getString(ASSET_ID);
            double time = call.getDouble("time", 0.0);

            cancelPendingPlay(audioId);

            if (!isStringValid(audioId)) {
                call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                return;
            }

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    this.getActivity()
                        .runOnUiThread(
                            new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        asset.setCurrentTime(time);
                                        call.resolve();
                                    } catch (Exception e) {
                                        call.reject("Error setting current time: " + e.getMessage());
                                    }
                                }
                            }
                        );
                } else {
                    call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
                }
            } else {
                call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    public void dispatchComplete(String assetId) {
        JSObject ret = new JSObject();
        ret.put("assetId", assetId);
        notifyListeners("complete", ret);
    }

    public void notifyCurrentTime(String assetId, double currentTime) {
        // Round to nearest 100ms
        double roundedTime = Math.round(currentTime * 10.0) / 10.0;
        JSObject ret = new JSObject();
        ret.put("currentTime", roundedTime);
        ret.put("assetId", assetId);
        if (hasListeners("currentTime")) {
            notifyListeners("currentTime", ret);
        }
    }

    private void preloadAsset(PluginCall call) {
        float volume = 1F;
        int audioChannelNum = 1;
        JSObject status = new JSObject();
        status.put("STATUS", "OK");

        try {
            initSoundPool();

            String audioId = call.getString(ASSET_ID);
            if (!isStringValid(audioId)) {
                call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                return;
            }

            String assetPath = call.getString(ASSET_PATH);
            if (!isStringValid(assetPath)) {
                call.reject(ERROR_ASSET_PATH_MISSING + " - " + audioId + " - " + assetPath);
                return;
            }

            boolean isLocalUrl = call.getBoolean("isUrl", false);
            boolean isComplex = call.getBoolean("isComplex", false);

            Log.d(
                TAG,
                "Preloading asset: " + audioId + ", path: " + assetPath + ", isLocalUrl: " + isLocalUrl + ", isComplex: " + isComplex
            );

            if (audioAssetList.containsKey(audioId)) {
                call.reject(ERROR_AUDIO_EXISTS + " - " + audioId);
                return;
            }

            if (isComplex) {
                volume = call.getFloat(VOLUME, 1F);
                audioChannelNum = call.getInt(AUDIO_CHANNEL_NUM, 1);
            }

            if (isLocalUrl) {
                try {
                    Uri uri = Uri.parse(assetPath);
                    if (uri.getScheme() != null && (uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
                        // Remote URL
                        Log.d(TAG, "Remote URL detected");
                        if (assetPath.endsWith(".m3u8")) {
                            // HLS Stream - resolve immediately since it's a stream
                            StreamAudioAsset streamAudioAsset = new StreamAudioAsset(this, audioId, uri, volume);
                            audioAssetList.put(audioId, streamAudioAsset);
                            call.resolve(status);
                        } else {
                            // Regular remote audio
                            RemoteAudioAsset remoteAudioAsset = new RemoteAudioAsset(this, audioId, uri, audioChannelNum, volume);
                            remoteAudioAsset.setCompletionListener(this::dispatchComplete);
                            audioAssetList.put(audioId, remoteAudioAsset);
                            call.resolve(status);
                        }
                    } else if (uri.getScheme() != null && uri.getScheme().equals("file")) {
                        // Local file URL
                        Log.d(TAG, "Local file URL detected");
                        File file = new File(uri.getPath());
                        if (!file.exists()) {
                            Log.e(TAG, "File does not exist - " + file.getAbsolutePath());
                            call.reject(ERROR_ASSET_PATH_MISSING + " - " + assetPath);
                            return;
                        }
                        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                        AssetFileDescriptor afd = new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
                        AudioAsset asset = new AudioAsset(this, audioId, afd, audioChannelNum, volume);
                        asset.setCompletionListener(this::dispatchComplete);
                        audioAssetList.put(audioId, asset);
                        call.resolve(status);
                    } else {
                        throw new IllegalArgumentException("Invalid URL scheme: " + uri.getScheme());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error handling URL", e);
                    call.reject("Error handling URL: " + e.getMessage());
                }
            } else {
                // Handle asset in public folder
                Log.d(TAG, "Handling asset in public folder");
                if (!assetPath.startsWith("public/")) {
                    assetPath = "public/" + assetPath;
                }
                try {
                    Context ctx = getContext().getApplicationContext();
                    AssetManager am = ctx.getResources().getAssets();
                    AssetFileDescriptor assetFileDescriptor = am.openFd(assetPath);
                    AudioAsset asset = new AudioAsset(this, audioId, assetFileDescriptor, audioChannelNum, volume);
                    audioAssetList.put(audioId, asset);
                    call.resolve(status);
                } catch (IOException e) {
                    Log.e(TAG, "Error opening asset: " + assetPath, e);
                    call.reject(ERROR_ASSET_PATH_MISSING + " - " + assetPath);
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error in preloadAsset", ex);
            call.reject("Error in preloadAsset: " + ex.getMessage());
        }
    }

    private void playOrLoop(String action, final PluginCall call) {
        try {
            final String audioId = call.getString(ASSET_ID);
            final double time = call.getDouble("time", 0.0);
            final float volume = call.getFloat("volume", 1f);
            boolean fadeIn = Boolean.TRUE.equals(call.getBoolean(FADE_IN, false));
            final double fadeInDurationSecs = call.getDouble(FADE_IN_DURATION, AudioAsset.DEFAULT_FADE_DURATION_MS / 1000);
            final double fadeInDurationMs = fadeInDurationSecs * 1000;

            boolean fadeOut = Boolean.TRUE.equals(call.getBoolean(FADE_OUT, false));
            final double fadeOutDurationSecs = call.getDouble(FADE_OUT_DURATION, AudioAsset.DEFAULT_FADE_DURATION_MS / 1000);
            final double fadeOutDurationMs = fadeOutDurationSecs * 1000;
            final double fadeOutStartTimeSecs = call.getDouble(FADE_OUT_START_TIME, 0.0);
            final double fadeOutStartTimeMs = fadeOutStartTimeSecs * 1000;
            Log.d(TAG, "Playing asset: " + audioId + ", action: " + action + ", time: " + time + ", volume: " + volume);

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                Log.d(TAG, "Found asset: " + audioId + ", type: " + asset.getClass().getSimpleName());

                if (asset != null) {
                    if (LOOP.equals(action)) {
                        asset.loop();
                    } else {
                        if (fadeOut) {
                            handleFadeOut(asset, fadeOutDurationMs, fadeOutStartTimeMs);
                        }
                        if (fadeIn) {
                            asset.playWithFadeIn(time, volume, fadeInDurationMs);
                        } else {
                            asset.play(time, volume);
                        }
                    }
                    call.resolve();
                } else {
                    call.reject("Asset is null: " + audioId);
                }
            } else {
                call.reject("Asset not found: " + audioId);
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error in playOrLoop", ex);
            call.reject(ex.getMessage());
        }
    }

    private void handleFadeOut(AudioAsset asset, double fadeOutDurationMs, double fadeOutStartTimeMs) {
        try {
            cancelFadeOut();
            double duration = asset.getDuration();
            if (duration > 0) {
                double fadeOutStartTime = duration - (fadeOutDurationMs / 1000.0);
                if (fadeOutStartTimeMs > 0) {
                    fadeOutStartTime = fadeOutStartTimeMs / 1000.0;
                }

                Log.d(TAG, "Scheduling fade-out for asset: " + asset.assetId + ", start time: " + fadeOutStartTime + " seconds");

                // Cancel any existing fade-out task
                cancelFadeOut();

                fadeOutRunnable = () -> {
                    try {
                        asset.stopWithFade(fadeOutDurationMs);
                    } catch (Exception e) {
                        Log.e(TAG, "Error during fade-out", e);
                    }
                };

                // Schedule fade-out to start at the calculated time
                fadeOutHandler.postDelayed(fadeOutRunnable, (long) (fadeOutStartTime * 1000));
            } else {
                Log.w(TAG, "Duration not available, skipping fade-out scheduling");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling fade-out", e);
        }
    }

    private void cancelFadeOut() {
        if (fadeOutRunnable != null) {
            Log.d(TAG, "Cancelling fade-out");
            fadeOutHandler.removeCallbacks(fadeOutRunnable);
            fadeOutRunnable = null;
        }
    }

    private void initSoundPool() {
        if (audioAssetList == null) {
            Log.d(TAG, "Initializing audio asset list");
            audioAssetList = new HashMap<>();
        }

        if (resumeList == null) {
            Log.d(TAG, "Initializing resume list");
            resumeList = new ArrayList<>();
        }
    }

    private boolean isStringValid(String value) {
        return (value != null && !value.isEmpty() && !value.equals("null"));
    }

    private void stopAudio(String audioId, boolean fadeOut, double fadeOutDurationMs) throws Exception {
        if (!audioAssetList.containsKey(audioId)) {
            throw new Exception(ERROR_ASSET_NOT_LOADED);
        }

        Log.d(TAG, "Stopping audio asset: " + audioId);
        AudioAsset asset = audioAssetList.get(audioId);
        if (asset != null) {
            cancelFadeOut();
            if (fadeOut) {
                asset.stopWithFade(fadeOutDurationMs);
            } else {
                asset.stop();
            }
        }
    }

    private void saveDurationCall(String audioId, PluginCall call) {
        Log.d(TAG, "Saving duration call for later: " + audioId);
        pendingDurationCalls.put(audioId, call);
    }

    public void notifyDurationAvailable(String assetId, double duration) {
        Log.d(TAG, "Duration available for " + assetId + ": " + duration);
        PluginCall savedCall = pendingDurationCalls.remove(assetId);
        if (savedCall != null) {
            JSObject ret = new JSObject();
            ret.put("duration", duration);
            savedCall.resolve(ret);
        }
    }
}

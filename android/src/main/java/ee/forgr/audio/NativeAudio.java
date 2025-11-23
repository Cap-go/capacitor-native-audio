package ee.forgr.audio;

import static ee.forgr.audio.Constant.ASSET_ID;
import static ee.forgr.audio.Constant.ASSET_PATH;
import static ee.forgr.audio.Constant.AUDIO_CHANNEL_NUM;
import static ee.forgr.audio.Constant.ERROR_ASSET_NOT_LOADED;
import static ee.forgr.audio.Constant.ERROR_ASSET_PATH_MISSING;
import static ee.forgr.audio.Constant.ERROR_AUDIO_ASSET_MISSING;
import static ee.forgr.audio.Constant.ERROR_AUDIO_EXISTS;
import static ee.forgr.audio.Constant.ERROR_AUDIO_ID_MISSING;
import static ee.forgr.audio.Constant.LOOP;
import static ee.forgr.audio.Constant.NOTIFICATION_METADATA;
import static ee.forgr.audio.Constant.OPT_FOCUS_AUDIO;
import static ee.forgr.audio.Constant.RATE;
import static ee.forgr.audio.Constant.SHOW_NOTIFICATION;
import static ee.forgr.audio.Constant.VOLUME;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.media3.common.util.UnstableApi;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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

    private final String pluginVersion = "";

    public static final String TAG = "NativeAudio";

    private static HashMap<String, AudioAsset> audioAssetList = new HashMap<>();
    private static ArrayList<AudioAsset> resumeList;
    private AudioManager audioManager;
    private boolean fadeMusic = false;
    private boolean audioFocusRequested = false;
    private int originalAudioMode = AudioManager.MODE_INVALID;

    private final Map<String, PluginCall> pendingDurationCalls = new HashMap<>();

    // Notification center support
    private boolean showNotification = false;
    private Map<String, Map<String, String>> notificationMetadataMap = new HashMap<>();
    private MediaSessionCompat mediaSession;
    private String currentlyPlayingAssetId;
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "native_audio_channel";

    @Override
    public void load() {
        super.load();

        this.audioManager = (AudioManager) this.getActivity().getSystemService(Context.AUDIO_SERVICE);

        audioAssetList = new HashMap<>();

        // Store the original audio mode but don't request focus yet
        if (this.audioManager != null) {
            originalAudioMode = this.audioManager.getMode();
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        try {
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                // Pause playback - temporary loss
                for (AudioAsset audio : audioAssetList.values()) {
                    if (audio.isPlaying()) {
                        audio.pause();
                        resumeList.add(audio);
                    }
                }
            } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                // Resume playback
                if (resumeList != null) {
                    while (!resumeList.isEmpty()) {
                        AudioAsset audio = resumeList.remove(0);
                        audio.resume();
                    }
                }
            } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                // Stop playback - permanent loss
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
                for (HashMap.Entry<String, AudioAsset> entry : audioAssetList.entrySet()) {
                    AudioAsset audio = entry.getValue();

                    if (audio != null) {
                        boolean wasPlaying = audio.pause();

                        if (wasPlaying) {
                            resumeList.add(audio);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Log.d(TAG, "Exception caught while listening for handleOnPause: " + ex.getLocalizedMessage());
        }
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();

        try {
            if (resumeList != null) {
                while (!resumeList.isEmpty()) {
                    AudioAsset audio = resumeList.remove(0);

                    if (audio != null) {
                        audio.resume();
                    }
                }
            }
        } catch (Exception ex) {
            Log.d(TAG, "Exception caught while listening for handleOnResume: " + ex.getLocalizedMessage());
        }
    }

    @PluginMethod
    public void configure(PluginCall call) {
        initSoundPool();

        if (this.audioManager == null) {
            call.resolve();
            return;
        }

        // Save original audio mode if not already saved
        if (originalAudioMode == AudioManager.MODE_INVALID) {
            originalAudioMode = this.audioManager.getMode();
        }

        boolean focus = call.getBoolean(OPT_FOCUS_AUDIO, false);
        boolean background = call.getBoolean("background", false);
        this.fadeMusic = call.getBoolean("fade", false);
        this.showNotification = call.getBoolean(SHOW_NOTIFICATION, false);

        try {
            if (focus) {
                // Request audio focus for playback with ducking
                int result = this.audioManager.requestAudioFocus(
                    this,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ); // Allow other audio to play quietly
                audioFocusRequested = true;
            } else if (audioFocusRequested) {
                this.audioManager.abandonAudioFocus(this);
                audioFocusRequested = false;
            }

            if (background) {
                // Set playback to continue in background
                this.audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            } else {
                this.audioManager.setMode(AudioManager.MODE_NORMAL);
            }

            if (this.showNotification) {
                setupMediaSession();
                createNotificationChannel();
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error configuring audio", ex);
        }

        call.resolve();
    }

    @PluginMethod
    public void isPreloaded(final PluginCall call) {
        new Thread(
            new Runnable() {
                @Override
                public void run() {
                    initSoundPool();

                    String audioId = call.getString(ASSET_ID);

                    if (!isStringValid(audioId)) {
                        call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                        return;
                    }
                    call.resolve(new JSObject().put("found", audioAssetList.containsKey(audioId)));
                }
            }
        )
            .start();
    }

    @PluginMethod
    public void preload(final PluginCall call) {
        this.getActivity().runOnUiThread(
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
        this.getActivity().runOnUiThread(
            new Runnable() {
                @Override
                public void run() {
                    playOrLoop("play", call);
                }
            }
        );
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
        this.getActivity().runOnUiThread(
            new Runnable() {
                @Override
                public void run() {
                    playOrLoop("loop", call);
                }
            }
        );
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

                    // Update notification when paused
                    if (showNotification) {
                        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
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

                    // Update notification when resumed
                    if (showNotification) {
                        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
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
    public void stop(final PluginCall call) {
        this.getActivity().runOnUiThread(
            new Runnable() {
                @Override
                public void run() {
                    try {
                        String audioId = call.getString(ASSET_ID);
                        if (!isStringValid(audioId)) {
                            call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                            return;
                        }
                        stopAudio(audioId);

                        // Clear notification when stopped
                        if (showNotification) {
                            clearNotification();
                            currentlyPlayingAssetId = null;
                        }

                        call.resolve();
                    } catch (Exception ex) {
                        call.reject(ex.getMessage());
                    }
                }
            }
        );
    }

    @PluginMethod
    public void unload(PluginCall call) {
        try {
            initSoundPool();
            new JSObject();
            JSObject status;

            if (isStringValid(call.getString(ASSET_ID))) {
                String audioId = call.getString(ASSET_ID);

                if (audioAssetList.containsKey(audioId)) {
                    AudioAsset asset = audioAssetList.get(audioId);
                    if (asset != null) {
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

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    asset.setVolume(volume);
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
        RemoteAudioAsset.clearCache(getContext());
        call.resolve();
    }

    @PluginMethod
    public void setCurrentTime(final PluginCall call) {
        try {
            initSoundPool();

            String audioId = call.getString(ASSET_ID);
            Double time = call.getDouble("time", 0.0);

            if (!isStringValid(audioId)) {
                call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                return;
            }

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    this.getActivity().runOnUiThread(
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
        notifyListeners("currentTime", ret);
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

            Log.d("AudioPlugin", "Debug: audioId = " + audioId + ", assetPath = " + assetPath + ", isLocalUrl = " + isLocalUrl);

            if (audioAssetList.containsKey(audioId)) {
                call.reject(ERROR_AUDIO_EXISTS + " - " + audioId);
                return;
            }

            if (isComplex) {
                volume = call.getFloat(VOLUME, 1F);
                audioChannelNum = call.getInt(AUDIO_CHANNEL_NUM, 1);
            }

            // Store notification metadata if provided
            JSObject metadata = call.getObject(NOTIFICATION_METADATA);
            if (metadata != null) {
                Map<String, String> metadataMap = new HashMap<>();
                if (metadata.has("title")) metadataMap.put("title", metadata.getString("title"));
                if (metadata.has("artist")) metadataMap.put("artist", metadata.getString("artist"));
                if (metadata.has("album")) metadataMap.put("album", metadata.getString("album"));
                if (metadata.has("artworkUrl")) metadataMap.put("artworkUrl", metadata.getString("artworkUrl"));
                if (!metadataMap.isEmpty()) {
                    notificationMetadataMap.put(audioId, metadataMap);
                }
            }

            if (isLocalUrl) {
                try {
                    Uri uri = Uri.parse(assetPath);
                    if (uri.getScheme() != null && (uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
                        // Remote URL
                        Log.d("AudioPlugin", "Debug: Remote URL detected: " + uri.toString());

                        // Extract headers if provided
                        Map<String, String> requestHeaders = null;
                        JSObject headersObj = call.getObject("headers");
                        if (headersObj != null) {
                            requestHeaders = new HashMap<>();
                            for (Iterator<String> it = headersObj.keys(); it.hasNext(); ) {
                                String key = it.next();
                                try {
                                    String value = headersObj.getString(key);
                                    if (value != null) {
                                        requestHeaders.put(key, value);
                                    }
                                } catch (Exception e) {
                                    Log.w("AudioPlugin", "Skipping non-string header: " + key);
                                }
                            }
                        }

                        if (assetPath.endsWith(".m3u8")) {
                            // HLS Stream - resolve immediately since it's a stream
                            StreamAudioAsset streamAudioAsset = new StreamAudioAsset(this, audioId, uri, volume, requestHeaders);
                            audioAssetList.put(audioId, streamAudioAsset);
                            call.resolve(status);
                        } else {
                            // Regular remote audio
                            RemoteAudioAsset remoteAudioAsset = new RemoteAudioAsset(
                                this,
                                audioId,
                                uri,
                                audioChannelNum,
                                volume,
                                requestHeaders
                            );
                            remoteAudioAsset.setCompletionListener(this::dispatchComplete);
                            audioAssetList.put(audioId, remoteAudioAsset);
                            call.resolve(status);
                        }
                    } else if (uri.getScheme() != null && uri.getScheme().equals("file")) {
                        // Local file URL
                        Log.d("AudioPlugin", "Debug: Local file URL detected");
                        File file = new File(uri.getPath());
                        if (!file.exists()) {
                            Log.e("AudioPlugin", "Error: File does not exist - " + file.getAbsolutePath());
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
                    Log.e("AudioPlugin", "Error handling URL", e);
                    call.reject("Error handling URL: " + e.getMessage());
                }
            } else {
                // Handle asset in public folder
                Log.d("AudioPlugin", "Debug: Handling asset in public folder");
                if (!assetPath.startsWith("public/")) {
                    assetPath = "public/" + assetPath;
                }
                try {
                    Context ctx = getContext().getApplicationContext();
                    AssetManager am = ctx.getResources().getAssets();
                    AssetFileDescriptor assetFileDescriptor = am.openFd(assetPath);
                    AudioAsset asset = new AudioAsset(this, audioId, assetFileDescriptor, audioChannelNum, volume);
                    asset.setCompletionListener(this::dispatchComplete);
                    audioAssetList.put(audioId, asset);
                    call.resolve(status);
                } catch (IOException e) {
                    Log.e("AudioPlugin", "Error opening asset: " + assetPath, e);
                    call.reject(ERROR_ASSET_PATH_MISSING + " - " + assetPath);
                }
            }
        } catch (Exception ex) {
            Log.e("AudioPlugin", "Error in preloadAsset", ex);
            call.reject("Error in preloadAsset: " + ex.getMessage());
        }
    }

    private void playOrLoop(String action, final PluginCall call) {
        try {
            final String audioId = call.getString(ASSET_ID);
            final Double time = call.getDouble("time", 0.0);
            Log.d(TAG, "Playing asset: " + audioId + ", action: " + action + ", assets count: " + audioAssetList.size());

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                Log.d(TAG, "Found asset: " + audioId + ", type: " + asset.getClass().getSimpleName());

                if (asset != null) {
                    if (LOOP.equals(action)) {
                        asset.loop();
                    } else {
                        if (fadeMusic) {
                            asset.playWithFade(time);
                        } else {
                            asset.play(time);
                        }
                    }

                    // Update notification if enabled
                    if (showNotification) {
                        currentlyPlayingAssetId = audioId;
                        updateNotification(audioId);
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

    private void initSoundPool() {
        if (audioAssetList == null) {
            audioAssetList = new HashMap<>();
        }

        if (resumeList == null) {
            resumeList = new ArrayList<>();
        }
    }

    private boolean isStringValid(String value) {
        return (value != null && !value.isEmpty() && !value.equals("null"));
    }

    private void stopAudio(String audioId) throws Exception {
        if (!audioAssetList.containsKey(audioId)) {
            throw new Exception(ERROR_ASSET_NOT_LOADED);
        }

        AudioAsset asset = audioAssetList.get(audioId);
        if (asset != null) {
            if (fadeMusic) {
                asset.stopWithFade();
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

    @PluginMethod
    public void getPluginVersion(final PluginCall call) {
        try {
            final JSObject ret = new JSObject();
            ret.put("version", this.pluginVersion);
            call.resolve(ret);
        } catch (final Exception e) {
            call.reject("Could not get plugin version", e);
        }
    }

    @PluginMethod
    public void deinitPlugin(final PluginCall call) {
        try {
            // Stop all playing audio
            if (audioAssetList != null) {
                for (AudioAsset asset : audioAssetList.values()) {
                    if (asset != null) {
                        asset.stop();
                    }
                }
            }

            // Clear notification and release media session
            if (showNotification) {
                clearNotification();
                if (mediaSession != null) {
                    mediaSession.release();
                    mediaSession = null;
                }
            }

            // Release audio focus if we requested it
            if (audioFocusRequested && this.audioManager != null) {
                this.audioManager.abandonAudioFocus(this);
                audioFocusRequested = false;
            }

            // Restore original audio mode if we changed it
            if (originalAudioMode != AudioManager.MODE_INVALID && this.audioManager != null) {
                this.audioManager.setMode(originalAudioMode);
                originalAudioMode = AudioManager.MODE_INVALID;
            }

            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Error in deinitPlugin", e);
            call.reject("Error deinitializing plugin: " + e.getMessage());
        }
    }

    // Notification and MediaSession methods

    private void setupMediaSession() {
        if (mediaSession != null) return;

        mediaSession = new MediaSessionCompat(getContext(), "NativeAudio");

        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder().setActions(
            PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_STOP
        );
        mediaSession.setPlaybackState(stateBuilder.build());

        // Set callback for media button events
        mediaSession.setCallback(
            new MediaSessionCompat.Callback() {
                @Override
                public void onPlay() {
                    if (currentlyPlayingAssetId != null && audioAssetList.containsKey(currentlyPlayingAssetId)) {
                        AudioAsset asset = audioAssetList.get(currentlyPlayingAssetId);
                        try {
                            if (asset != null && !asset.isPlaying()) {
                                asset.resume();
                                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error resuming audio from media session", e);
                        }
                    }
                }

                @Override
                public void onPause() {
                    if (currentlyPlayingAssetId != null && audioAssetList.containsKey(currentlyPlayingAssetId)) {
                        AudioAsset asset = audioAssetList.get(currentlyPlayingAssetId);
                        try {
                            if (asset != null) {
                                asset.pause();
                                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error pausing audio from media session", e);
                        }
                    }
                }

                @Override
                public void onStop() {
                    if (currentlyPlayingAssetId != null) {
                        try {
                            stopAudio(currentlyPlayingAssetId);
                            clearNotification();
                            currentlyPlayingAssetId = null;
                        } catch (Exception e) {
                            Log.e(TAG, "Error stopping audio from media session", e);
                        }
                    }
                }
            }
        );

        mediaSession.setActive(true);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Audio Playback", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows currently playing audio");
            NotificationManager notificationManager = getContext().getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private void updateNotification(String audioId) {
        if (mediaSession == null) return;

        Map<String, String> metadata = notificationMetadataMap.get(audioId);
        String title = metadata != null && metadata.containsKey("title") ? metadata.get("title") : "Playing";
        String artist = metadata != null && metadata.containsKey("artist") ? metadata.get("artist") : "";
        String album = metadata != null && metadata.containsKey("album") ? metadata.get("album") : "";
        String artworkUrl = metadata != null ? metadata.get("artworkUrl") : null;

        // Update MediaSession metadata
        MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder();
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, title);
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist);
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album);

        // Load artwork if provided
        if (artworkUrl != null) {
            loadArtwork(artworkUrl, (bitmap) -> {
                if (bitmap != null) {
                    metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap);
                }
                mediaSession.setMetadata(metadataBuilder.build());
                showNotification(title, artist);
            });
        } else {
            mediaSession.setMetadata(metadataBuilder.build());
            showNotification(title, artist);
        }

        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
    }

    private void showNotification(String title, String artist) {
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(getContext(), CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setStyle(
                new androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(android.R.drawable.ic_media_previous, "Previous", null)
            .addAction(android.R.drawable.ic_media_pause, "Pause", null)
            .addAction(android.R.drawable.ic_media_next, "Next", null)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(getContext());
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
    }

    private void clearNotification() {
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(getContext());
        notificationManager.cancel(NOTIFICATION_ID);

        if (mediaSession != null) {
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);
        }
    }

    private void updatePlaybackState(int state) {
        if (mediaSession == null) return;

        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
            .setState(state, 0, state == PlaybackStateCompat.STATE_PLAYING ? 1.0f : 0.0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_STOP);
        mediaSession.setPlaybackState(stateBuilder.build());
    }

    private void loadArtwork(String urlString, ArtworkCallback callback) {
        new Thread(() -> {
            try {
                Uri uri = Uri.parse(urlString);
                Bitmap bitmap = null;

                if (uri.getScheme() == null || uri.getScheme().equals("file")) {
                    // Local file
                    File file = new File(uri.getPath());
                    if (file.exists()) {
                        bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                    }
                } else {
                    // Remote URL
                    URL url = new URL(urlString);
                    bitmap = BitmapFactory.decodeStream(url.openConnection().getInputStream());
                }

                Bitmap finalBitmap = bitmap;
                new Handler(Looper.getMainLooper()).post(() -> callback.onArtworkLoaded(finalBitmap));
            } catch (Exception e) {
                Log.e(TAG, "Error loading artwork", e);
                new Handler(Looper.getMainLooper()).post(() -> callback.onArtworkLoaded(null));
            }
        })
            .start();
    }

    interface ArtworkCallback {
        void onArtworkLoaded(Bitmap bitmap);
    }
}

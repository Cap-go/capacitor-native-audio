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
import static ee.forgr.audio.Constant.NOTIFICATION_METADATA;
import static ee.forgr.audio.Constant.OPT_FOCUS_AUDIO;
import static ee.forgr.audio.Constant.PLAY;
import static ee.forgr.audio.Constant.RATE;
import static ee.forgr.audio.Constant.SHOW_NOTIFICATION;
import static ee.forgr.audio.Constant.TIME;
import static ee.forgr.audio.Constant.VOLUME;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
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
import android.view.KeyEvent;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
    private static final Logger logger = new Logger(TAG);
    public static boolean debugEnabled = false;

    private static ConcurrentHashMap<String, AudioAsset> audioAssetList = new ConcurrentHashMap<>();
    private static CopyOnWriteArrayList<AudioAsset> resumeList;
    private AudioManager audioManager;
    private boolean audioFocusRequested = false;
    private int originalAudioMode = AudioManager.MODE_INVALID;

    private final Map<String, PluginCall> pendingDurationCalls = new ConcurrentHashMap<>();
    private final Map<String, Handler> pendingPlayHandlers = new ConcurrentHashMap<>();
    private final Map<String, Runnable> pendingPlayRunnables = new ConcurrentHashMap<>();
    private final Map<String, JSObject> audioData = new ConcurrentHashMap<>();

    // Notification center support
    private boolean showNotification = false;
    private Map<String, Map<String, String>> notificationMetadataMap = new ConcurrentHashMap<>();
    private MediaSessionCompat mediaSession;
    private String currentlyPlayingAssetId;
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "native_audio_channel";

    // Track playOnce assets for automatic cleanup
    private Set<String> playOnceAssets = new HashSet<>();

    // Background playback support
    private boolean backgroundPlayback = false;

    /**
     * Initializes plugin runtime state by obtaining the system {@link AudioManager}, preparing the asset map,
     * and recording the device's original audio mode without requesting audio focus.
     */
    @Override
    public void load() {
        super.load();

        this.audioManager = (AudioManager) this.getActivity().getSystemService(Context.AUDIO_SERVICE);

        audioAssetList = new ConcurrentHashMap<>();

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

        // Skip automatic pause when background playback is enabled
        if (backgroundPlayback) {
            Log.d(TAG, "Background playback enabled - skipping automatic pause");
            return;
        }

        try {
            if (audioAssetList != null) {
                for (Map.Entry<String, AudioAsset> entry : audioAssetList.entrySet()) {
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

        // Skip automatic resume when background playback is enabled
        if (backgroundPlayback) {
            Log.d(TAG, "Background playback enabled - skipping automatic resume");
            return;
        }

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
    public void setDebugMode(PluginCall call) {
        boolean enabled = Boolean.TRUE.equals(call.getBoolean("enabled", false));
        debugEnabled = enabled;
        if (enabled) {
            logger.info("Debug mode enabled");
        }
        call.resolve();
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
        this.showNotification = call.getBoolean(SHOW_NOTIFICATION, false);
        this.backgroundPlayback = call.getBoolean("backgroundPlayback", false);

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

    /**
     * Initiates preloading of an audio asset described by the plugin call.
     *
     * @param call the PluginCall containing preload options (for example `assetId`, `assetPath`, `isUrl`, `isComplex`, headers, and optional notification metadata); the call will be resolved or rejected when the preload operation completes.
     */
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

    /**
     * Play an audio asset a single time and automatically remove its resources when finished.
     *
     * <p>Preloads the specified asset, optionally starts playback immediately, and ensures the
     * asset is unloaded and any associated notification metadata are cleared after completion or on
     * error. Supports local file paths and remote URLs, HLS streams when available, custom HTTP
     * headers for remote requests, and optional deletion of local source files after playback.
     *
     * @param call Capacitor PluginCall containing options:
     * <ul>
     *   <li><code>assetPath</code> (required): path or URL to the audio file;</li>
     *   <li><code>volume</code> (optional): playback volume (0.1–1.0), default 1.0;</li>
     *   <li><code>isUrl</code> (optional): treat <code>assetPath</code> as a URL when true, default false;</li>
     *   <li><code>autoPlay</code> (optional): start playback immediately when true, default true;</li>
     *   <li><code>deleteAfterPlay</code> (optional): delete the local file after playback when true, default false;</li>
     *   <li><code>headers</code> (optional): JS object of HTTP headers for remote requests;</li>
     *   <li><code>notificationMetadata</code> (optional): object with <code>title</code>, <code>artist</code>,
     *       <code>album</code>, <code>artworkUrl</code> for notification display.</li>
     * </ul>
     */
    @PluginMethod
    public void playOnce(final PluginCall call) {
        this.getActivity().runOnUiThread(
            new Runnable() {
                /**
                 * Preloads a temporary audio asset, optionally plays it one time, and schedules automatic cleanup when playback completes.
                 *
                 * <p>The method generates a unique temporary assetId, validates options (path, volume, local/remote, headers),
                 * loads the asset into the plugin's asset map, registers completion listeners to dispatch the completion event
                 * and to unload/remove notification metadata and tracking state, and optionally deletes the source file from
                 * safe application directories after playback. If configured, it also updates the media notification and returns
                 * the generated `assetId` to the caller.
                 */
                @Override
                public void run() {
                    try {
                        NativeAudio.this.initSoundPool();

                        // Generate unique temporary asset ID
                        final String assetId =
                            "playOnce_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);

                        // Extract options
                        String assetPath = call.getString("assetPath");
                        if (!NativeAudio.this.isStringValid(assetPath)) {
                            call.reject("Asset Path is missing - " + assetPath);
                            return;
                        }

                        boolean autoPlay = call.getBoolean("autoPlay", true);
                        final boolean deleteAfterPlay = call.getBoolean("deleteAfterPlay", false);
                        float volume = call.getFloat(VOLUME, 1F);
                        boolean isLocalUrl = call.getBoolean("isUrl", false);
                        int audioChannelNum = 1; // Single channel for playOnce

                        // Track this as a playOnce asset
                        NativeAudio.this.playOnceAssets.add(assetId);

                        // Store notification metadata if provided
                        JSObject metadata = call.getObject(NOTIFICATION_METADATA);
                        if (metadata != null) {
                            Map<String, String> metadataMap = new HashMap<>();
                            if (metadata.has("title")) metadataMap.put("title", metadata.getString("title"));
                            if (metadata.has("artist")) metadataMap.put("artist", metadata.getString("artist"));
                            if (metadata.has("album")) metadataMap.put("album", metadata.getString("album"));
                            if (metadata.has("artworkUrl")) metadataMap.put("artworkUrl", metadata.getString("artworkUrl"));
                            if (!metadataMap.isEmpty()) {
                                NativeAudio.this.notificationMetadataMap.put(assetId, metadataMap);
                            }
                        }

                        // Preload the asset using the helper method
                        try {
                            // Check if asset already exists
                            if (NativeAudio.this.audioAssetList.containsKey(assetId)) {
                                call.reject(ERROR_AUDIO_EXISTS + " - " + assetId);
                            }

                            // Load the asset using the helper method
                            JSObject headersObj = call.getObject("headers");
                            AudioAsset asset = NativeAudio.this.loadAudioAsset(
                                assetId,
                                assetPath,
                                isLocalUrl,
                                volume,
                                audioChannelNum,
                                headersObj
                            );

                            // Add to asset list; completion listener is set below with cleanup
                            NativeAudio.this.audioAssetList.put(assetId, asset);

                            // Store the file path if we need to delete it later
                            // Only delete local file:// URLs, not remote streaming URLs
                            final String filePathToDelete = (deleteAfterPlay && assetPath.startsWith("file://")) ? assetPath : null;

                            // Set up completion listener for automatic cleanup
                            asset.setCompletionListener(
                                new AudioCompletionListener() {
                                    @Override
                                    public void onCompletion(String completedAssetId) {
                                        // Call the original completion dispatcher first
                                        NativeAudio.this.dispatchComplete(completedAssetId);

                                        // Then perform cleanup
                                        NativeAudio.this.getActivity().runOnUiThread(() -> {
                                            try {
                                                // Unload the asset
                                                AudioAsset assetToUnload = NativeAudio.this.audioAssetList.get(assetId);
                                                if (assetToUnload != null) {
                                                    assetToUnload.unload();
                                                    NativeAudio.this.audioAssetList.remove(assetId);
                                                }

                                                // Remove from tracking sets
                                                NativeAudio.this.playOnceAssets.remove(assetId);
                                                NativeAudio.this.notificationMetadataMap.remove(assetId);

                                                // Clear notification if this was the currently playing asset
                                                if (assetId.equals(NativeAudio.this.currentlyPlayingAssetId)) {
                                                    NativeAudio.this.clearNotification();
                                                    NativeAudio.this.currentlyPlayingAssetId = null;
                                                }

                                                // Delete file if requested
                                                if (filePathToDelete != null) {
                                                    try {
                                                        File fileToDelete = new File(URI.create(filePathToDelete));
                                                        if (fileToDelete.exists() && fileToDelete.delete()) {
                                                            Log.d(TAG, "Deleted file after playOnce: " + filePathToDelete);
                                                        }
                                                    } catch (Exception e) {
                                                        Log.e(TAG, "Error deleting file after playOnce: " + filePathToDelete, e);
                                                    }
                                                }
                                            } catch (Exception e) {
                                                Log.e(TAG, "Error during playOnce cleanup: " + e.getMessage());
                                            }
                                        });
                                    }
                                }
                            );

                            // Auto-play if requested
                            if (autoPlay) {
                                asset.play(0.0);

                                // Update notification if enabled
                                if (showNotification) {
                                    currentlyPlayingAssetId = assetId;
                                    updateNotification(assetId);
                                }
                            }

                            // Return the generated assetId
                            JSObject result = new JSObject();
                            result.put(ASSET_ID, assetId);
                            call.resolve(result);
                        } catch (Exception ex) {
                            // Cleanup on failure
                            NativeAudio.this.playOnceAssets.remove(assetId);
                            NativeAudio.this.notificationMetadataMap.remove(assetId);
                            AudioAsset failedAsset = NativeAudio.this.audioAssetList.get(assetId);
                            if (failedAsset != null) {
                                failedAsset.unload();
                                NativeAudio.this.audioAssetList.remove(assetId);
                            }
                            call.reject("Failed to load asset for playOnce: " + ex.getMessage());
                        }
                    } catch (Exception ex) {
                        call.reject(ex.getMessage());
                    }
                }
            }
        );
    }

    /**
     * Starts playback of a preloaded audio asset on the main (UI) thread.
     *
     * The PluginCall must include:
     * - "assetId" (String): identifier of the preloaded asset to play.
     * - Optional "time" (number): start position in seconds.
     *
     * @param call the PluginCall containing playback parameters
     */
    @PluginMethod
    public void play(final PluginCall call) {
        this.getActivity().runOnUiThread(
            new Runnable() {
                @Override
                public void run() {
                    try {
                        final String audioId = call.getString(ASSET_ID);
                        if (!isStringValid(audioId)) {
                            call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                            return;
                        }

                        final double time = call.getDouble(TIME, 0.0);
                        final double delaySecs = call.getDouble(DELAY, 0.0);
                        final float volume = call.getFloat(VOLUME, 1F);
                        final boolean fadeIn = call.getBoolean(FADE_IN, false);
                        final double fadeInDurationMs =
                            call.getDouble(FADE_IN_DURATION, AudioAsset.DEFAULT_FADE_DURATION_MS / 1000.0) * 1000.0;
                        final boolean fadeOut = call.getBoolean(FADE_OUT, false);
                        final double fadeOutDurationMs =
                            call.getDouble(FADE_OUT_DURATION, AudioAsset.DEFAULT_FADE_DURATION_MS / 1000.0) * 1000.0;
                        final double fadeOutStartTimeSecs = call.getDouble(FADE_OUT_START_TIME, 0.0);

                        cancelPendingPlay(audioId);
                        clearFadeOutToStopTimer(audioId);

                        if (delaySecs > 0) {
                            final Handler handler = new Handler(Looper.getMainLooper());
                            final Runnable runnable = new Runnable() {
                                @Override
                                public void run() {
                                    pendingPlayHandlers.remove(audioId);
                                    pendingPlayRunnables.remove(audioId);
                                    executePlay(
                                        call,
                                        audioId,
                                        time,
                                        volume,
                                        fadeIn,
                                        fadeInDurationMs,
                                        fadeOut,
                                        fadeOutDurationMs,
                                        fadeOutStartTimeSecs
                                    );
                                }
                            };
                            pendingPlayHandlers.put(audioId, handler);
                            pendingPlayRunnables.put(audioId, runnable);
                            handler.postDelayed(runnable, Math.max(0L, (long) (delaySecs * 1000)));
                            return;
                        }

                        executePlay(
                            call,
                            audioId,
                            time,
                            volume,
                            fadeIn,
                            fadeInDurationMs,
                            fadeOut,
                            fadeOutDurationMs,
                            fadeOutStartTimeSecs
                        );
                    } catch (Exception ex) {
                        call.reject(ex.getMessage());
                    }
                }
            }
        );
    }

    private void executePlay(
        PluginCall call,
        String audioId,
        double time,
        float volume,
        boolean fadeIn,
        double fadeInDurationMs,
        boolean fadeOut,
        double fadeOutDurationMs,
        double fadeOutStartTimeSecs
    ) {
        try {
            if (!audioAssetList.containsKey(audioId)) {
                call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
                return;
            }

            AudioAsset asset = audioAssetList.get(audioId);
            if (asset == null) {
                call.reject(ERROR_AUDIO_ASSET_MISSING + " - " + audioId);
                return;
            }

            if (fadeIn) {
                asset.playWithFadeIn(time, volume, fadeInDurationMs);
            } else {
                asset.play(time, volume);
            }

            if (fadeOut) {
                handleFadeOut(asset, audioId, fadeOutDurationMs, fadeOutStartTimeSecs);
            }

            if (showNotification) {
                currentlyPlayingAssetId = audioId;
                updateNotification(audioId);
            }

            call.resolve();
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    private void cancelPendingPlay(String audioId) {
        if (audioId == null) return;
        Handler handler = pendingPlayHandlers.remove(audioId);
        Runnable runnable = pendingPlayRunnables.remove(audioId);
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
            AudioAsset asset = audioAssetList.get(audioId);
            if (asset != null) {
                double duration = asset.getDuration();
                if (duration > 0) {
                    JSObject ret = new JSObject();
                    ret.put("duration", duration);
                    call.resolve(ret);
                } else {
                    saveDurationCall(audioId, call);
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
                    String audioId = call.getString(ASSET_ID);
                    cancelPendingPlay(audioId);
                    clearFadeOutToStopTimer(audioId);
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
            final boolean fadeOut = call.getBoolean(FADE_OUT, false);
            final double fadeOutDurationMs = call.getDouble(FADE_OUT_DURATION, AudioAsset.DEFAULT_FADE_DURATION_MS / 1000.0) * 1000.0;

            cancelPendingPlay(audioId);

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    boolean wasPlaying = asset.isPlaying();

                    JSObject data = getAudioAssetData(audioId);
                    data.put("volumeBeforePause", asset.getVolume());
                    setAudioAssetData(audioId, data);

                    if (fadeOut) {
                        asset.stopWithFade(fadeOutDurationMs, true);
                    } else {
                        asset.pause();
                    }

                    if (wasPlaying) {
                        resumeList.add(asset);
                    }

                    // Update notification when paused
                    if (showNotification) {
                        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
                        updateNotification(audioId);
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
            final boolean fadeIn = call.getBoolean(FADE_IN, false);
            final double fadeInDurationMs = call.getDouble(FADE_IN_DURATION, AudioAsset.DEFAULT_FADE_DURATION_MS / 1000.0) * 1000.0;

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    JSObject data = getAudioAssetData(audioId);
                    float volumeBeforePause = (float) data.optDouble("volumeBeforePause", asset.getVolume());

                    if (fadeIn) {
                        asset.setVolume(0f, 0);
                        asset.resume();
                        asset.setVolume(volumeBeforePause, fadeInDurationMs);
                    } else {
                        asset.setVolume(volumeBeforePause, 0);
                        asset.resume();
                    }

                    data.remove("volumeBeforePause");
                    setAudioAssetData(audioId, data);
                    resumeList.add(asset);

                    // Update notification when resumed
                    if (showNotification) {
                        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
                        updateNotification(audioId);
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
                        boolean fadeOut = call.getBoolean(FADE_OUT, false);
                        double fadeOutDurationMs = call.getDouble(FADE_OUT_DURATION, AudioAsset.DEFAULT_FADE_DURATION_MS / 1000.0) * 1000.0;

                        if (!isStringValid(audioId)) {
                            call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                            return;
                        }

                        cancelPendingPlay(audioId);
                        clearFadeOutToStopTimer(audioId);
                        stopAudio(audioId, fadeOut, fadeOutDurationMs);
                        audioData.remove(audioId);

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
                cancelPendingPlay(audioId);
                pendingPlayHandlers.remove(audioId);
                pendingPlayRunnables.remove(audioId);
                audioData.remove(audioId);
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    clearFadeOutToStopTimer(audioId);
                    asset.unload();
                    audioAssetList.remove(audioId);
                    call.resolve();
                } else {
                    call.reject(ERROR_AUDIO_ASSET_MISSING + " - " + audioId);
                }
            } else {
                call.reject(ERROR_AUDIO_ID_MISSING);
            }
        } catch (Exception ex) {
            String audioId = call.getString(ASSET_ID);
            if (audioId != null) {
                pendingPlayHandlers.remove(audioId);
                pendingPlayRunnables.remove(audioId);
                audioData.remove(audioId);
            }
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
                logger.debug("setVolume " + volume + " over duration " + durationSecs + " seconds");
            } else {
                logger.debug("setVolume " + volume);
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
                if (asset != null) {
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

            String audioId = call.getString(ASSET_ID);
            clearFadeOutToStopTimer(audioId);
            double time = call.getDouble(TIME, 0.0);

            cancelPendingPlay(audioId);

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

    /**
     * Emits a "currentTime" event for the given asset with the playback position rounded to the nearest 0.1 second.
     *
     * The emitted event payload contains `assetId` and `currentTime` (in seconds, rounded to the nearest 0.1).
     *
     * @param assetId     the identifier of the audio asset
     * @param currentTime the current playback time in seconds (will be rounded to nearest 0.1)
     */
    public void notifyCurrentTime(String assetId, double currentTime) {
        // Round to nearest 100ms
        double roundedTime = Math.round(currentTime * 10.0) / 10.0;
        JSObject ret = new JSObject();
        ret.put("currentTime", roundedTime);
        ret.put("assetId", assetId);
        notifyListeners("currentTime", ret);

        JSObject data = getAudioAssetData(assetId);
        if (data.optBoolean("fadeOut", false)) {
            double fadeOutStartTime = data.optDouble("fadeOutStartTime", -1);
            if (fadeOutStartTime >= 0 && currentTime >= fadeOutStartTime) {
                double fadeOutDuration = data.optDouble("fadeOutDuration", AudioAsset.DEFAULT_FADE_DURATION_MS);
                try {
                    AudioAsset asset = audioAssetList.get(assetId);
                    if (asset != null) {
                        asset.stopWithFade(fadeOutDuration, false);
                    }
                } catch (Exception e) {
                    logger.error("Error triggering scheduled fade-out", e);
                }
                clearFadeOutToStopTimer(assetId);
            }
        }
    }

    /**
     * Create an AudioAsset for the given identifier and path, supporting remote URLs (including HLS),
     * local file URIs, and assets in the app's public folder.
     *
     * @param assetId         unique identifier for the asset
     * @param assetPath       file path or URL to the audio resource
     * @param isLocalUrl      true when assetPath is a URL (http/https/file), false when it refers to a public asset path
     * @param volume          initial playback volume (expected range: 0.1 to 1.0)
     * @param audioChannelNum number of audio channels to configure for the asset
     * @param headersObj      optional HTTP headers for remote requests (may be null)
     * @return                an initialized AudioAsset instance for the provided path
     * @throws Exception      if the asset cannot be located or initialized (includes missing file, invalid path, or other load errors)
     */
    private AudioAsset loadAudioAsset(
        String assetId,
        String assetPath,
        boolean isLocalUrl,
        float volume,
        int audioChannelNum,
        JSObject headersObj
    ) throws Exception {
        if (isLocalUrl) {
            Uri uri = Uri.parse(assetPath);
            if (uri.getScheme() != null && (uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
                // Remote URL
                Map<String, String> requestHeaders = null;
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

                if (isHlsUrl(assetPath)) {
                    // HLS Stream - check if HLS support is available
                    if (!HlsAvailabilityChecker.isHlsAvailable()) {
                        throw new Exception(
                            "HLS streaming (.m3u8) is not available. " + "Set 'hls: true' in capacitor.config.ts and run 'npx cap sync'."
                        );
                    }
                    AudioAsset streamAudioAsset = createStreamAudioAsset(assetId, uri, volume, requestHeaders);
                    if (streamAudioAsset == null) {
                        throw new Exception("Failed to create HLS stream player. HLS may not be configured.");
                    }
                    return streamAudioAsset;
                } else {
                    RemoteAudioAsset remoteAudioAsset = new RemoteAudioAsset(this, assetId, uri, audioChannelNum, volume, requestHeaders);
                    return remoteAudioAsset;
                }
            } else if (uri.getScheme() != null && uri.getScheme().equals("file")) {
                File file = new File(uri.getPath());
                if (!file.exists()) {
                    throw new Exception(ERROR_ASSET_PATH_MISSING + " - " + assetPath);
                }
                ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                AssetFileDescriptor afd = new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
                AudioAsset asset = new AudioAsset(this, assetId, afd, audioChannelNum, volume);
                return asset;
            } else {
                // Handle unexpected URI schemes by attempting to treat as local file
                try {
                    File file = new File(uri.getPath());
                    if (!file.exists()) {
                        throw new Exception(ERROR_ASSET_PATH_MISSING + " - " + assetPath);
                    }
                    ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                    AssetFileDescriptor afd = new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
                    AudioAsset asset = new AudioAsset(this, assetId, afd, audioChannelNum, volume);
                    Log.w(TAG, "Unexpected URI scheme '" + uri.getScheme() + "' treated as local file: " + assetPath);
                    return asset;
                } catch (Exception e) {
                    throw new Exception(
                        "Failed to load asset with unexpected URI scheme '" +
                            uri.getScheme() +
                            "' (expected 'http', 'https', or 'file'). Asset path: " +
                            assetPath +
                            ". Error: " +
                            e.getMessage()
                    );
                }
            }
        } else {
            // Handle asset in public folder
            String finalAssetPath = assetPath;
            if (!assetPath.startsWith("public/")) {
                finalAssetPath = "public/" + assetPath;
            }
            Context ctx = getContext().getApplicationContext();
            AssetManager am = ctx.getResources().getAssets();
            AssetFileDescriptor assetFileDescriptor = am.openFd(finalAssetPath);
            AudioAsset asset = new AudioAsset(this, assetId, assetFileDescriptor, audioChannelNum, volume);
            return asset;
        }
    }

    /**
     * Preloads an audio asset into the plugin's asset list.
     *
     * <p>The provided PluginCall must include:
     * <ul>
     *   <li>`assetId` (string) — identifier for the asset</li>
     *   <li>`assetPath` (string) — path or URL to the audio resource</li>
     * </ul>
     * Optional keys on the call:
     * <ul>
     *   <li>`isUrl` (boolean) — true when `assetPath` is a remote URL</li>
     *   <li>`isComplex` (boolean) — when true, `volume` and `audioChannelNum` may be provided</li>
     *   <li>`volume` (number) — initial playback volume (default 1.0)</li>
     *   <li>`audioChannelNum` (int) — audio channel count (default 1)</li>
     *   <li>`headers` (object) — HTTP headers for remote requests</li>
     *   <li>`notificationMetadata` (object) — optional metadata (`title`, `artist`, `album`, `artworkUrl`) to attach to the asset</li>
     * </ul>
     *
     * <p>On success the call is resolved with a status indicating success. The method rejects the call
     * when required parameters are missing, when an asset with the same id already exists, or when
     * the asset cannot be loaded.
     *
     * @param call the PluginCall containing asset parameters and options
     */
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

            // Use the helper method to load the asset
            JSObject headersObj = call.getObject("headers");
            AudioAsset asset = loadAudioAsset(audioId, assetPath, isLocalUrl, volume, audioChannelNum, headersObj);

            if (asset == null) {
                call.reject("Failed to load asset");
                return;
            }

            // Set completion listener and add to asset list
            asset.setCompletionListener(this::dispatchComplete);
            audioAssetList.put(audioId, asset);
            call.resolve(status);
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
                        asset.play(time);
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
            logger.error("Error in playOrLoop", ex);
            call.reject(ex.getMessage());
        }
    }

    private void scheduleFadeOut(AudioAsset asset, double fadeOutDurationMs, double fadeOutStartTimeMs) {
        try {
            double duration = asset.getDuration();
            if (duration > 0) {
                double fadeOutStartTime = duration - (fadeOutDurationMs / 1000.0);
                if (fadeOutStartTimeMs > 0) {
                    fadeOutStartTime = fadeOutStartTimeMs / 1000.0;
                }

                logger.debug("Scheduling fade-out for asset: " + asset.assetId + ", start time: " + fadeOutStartTime + " seconds");

                // Store fade-out parameters in asset data
                JSObject data = getAudioAssetData(asset.assetId);
                data.put("fadeOut", true);
                data.put("fadeOutStartTime", fadeOutStartTime);
                data.put("fadeOutDuration", fadeOutDurationMs);
                setAudioAssetData(asset.assetId, data);
            } else {
                logger.warning("Duration not available, skipping fade-out scheduling");
            }
        } catch (Exception e) {
            logger.error("Error handling fade-out", e);
        }
    }

    private void handleFadeOut(AudioAsset asset, String audioId, double fadeOutDurationMs, double fadeOutStartTimeSecs) {
        try {
            double duration = asset.getDuration();
            if (duration <= 0) {
                logger.warning("Duration not available, skipping fade-out scheduling");
                return;
            }

            double fadeOutStartTime = duration - (fadeOutDurationMs / 1000.0);
            if (fadeOutStartTimeSecs > 0) {
                fadeOutStartTime = fadeOutStartTimeSecs;
            }
            fadeOutStartTime = Math.max(fadeOutStartTime, 0);

            JSObject data = getAudioAssetData(audioId);
            data.put("fadeOut", true);
            data.put("fadeOutStartTime", fadeOutStartTime);
            data.put("fadeOutDuration", fadeOutDurationMs);
            setAudioAssetData(audioId, data);
        } catch (Exception e) {
            logger.error("Error scheduling fade-out", e);
        }
    }

    private void clearFadeOutToStopTimer(String audioId) {
        JSObject data = getAudioAssetData(audioId);
        if (data.has("fadeOut")) {
            logger.debug("Cancelling fade-out for asset: " + audioId);
            data.remove("fadeOut");
            data.remove("fadeOutStartTime");
            data.remove("fadeOutDuration");
            setAudioAssetData(audioId, data);
        }
    }

    private void initSoundPool() {
        if (audioAssetList == null) {
            logger.debug("Initializing audio asset list");
            audioAssetList = new ConcurrentHashMap<>();
        }
        if (resumeList == null) {
            logger.debug("Initializing resume list");
            resumeList = new CopyOnWriteArrayList<>();
        }
    }

    private boolean isStringValid(String value) {
        return (value != null && !value.isEmpty() && !value.equals("null"));
    }

    /**
     * Check if the given URL is an HLS stream by examining the URL path.
     * This handles URLs with query parameters correctly.
     *
     * @param assetPath The URL or path to check
     * @return true if the URL path ends with .m3u8, false otherwise
     */
    private boolean isHlsUrl(String assetPath) {
        if (assetPath == null || assetPath.isEmpty()) {
            return false;
        }

        try {
            Uri uri = Uri.parse(assetPath);
            String path = uri.getPath();
            if (path != null) {
                return path.toLowerCase().endsWith(".m3u8");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse URL for HLS detection: " + assetPath, e);
        }

        // Fallback: check if the URL contains .m3u8 followed by nothing or query params
        return assetPath.toLowerCase().contains(".m3u8");
    }

    /**
     * Creates a StreamAudioAsset via reflection.
     * This allows the StreamAudioAsset class to be excluded at compile time when HLS is disabled,
     * reducing APK size by ~4MB.
     *
     * @param audioId The unique identifier for the audio asset
     * @param uri The URI of the HLS stream
     * @param volume The initial volume (0.0 to 1.0)
     * @param headers Optional HTTP headers for the request
     * @return The created AudioAsset, or null if creation failed
     */
    private AudioAsset createStreamAudioAsset(String audioId, Uri uri, float volume, java.util.Map<String, String> headers) {
        try {
            Class<?> streamAudioAssetClass = Class.forName("ee.forgr.audio.StreamAudioAsset");
            java.lang.reflect.Constructor<?> constructor = streamAudioAssetClass.getConstructor(
                NativeAudio.class,
                String.class,
                Uri.class,
                float.class,
                java.util.Map.class
            );
            return (AudioAsset) constructor.newInstance(this, audioId, uri, volume, headers);
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "StreamAudioAsset class not found. HLS support is not included in this build.", e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create StreamAudioAsset", e);
            return null;
        }
    }

    private void stopAudio(String audioId, boolean fadeOut, double fadeOutDurationMs) throws Exception {
        if (!audioAssetList.containsKey(audioId)) {
            throw new Exception(ERROR_ASSET_NOT_LOADED);
        }

        AudioAsset asset = audioAssetList.get(audioId);
        if (asset != null) {
            if (fadeOut) {
                asset.stopWithFade(fadeOutDurationMs, false);
            } else {
                asset.stop();
            }
        }
    }

    private void saveDurationCall(String audioId, PluginCall call) {
        logger.debug("Saving duration call for later: " + audioId);
        pendingDurationCalls.put(audioId, call);
    }

    public void notifyDurationAvailable(String assetId, double duration) {
        logger.debug("Duration available for " + assetId + ": " + duration);
        PluginCall savedCall = pendingDurationCalls.remove(assetId);
        if (savedCall != null) {
            JSObject ret = new JSObject();
            ret.put("duration", duration);
            savedCall.resolve(ret);
        }
    }

    private JSObject getAudioAssetData(String audioId) {
        JSObject data = audioData.get(audioId);
        if (data == null) {
            data = new JSObject();
        }
        return data;
    }

    private void setAudioAssetData(String audioId, JSObject data) {
        audioData.put(audioId, data);
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
            PlaybackStateCompat.ACTION_PLAY |
            PlaybackStateCompat.ACTION_PAUSE |
            PlaybackStateCompat.ACTION_STOP |
            PlaybackStateCompat.ACTION_REWIND |
            PlaybackStateCompat.ACTION_FAST_FORWARD |
            PlaybackStateCompat.ACTION_SEEK_TO
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
                                updateNotification(currentlyPlayingAssetId);
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
                                updateNotification(currentlyPlayingAssetId);
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
                            stopAudio(currentlyPlayingAssetId, false, 0);
                            clearNotification();
                            currentlyPlayingAssetId = null;
                        } catch (Exception e) {
                            Log.e(TAG, "Error stopping audio from media session", e);
                        }
                    }
                }

                @Override
                public void onRewind() {
                    if (currentlyPlayingAssetId != null && audioAssetList.containsKey(currentlyPlayingAssetId)) {
                        AudioAsset asset = audioAssetList.get(currentlyPlayingAssetId);
                        try {
                            if (asset != null) {
                                // Skip backward 15 seconds
                                double currentPosition = asset.getCurrentPosition();
                                double newPosition = Math.max(0, currentPosition - 15.0);
                                asset.setCurrentPosition(newPosition);
                                Log.d(TAG, "Rewind 15s: " + currentPosition + " -> " + newPosition);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error rewinding audio from media session", e);
                        }
                    }
                }

                @Override
                public void onFastForward() {
                    if (currentlyPlayingAssetId != null && audioAssetList.containsKey(currentlyPlayingAssetId)) {
                        AudioAsset asset = audioAssetList.get(currentlyPlayingAssetId);
                        try {
                            if (asset != null) {
                                // Skip forward 15 seconds
                                double currentPosition = asset.getCurrentPosition();
                                double duration = asset.getDuration();
                                double newPosition = Math.min(duration, currentPosition + 15.0);
                                asset.setCurrentPosition(newPosition);
                                Log.d(TAG, "Fast forward 15s: " + currentPosition + " -> " + newPosition);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error fast forwarding audio from media session", e);
                        }
                    }
                }

                @Override
                public void onSeekTo(long pos) {
                    if (currentlyPlayingAssetId != null && audioAssetList.containsKey(currentlyPlayingAssetId)) {
                        AudioAsset asset = audioAssetList.get(currentlyPlayingAssetId);
                        try {
                            if (asset != null) {
                                // Convert milliseconds to seconds
                                double positionInSeconds = pos / 1000.0;
                                asset.setCurrentPosition(positionInSeconds);
                                Log.d(TAG, "Seek to: " + positionInSeconds);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error seeking audio from media session", e);
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
        // Determine if currently playing
        boolean isPlaying = false;
        if (currentlyPlayingAssetId != null && audioAssetList.containsKey(currentlyPlayingAssetId)) {
            AudioAsset asset = audioAssetList.get(currentlyPlayingAssetId);
            if (asset != null) {
                try {
                    isPlaying = asset.isPlaying();
                } catch (Exception e) {
                    Log.e(TAG, "Error checking playback state", e);
                }
            }
        }

        // Build notification with proper action order: Rewind, Play/Pause, Fast Forward
        // The MediaStyle will automatically wire these to the MediaSession callbacks
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(getContext(), CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_rew, "Rewind 15 seconds", createMediaButtonPendingIntent(PlaybackStateCompat.ACTION_REWIND))
            .addAction(
                isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                isPlaying ? "Pause" : "Play",
                createMediaButtonPendingIntent(isPlaying ? PlaybackStateCompat.ACTION_PAUSE : PlaybackStateCompat.ACTION_PLAY)
            )
            .addAction(android.R.drawable.ic_media_ff, "Fast forward 15 seconds", createMediaButtonPendingIntent(PlaybackStateCompat.ACTION_FAST_FORWARD))
            .setStyle(
                new androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(getContext());
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
    }

    private PendingIntent createMediaButtonPendingIntent(long action) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        intent.setPackage(getContext().getPackageName());
        
        // Create a key event for the action
        KeyEvent keyEvent = null;
        if (action == PlaybackStateCompat.ACTION_PLAY) {
            keyEvent = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY);
        } else if (action == PlaybackStateCompat.ACTION_PAUSE) {
            keyEvent = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE);
        } else if (action == PlaybackStateCompat.ACTION_REWIND) {
            keyEvent = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_REWIND);
        } else if (action == PlaybackStateCompat.ACTION_FAST_FORWARD) {
            keyEvent = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
        }
        
        if (keyEvent != null) {
            intent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
        }
        
        return PendingIntent.getBroadcast(
            getContext(),
            (int) action,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
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
            .setActions(
                PlaybackStateCompat.ACTION_PLAY |
                PlaybackStateCompat.ACTION_PAUSE |
                PlaybackStateCompat.ACTION_STOP |
                PlaybackStateCompat.ACTION_REWIND |
                PlaybackStateCompat.ACTION_FAST_FORWARD |
                PlaybackStateCompat.ACTION_SEEK_TO
            );
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

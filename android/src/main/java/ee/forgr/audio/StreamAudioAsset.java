package ee.forgr.audio;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;

@UnstableApi
public class StreamAudioAsset extends AudioAsset {

    private static final String TAG = "StreamAudioAsset";
    private ExoPlayer player;
    private final Uri uri;
    private float volume;
    private boolean isPrepared = false;
    private final float initialVolume;
    private static final float FADE_STEP = 0.05f;
    private static final int FADE_DELAY_MS = 80; // 80ms between steps
    private static final long LIVE_OFFSET_MS = 5000; // 5 seconds behind live
    private final java.util.Map<String, String> headers;

    public StreamAudioAsset(NativeAudio owner, String assetId, Uri uri, float volume, java.util.Map<String, String> headers)
        throws Exception {
        super(owner, assetId, null, 0, volume);
        this.uri = uri;
        this.volume = volume;
        this.initialVolume = volume;
        this.headers = headers;

        createPlayer();
    }

    private void createPlayer() {
        // Adjust buffer settings for smoother playback
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                60000, // Increase min buffer to 60s
                180000, // Increase max buffer to 180s
                5000, // Increase buffer for playback
                10000 // Increase buffer to start playback
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(60000, true) // Increase back buffer
            .build();

        player = new ExoPlayer.Builder(owner.getContext())
            .setLoadControl(loadControl)
            .setLivePlaybackSpeedControl(
                new DefaultLivePlaybackSpeedControl.Builder()
                    .setFallbackMaxPlaybackSpeed(1.04f)
                    .setMaxLiveOffsetErrorMsForUnitSpeed(LIVE_OFFSET_MS)
                    .build()
            )
            .build();

        player.setVolume(volume);
        initializePlayer();
    }

    private void initializePlayer() {
        Log.d(TAG, "Initializing stream player with volume: " + volume);

        // Configure HLS source with better settings for live streaming
        DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setUserAgent("ExoPlayer");

        // Add custom headers if provided
        if (headers != null && !headers.isEmpty()) {
            httpDataSourceFactory.setDefaultRequestProperties(headers);
        }

        HlsMediaSource mediaSource = new HlsMediaSource.Factory(httpDataSourceFactory)
            .setAllowChunklessPreparation(true)
            .setTimestampAdjusterInitializationTimeoutMs(LIVE_OFFSET_MS) // 30 seconds timeout
            .createMediaSource(MediaItem.fromUri(uri));

        player.setMediaSource(mediaSource);
        player.setVolume(volume);
        player.prepare();

        player.addListener(
            new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    Log.d(TAG, "Stream state changed to: " + getStateString(state));
                    if (state == Player.STATE_READY && !isPrepared) {
                        isPrepared = true;
                        if (player.isCurrentMediaItemLive()) {
                            player.seekToDefaultPosition();
                        }
                    }
                }

                @Override
                public void onIsLoadingChanged(boolean isLoading) {
                    Log.d(TAG, "Loading state changed: " + isLoading);
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    Log.d(TAG, "Playing state changed: " + isPlaying);
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "Player error: " + error.getMessage());
                    isPrepared = false;
                    // Try to recover by recreating the player
                    owner
                        .getActivity()
                        .runOnUiThread(() -> {
                            player.release();
                            createPlayer();
                        });
                }
            }
        );
    }

    private String getStateString(int state) {
        switch (state) {
            case Player.STATE_IDLE:
                return "IDLE";
            case Player.STATE_BUFFERING:
                return "BUFFERING";
            case Player.STATE_READY:
                return "READY";
            case Player.STATE_ENDED:
                return "ENDED";
            default:
                return "UNKNOWN(" + state + ")";
        }
    }

    @Override
    public void play(Double time) throws Exception {
        Log.d(TAG, "Play called with time: " + time + ", isPrepared: " + isPrepared);
        owner
            .getActivity()
            .runOnUiThread(() -> {
                if (!isPrepared) {
                    // If not prepared, wait for preparation
                    player.addListener(
                        new Player.Listener() {
                            @Override
                            public void onPlaybackStateChanged(int state) {
                                Log.d(TAG, "Play-wait state changed to: " + getStateString(state));
                                if (state == Player.STATE_READY) {
                                    startPlayback(time);
                                    player.removeListener(this);
                                }
                            }
                        }
                    );
                } else {
                    startPlayback(time);
                }
            });
    }

    private void startPlayback(Double time) {
        Log.d(TAG, "Starting playback with time: " + time);
        if (time != null) {
            player.seekTo(Math.round(time * 1000));
        } else if (player.isCurrentMediaItemLive()) {
            player.seekToDefaultPosition();
        }
        player.setPlaybackParameters(new PlaybackParameters(1.0f));
        player.setVolume(volume);
        player.setPlayWhenReady(true);
    }

    @Override
    public boolean pause() throws Exception {
        final boolean[] wasPlaying = { false };
        owner
            .getActivity()
            .runOnUiThread(() -> {
                if (player.isPlaying()) {
                    player.setPlayWhenReady(false);
                    wasPlaying[0] = true;
                }
            });
        return wasPlaying[0];
    }

    @Override
    public void resume() throws Exception {
        owner
            .getActivity()
            .runOnUiThread(() -> {
                player.setPlayWhenReady(true);
            });
    }

    @Override
    public void stop() throws Exception {
        owner
            .getActivity()
            .runOnUiThread(() -> {
                // First stop playback
                player.stop();
                // Reset player state
                player.clearMediaItems();
                isPrepared = false;

                // Create new media source
                DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(15000)
                    .setUserAgent("ExoPlayer");

                // Add custom headers if provided
                if (headers != null && !headers.isEmpty()) {
                    httpDataSourceFactory.setDefaultRequestProperties(headers);
                }

                HlsMediaSource mediaSource = new HlsMediaSource.Factory(httpDataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .setTimestampAdjusterInitializationTimeoutMs(LIVE_OFFSET_MS)
                    .createMediaSource(MediaItem.fromUri(uri));

                // Set new media source and prepare
                player.setMediaSource(mediaSource);
                player.prepare();

                // Add listener for preparation completion
                player.addListener(
                    new Player.Listener() {
                        @Override
                        public void onPlaybackStateChanged(int state) {
                            Log.d(TAG, "Stop-reinit state changed to: " + getStateString(state));
                            if (state == Player.STATE_READY) {
                                isPrepared = true;
                                player.removeListener(this);
                            } else if (state == Player.STATE_IDLE) {
                                // Retry preparation if it fails
                                player.prepare();
                            }
                        }
                    }
                );
            });
    }

    @Override
    public void loop() throws Exception {
        owner
            .getActivity()
            .runOnUiThread(() -> {
                player.setRepeatMode(Player.REPEAT_MODE_ONE);
                player.setPlayWhenReady(true);
            });
    }

    @Override
    public void unload() throws Exception {
        owner
            .getActivity()
            .runOnUiThread(() -> {
                player.stop();
                player.clearMediaItems();
                player.release();
                isPrepared = false;
            });
    }

    @Override
    public void setVolume(float volume) throws Exception {
        this.volume = volume;
        owner
            .getActivity()
            .runOnUiThread(() -> {
                Log.d(TAG, "Setting volume to: " + volume);
                player.setVolume(volume);
            });
    }

    @Override
    public boolean isPlaying() throws Exception {
        return player != null && player.isPlaying();
    }

    @Override
    public double getDuration() {
        if (isPrepared) {
            final double[] duration = { 0 };
            owner
                .getActivity()
                .runOnUiThread(() -> {
                    if (player.getPlaybackState() == Player.STATE_READY) {
                        long rawDuration = player.getDuration();
                        if (rawDuration != androidx.media3.common.C.TIME_UNSET) {
                            duration[0] = rawDuration / 1000.0;
                        }
                    }
                });
            return duration[0];
        }
        return 0;
    }

    @Override
    public double getCurrentPosition() {
        if (isPrepared) {
            final double[] position = { 0 };
            owner
                .getActivity()
                .runOnUiThread(() -> {
                    if (player.getPlaybackState() == Player.STATE_READY) {
                        position[0] = player.getCurrentPosition() / 1000.0;
                    }
                });
            return position[0];
        }
        return 0;
    }

    @Override
    public void setCurrentTime(double time) throws Exception {
        owner
            .getActivity()
            .runOnUiThread(() -> {
                player.seekTo(Math.round(time * 1000));
            });
    }

    @Override
    public void playWithFade(Double time) throws Exception {
        Log.d(TAG, "PlayWithFade called with time: " + time);
        owner
            .getActivity()
            .runOnUiThread(() -> {
                if (!isPrepared) {
                    // If not prepared, wait for preparation
                    player.addListener(
                        new Player.Listener() {
                            @Override
                            public void onPlaybackStateChanged(int state) {
                                if (state == Player.STATE_READY) {
                                    startPlaybackWithFade(time);
                                    player.removeListener(this);
                                }
                            }
                        }
                    );
                } else {
                    startPlaybackWithFade(time);
                }
            });
    }

    private void startPlaybackWithFade(Double time) {
        if (!player.isPlayingAd()) {
            // Make sure we're not in an ad
            if (time != null) {
                player.seekTo(Math.round(time * 1000));
            } else if (player.isCurrentMediaItemLive()) {
                long liveEdge = player.getCurrentLiveOffset();
                if (liveEdge > 0) {
                    player.seekTo(liveEdge - LIVE_OFFSET_MS);
                }
            }

            // Wait for buffering to complete before starting playback
            player.addListener(
                new Player.Listener() {
                    @Override
                    public void onPlaybackStateChanged(int state) {
                        if (state == Player.STATE_READY) {
                            player.removeListener(this);
                            // Ensure playback rate is normal
                            player.setPlaybackParameters(new PlaybackParameters(1.0f));
                            // Start with volume 0
                            player.setVolume(0);
                            player.setPlayWhenReady(true);
                            // Start fade after ensuring we're actually playing
                            checkAndStartFade();
                        }
                    }
                }
            );
        }
    }

    private void checkAndStartFade() {
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(
            new Runnable() {
                int attempts = 0;

                @Override
                public void run() {
                    if (player.isPlaying()) {
                        fadeIn();
                    } else if (attempts < 10) {
                        // Try for 5 seconds (10 * 500ms)
                        attempts++;
                        handler.postDelayed(this, 500);
                    }
                }
            },
            500
        );
    }

    private void fadeIn() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable fadeRunnable = new Runnable() {
            float currentVolume = 0;

            @Override
            public void run() {
                if (player != null && player.isPlaying() && currentVolume < volume) {
                    currentVolume += FADE_STEP;
                    if (currentVolume > volume) currentVolume = volume;
                    player.setVolume(currentVolume);
                    Log.d(TAG, "Fading in: volume = " + currentVolume);
                    handler.postDelayed(this, FADE_DELAY_MS);
                }
            }
        };
        handler.post(fadeRunnable);
    }

    @Override
    public void stopWithFade() throws Exception {
        owner
            .getActivity()
            .runOnUiThread(() -> {
                if (player.isPlaying()) {
                    fadeOut();
                }
            });
    }

    private void fadeOut() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable fadeRunnable = new Runnable() {
            float currentVolume = player.getVolume();

            @Override
            public void run() {
                if (currentVolume > FADE_STEP) {
                    currentVolume -= FADE_STEP;
                    player.setVolume(currentVolume);
                    Log.d(TAG, "Fading out: volume = " + currentVolume);
                    handler.postDelayed(this, FADE_DELAY_MS);
                } else {
                    player.setVolume(0);
                    // Stop and reset player
                    player.stop();
                    player.clearMediaItems();
                    isPrepared = false;

                    // Create new media source
                    DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(15000)
                        .setReadTimeoutMs(15000)
                        .setUserAgent("ExoPlayer");

                    // Add custom headers if provided
                    if (headers != null && !headers.isEmpty()) {
                        httpDataSourceFactory.setDefaultRequestProperties(headers);
                    }

                    HlsMediaSource mediaSource = new HlsMediaSource.Factory(httpDataSourceFactory)
                        .setAllowChunklessPreparation(true)
                        .setTimestampAdjusterInitializationTimeoutMs(LIVE_OFFSET_MS)
                        .createMediaSource(MediaItem.fromUri(uri));

                    // Set new media source and prepare
                    player.setMediaSource(mediaSource);
                    player.prepare();

                    // Add listener for preparation completion
                    player.addListener(
                        new Player.Listener() {
                            @Override
                            public void onPlaybackStateChanged(int state) {
                                Log.d(TAG, "Fade-stop state changed to: " + getStateString(state));
                                if (state == Player.STATE_READY) {
                                    isPrepared = true;
                                    player.removeListener(this);
                                }
                            }
                        }
                    );
                }
            }
        };
        handler.post(fadeRunnable);
    }

    @Override
    public void setRate(float rate) throws Exception {
        owner
            .getActivity()
            .runOnUiThread(() -> {
                Log.d(TAG, "Setting playback rate to: " + rate);
                player.setPlaybackParameters(new PlaybackParameters(rate));
            });
    }
}

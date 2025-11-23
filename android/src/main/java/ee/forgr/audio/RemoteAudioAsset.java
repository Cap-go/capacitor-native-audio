package ee.forgr.audio;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import java.io.File;
import java.util.ArrayList;
import java.util.Map;

@UnstableApi
public class RemoteAudioAsset extends AudioAsset {

    private static final String TAG = "RemoteAudioAsset";
    private final ArrayList<ExoPlayer> players;
    private int playIndex = 0;
    private final Uri uri;
    private float volume;
    private boolean isPrepared = false;
    private static SimpleCache cache;
    private static final long MAX_CACHE_SIZE = 100 * 1024 * 1024; // 100MB cache
    protected AudioCompletionListener completionListener;
    private static final float FADE_STEP = 0.05f;
    private static final int FADE_DELAY_MS = 80; // 80ms between steps
    private float initialVolume;
    private Handler currentTimeHandler;
    private Runnable currentTimeRunnable;
    private final Map<String, String> headers;

    public RemoteAudioAsset(NativeAudio owner, String assetId, Uri uri, int audioChannelNum, float volume, Map<String, String> headers)
        throws Exception {
        super(owner, assetId, null, 0, volume);
        this.uri = uri;
        this.volume = volume;
        this.initialVolume = volume;
        this.players = new ArrayList<>();
        this.headers = headers;

        if (audioChannelNum < 1) {
            audioChannelNum = 1;
        }

        final int channels = audioChannelNum;
        owner
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            for (int i = 0; i < channels; i++) {
                                ExoPlayer player = new ExoPlayer.Builder(owner.getContext()).build();
                                player.setPlaybackSpeed(1.0f);
                                players.add(player);
                                initializePlayer(player);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error initializing players", e);
                        }
                    }
                }
            );
    }

    @UnstableApi
    private void initializePlayer(ExoPlayer player) {
        Log.d(TAG, "Initializing player");

        // Initialize cache if not already done
        if (cache == null) {
            File cacheDir = new File(owner.getContext().getCacheDir(), "media");
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            cache = new SimpleCache(
                cacheDir,
                new LeastRecentlyUsedCacheEvictor(MAX_CACHE_SIZE),
                new StandaloneDatabaseProvider(owner.getContext())
            );
        }

        // Create cached data source factory with custom headers
        DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000);

        // Add custom headers if provided
        if (headers != null && !headers.isEmpty()) {
            httpDataSourceFactory.setDefaultRequestProperties(headers);
        }

        CacheDataSource.Factory cacheDataSourceFactory = new CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);

        // Create media source
        MediaSource mediaSource = new ProgressiveMediaSource.Factory(cacheDataSourceFactory).createMediaSource(MediaItem.fromUri(uri));

        player.setMediaSource(mediaSource);
        player.setVolume(volume);
        player.prepare();

        // Add listener for duration
        player.addListener(
            new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    Log.d(TAG, "Player state changed to: " + getStateString(playbackState));
                    if (playbackState == Player.STATE_READY) {
                        isPrepared = true;
                        long duration = player.getDuration();
                        Log.d(TAG, "Duration available on STATE_READY: " + duration + " ms");
                        if (duration != androidx.media3.common.C.TIME_UNSET) {
                            double durationSec = duration / 1000.0;
                            Log.d(TAG, "Notifying duration: " + durationSec + " seconds");
                            owner.notifyDurationAvailable(assetId, durationSec);
                        }
                    } else if (playbackState == Player.STATE_ENDED) {
                        notifyCompletion();
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    Log.d(TAG, "isPlaying changed to: " + isPlaying + ", state: " + getStateString(player.getPlaybackState()));
                }

                @Override
                public void onIsLoadingChanged(boolean isLoading) {
                    Log.d(TAG, "isLoading changed to: " + isLoading + ", state: " + getStateString(player.getPlaybackState()));
                }
            }
        );

        Log.d(TAG, "Player initialization complete");
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
        if (players.isEmpty()) {
            throw new Exception("No ExoPlayer available");
        }

        final ExoPlayer player = players.get(playIndex);
        owner
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        if (!isPrepared) {
                            player.addListener(
                                new Player.Listener() {
                                    @Override
                                    public void onPlaybackStateChanged(int playbackState) {
                                        if (playbackState == Player.STATE_READY) {
                                            isPrepared = true;
                                            try {
                                                playInternal(player, time);
                                                startCurrentTimeUpdates();
                                            } catch (Exception e) {
                                                Log.e(TAG, "Error playing after prepare", e);
                                            }
                                        } else if (playbackState == Player.STATE_ENDED) {
                                            notifyCompletion();
                                        }
                                    }
                                }
                            );
                        } else {
                            try {
                                playInternal(player, time);
                                startCurrentTimeUpdates();
                            } catch (Exception e) {
                                Log.e(TAG, "Error playing", e);
                            }
                        }
                    }
                }
            );

        playIndex = (playIndex + 1) % players.size();
    }

    private void playInternal(final ExoPlayer player, final Double time) throws Exception {
        owner
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        if (time != null) {
                            player.seekTo(Math.round(time * 1000));
                        }
                        player.play();
                    }
                }
            );
    }

    @Override
    public boolean pause() throws Exception {
        final boolean[] wasPlaying = { false };
        owner
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        for (ExoPlayer player : players) {
                            if (player.isPlaying()) {
                                player.pause();
                                wasPlaying[0] = true;
                            }
                        }
                    }
                }
            );
        return wasPlaying[0];
    }

    @Override
    public void resume() throws Exception {
        owner
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        for (ExoPlayer player : players) {
                            if (!player.isPlaying()) {
                                player.play();
                            }
                        }
                        startCurrentTimeUpdates();
                    }
                }
            );
    }

    @Override
    public void stop() throws Exception {
        owner
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        for (ExoPlayer player : players) {
                            if (player.isPlaying()) {
                                player.stop();
                            }
                            // Reset the ExoPlayer to make it ready for future playback
                            initializePlayer(player);
                        }
                        isPrepared = false;
                    }
                }
            );
    }

    @Override
    public void loop() throws Exception {
        owner
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        if (!players.isEmpty()) {
                            ExoPlayer player = players.get(playIndex);
                            player.setRepeatMode(Player.REPEAT_MODE_ONE);
                            player.play();
                            playIndex = (playIndex + 1) % players.size();
                            startCurrentTimeUpdates();
                        }
                    }
                }
            );
    }

    @Override
    public void unload() throws Exception {
        for (ExoPlayer player : players) {
            player.release();
        }
        players.clear();
    }

    @Override
    public void setVolume(final float volume) throws Exception {
        this.volume = volume;
        owner
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        for (ExoPlayer player : players) {
                            player.setVolume(volume);
                        }
                    }
                }
            );
    }

    @Override
    public boolean isPlaying() throws Exception {
        if (players.isEmpty() || !isPrepared) return false;

        ExoPlayer player = players.get(playIndex);
        return player != null && player.isPlaying();
    }

    @Override
    public double getDuration() {
        Log.d(TAG, "getDuration called, players empty: " + players.isEmpty() + ", isPrepared: " + isPrepared);
        if (!players.isEmpty() && isPrepared) {
            final double[] duration = { 0 };
            owner
                .getActivity()
                .runOnUiThread(
                    new Runnable() {
                        @Override
                        public void run() {
                            ExoPlayer player = players.get(playIndex);
                            int state = player.getPlaybackState();
                            Log.d(TAG, "Player state: " + state + " (READY=" + Player.STATE_READY + ")");
                            if (state == Player.STATE_READY) {
                                long rawDuration = player.getDuration();
                                Log.d(TAG, "Raw duration: " + rawDuration + ", TIME_UNSET=" + androidx.media3.common.C.TIME_UNSET);
                                if (rawDuration != androidx.media3.common.C.TIME_UNSET) {
                                    duration[0] = rawDuration / 1000.0;
                                    Log.d(TAG, "Final duration in seconds: " + duration[0]);
                                } else {
                                    Log.d(TAG, "Duration is TIME_UNSET");
                                }
                            } else {
                                Log.d(TAG, "Player not in READY state");
                            }
                        }
                    }
                );
            return duration[0];
        }
        Log.d(TAG, "No players or not prepared for duration");
        return 0;
    }

    @Override
    public double getCurrentPosition() {
        if (!players.isEmpty() && isPrepared) {
            final double[] position = { 0 };
            owner
                .getActivity()
                .runOnUiThread(
                    new Runnable() {
                        @Override
                        public void run() {
                            ExoPlayer player = players.get(playIndex);
                            if (player.getPlaybackState() == Player.STATE_READY) {
                                long rawPosition = player.getCurrentPosition();
                                Log.d(TAG, "Raw position: " + rawPosition);
                                position[0] = rawPosition / 1000.0;
                            }
                        }
                    }
                );
            return position[0];
        }
        return 0;
    }

    @Override
    public void setCurrentTime(double time) throws Exception {
        if (players.isEmpty()) {
            throw new Exception("No ExoPlayer available");
        }

        final ExoPlayer player = players.get(playIndex);
        owner
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        if (isPrepared) {
                            player.seekTo(Math.round(time * 1000));
                        } else {
                            player.addListener(
                                new Player.Listener() {
                                    @Override
                                    public void onPlaybackStateChanged(int playbackState) {
                                        if (playbackState == Player.STATE_READY) {
                                            isPrepared = true;
                                            player.seekTo(Math.round(time * 1000));
                                        }
                                    }
                                }
                            );
                        }
                    }
                }
            );
    }

    @UnstableApi
    public static void clearCache(Context context) {
        try {
            if (cache != null) {
                cache.release();
                cache = null;
            }
            File cacheDir = new File(context.getCacheDir(), "media");
            if (cacheDir.exists()) {
                deleteDir(cacheDir);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error clearing audio cache", e);
        }
    }

    private static boolean deleteDir(File dir) {
        if (dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String child : children) {
                    boolean success = deleteDir(new File(dir, child));
                    if (!success) {
                        return false;
                    }
                }
            }
        }
        return dir.delete();
    }

    public void setCompletionListener(AudioCompletionListener listener) {
        this.completionListener = listener;
    }

    protected void notifyCompletion() {
        if (completionListener != null) {
            completionListener.onCompletion(getAssetId());
        }
    }

    public void playWithFade(Double time) throws Exception {
        if (players.isEmpty()) {
            throw new Exception("No ExoPlayer available");
        }

        final ExoPlayer player = players.get(playIndex);
        owner
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        if (!player.isPlaying()) {
                            if (time != null) {
                                player.seekTo(Math.round(time * 1000));
                            }
                            player.setVolume(0);
                            player.play();
                            startCurrentTimeUpdates();
                            fadeIn(player);
                        }
                    }
                }
            );
    }

    private void fadeIn(final ExoPlayer player) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable fadeRunnable = new Runnable() {
            float currentVolume = 0;

            @Override
            public void run() {
                if (currentVolume < initialVolume) {
                    currentVolume += FADE_STEP;
                    player.setVolume(currentVolume);
                    handler.postDelayed(this, FADE_DELAY_MS);
                }
            }
        };
        handler.post(fadeRunnable);
    }

    public void stopWithFade() throws Exception {
        if (players.isEmpty()) {
            return;
        }

        final ExoPlayer player = players.get(playIndex);
        owner
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        if (player.isPlaying()) {
                            fadeOut(player);
                        }
                    }
                }
            );
    }

    private void fadeOut(final ExoPlayer player) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable fadeRunnable = new Runnable() {
            float currentVolume = player.getVolume();

            @Override
            public void run() {
                if (currentVolume > FADE_STEP) {
                    currentVolume -= FADE_STEP;
                    player.setVolume(currentVolume);
                    handler.postDelayed(this, FADE_DELAY_MS);
                } else {
                    player.setVolume(0);
                    player.stop();
                }
            }
        };
        handler.post(fadeRunnable);
    }

    @Override
    protected void startCurrentTimeUpdates() {
        Log.d(TAG, "Starting timer updates in RemoteAudioAsset");
        if (currentTimeHandler == null) {
            currentTimeHandler = new Handler(Looper.getMainLooper());
        }

        // Wait for player to be truly ready
        currentTimeHandler.postDelayed(
            new Runnable() {
                @Override
                public void run() {
                    if (!players.isEmpty()) {
                        ExoPlayer player = players.get(playIndex);
                        if (player.getPlaybackState() == Player.STATE_READY) {
                            startTimeUpdateLoop();
                        } else {
                            // Check again in 100ms
                            currentTimeHandler.postDelayed(this, 100);
                        }
                    }
                }
            },
            100
        );
    }

    private void startTimeUpdateLoop() {
        currentTimeRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (!players.isEmpty()) {
                        ExoPlayer player = players.get(playIndex);
                        if (player.getPlaybackState() == Player.STATE_READY && player.isPlaying()) {
                            double currentTime = player.getCurrentPosition() / 1000.0; // Get time directly
                            Log.d(TAG, "Timer update: currentTime = " + currentTime);
                            owner.notifyCurrentTime(assetId, currentTime);
                            currentTimeHandler.postDelayed(this, 100);
                            return;
                        }
                    }
                    Log.d(TAG, "Stopping timer - not playing or not ready");
                    stopCurrentTimeUpdates();
                } catch (Exception e) {
                    Log.e(TAG, "Error getting current time", e);
                    stopCurrentTimeUpdates();
                }
            }
        };
        currentTimeHandler.post(currentTimeRunnable);
    }

    @Override
    void stopCurrentTimeUpdates() {
        Log.d(TAG, "Stopping timer updates in RemoteAudioAsset");
        if (currentTimeHandler != null) {
            currentTimeHandler.removeCallbacks(currentTimeRunnable);
            currentTimeHandler = null;
        }
    }
}

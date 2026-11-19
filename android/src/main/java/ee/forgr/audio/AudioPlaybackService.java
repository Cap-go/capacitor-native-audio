package ee.forgr.audio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Media foreground service that keeps playback eligible to continue while the app is backgrounded.
 * Started only when {@code backgroundPlayback} is enabled and the app declared the foreground service permissions.
 */
public class AudioPlaybackService extends Service {

    public static final String TAG = "AudioPlaybackService";
    public static final int NOTIFICATION_ID = 1001;
    public static final String CHANNEL_ID = "native_audio_channel";
    private static final long WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 60 * 1000L;

    private static volatile AudioPlaybackService instance;
    private static volatile Notification pendingNotification;
    private static volatile boolean pendingPlaying;

    private PowerManager.WakeLock wakeLock;

    public static boolean canRun(Context context) {
        if (context == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 34) {
            return (
                hasPermission(context, android.Manifest.permission.FOREGROUND_SERVICE) &&
                hasPermission(context, android.Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK)
            );
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return hasPermission(context, android.Manifest.permission.FOREGROUND_SERVICE);
        }
        return true;
    }

    public static boolean startOrUpdate(Context context, Notification notification, boolean playing) {
        if (context == null || notification == null) {
            return false;
        }
        pendingNotification = notification;
        pendingPlaying = playing;
        AudioPlaybackService running = instance;
        if (running != null) {
            running.promote(notification, playing);
            return true;
        }
        try {
            ContextCompat.startForegroundService(context, new Intent(context, AudioPlaybackService.class));
            return true;
        } catch (RuntimeException ex) {
            Log.e(TAG, "Could not start the media foreground service", ex);
            return false;
        }
    }

    public static void stop(Context context) {
        pendingNotification = null;
        pendingPlaying = false;
        AudioPlaybackService running = instance;
        if (running != null) {
            running.stopPlayback();
            return;
        }
        if (context != null) {
            context.stopService(new Intent(context, AudioPlaybackService.class));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        ensureChannel();
        Notification notification = pendingNotification;
        if (notification == null) {
            stopWithoutPlayback();
            return START_NOT_STICKY;
        }
        try {
            promoteSync(notification, pendingPlaying);
        } catch (RuntimeException ex) {
            Log.e(TAG, "startForeground failed", ex);
            stopWithoutPlayback();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void promote(Notification notification, boolean playing) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            promoteSync(notification, playing);
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> promoteSync(notification, playing));
    }

    private void promoteSync(Notification notification, boolean playing) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        if (playing) {
            acquireWakeLock();
        } else {
            releaseWakeLock();
        }
    }

    private void stopWithoutPlayback() {
        try {
            Notification placeholder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, placeholder, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, placeholder);
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "Could not satisfy startForeground before stopping", ex);
        }
        releaseWakeLock();
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (RuntimeException ex) {
            Log.w(TAG, "stopForeground failed", ex);
        }
        stopSelf();
    }

    private void stopPlayback() {
        Runnable task = () -> {
            releaseWakeLock();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
            return;
        }
        new Handler(Looper.getMainLooper()).post(task);
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Audio Playback", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows currently playing audio");
        notificationManager.createNotificationChannel(channel);
    }

    private void acquireWakeLock() {
        if (checkSelfPermission(android.Manifest.permission.WAKE_LOCK) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            if (wakeLock == null) {
                PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
                if (powerManager == null) {
                    return;
                }
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nativeaudio:playback");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "Wake lock not acquired", ex);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private static boolean hasPermission(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}

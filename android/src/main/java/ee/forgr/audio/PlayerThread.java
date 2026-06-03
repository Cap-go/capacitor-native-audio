package ee.forgr.audio;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class PlayerThread {

    private PlayerThread() {}

    static void run(PlayerRunnable runnable) throws Exception {
        call(() -> {
            runnable.run();
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    static <T> T call(PlayerCallable<T> callable) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return callable.call();
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final Object[] result = new Object[1];
        final Exception[] error = new Exception[1];

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                result[0] = callable.call();
            } catch (Exception e) {
                error[0] = e;
            } finally {
                latch.countDown();
            }
        });

        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new Exception("Timed out waiting for ExoPlayer operation on main thread");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exception("Interrupted waiting for ExoPlayer operation on main thread", e);
        }

        if (error[0] != null) {
            throw error[0];
        }

        return (T) result[0];
    }

    interface PlayerRunnable {
        void run() throws Exception;
    }

    interface PlayerCallable<T> {
        T call() throws Exception;
    }
}

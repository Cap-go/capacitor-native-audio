package ee.forgr.audio;

final class MediaControlOptions {

    static final double DEFAULT_SEEK_SECONDS = 15d;

    private MediaControlOptions() {}

    static double normalizeSeekSeconds(Double seconds) {
        if (seconds == null || seconds.isNaN() || seconds <= 0d) {
            return DEFAULT_SEEK_SECONDS;
        }
        return seconds;
    }

    static String seekActionTitle(String verb, double seconds) {
        return verb + " " + formatSeconds(seconds) + " seconds";
    }

    static String formatSeconds(double seconds) {
        double rounded = Math.rint(seconds);
        if (Math.abs(seconds - rounded) < 0.001d) {
            return Long.toString((long) rounded);
        }
        return Double.toString(seconds);
    }

    /**
     * Compact action indexes.
     * Without a queue the notification is [rewind, play, forward].
     * With a queue it is [previous, rewind, play, forward, next].
     */
    static int[] compactActionIndices(boolean includeQueueTransport) {
        if (includeQueueTransport) {
            return new int[] { 0, 2, 4 };
        }
        return new int[] { 0, 1, 2 };
    }

    static String normalizeDrawableName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim();
        if (name.isEmpty() || name.contains("..") || name.indexOf('\\') >= 0) {
            return null;
        }
        if (name.startsWith("@drawable/")) {
            name = name.substring("@drawable/".length());
        }
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        if (!name.matches("[a-z0-9_]+")) {
            return null;
        }
        return name;
    }
}

package ee.forgr.audio;

final class QueuePlayback {

    static final String REPEAT_NONE = "none";
    static final String REPEAT_ONE = "one";
    static final String REPEAT_ALL = "all";

    private QueuePlayback() {}

    static String normalizeRepeatMode(String mode) {
        if (REPEAT_ONE.equals(mode) || REPEAT_ALL.equals(mode)) {
            return mode;
        }
        return REPEAT_NONE;
    }

    static int indexAfterStep(int index, int count, int delta, String mode) {
        if (count <= 0) {
            return -1;
        }
        int next = index + delta;
        if (next >= 0 && next < count) {
            return next;
        }
        if (!REPEAT_ALL.equals(mode)) {
            return -1;
        }
        return next < 0 ? count - 1 : 0;
    }

    static int indexAfterComplete(int index, int count, String mode) {
        if (count <= 0 || index < 0 || index >= count || REPEAT_NONE.equals(normalizeRepeatMode(mode))) {
            return -1;
        }
        if (REPEAT_ONE.equals(mode)) {
            return index;
        }
        return indexAfterStep(index, count, 1, mode);
    }
}

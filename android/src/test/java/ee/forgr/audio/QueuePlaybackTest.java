package ee.forgr.audio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class QueuePlaybackTest {

    @Test
    public void indexAfterStep_wrapsOnlyForRepeatAll() {
        assertEquals(2, QueuePlayback.indexAfterStep(1, 3, 1, QueuePlayback.REPEAT_NONE));
        assertEquals(-1, QueuePlayback.indexAfterStep(2, 3, 1, QueuePlayback.REPEAT_NONE));
        assertEquals(-1, QueuePlayback.indexAfterStep(0, 3, -1, QueuePlayback.REPEAT_ONE));
        assertEquals(0, QueuePlayback.indexAfterStep(2, 3, 1, QueuePlayback.REPEAT_ALL));
        assertEquals(2, QueuePlayback.indexAfterStep(0, 3, -1, QueuePlayback.REPEAT_ALL));
    }

    @Test
    public void indexAfterComplete_repeatsCurrentOrQueue() {
        assertEquals(-1, QueuePlayback.indexAfterComplete(1, 3, QueuePlayback.REPEAT_NONE));
        assertEquals(1, QueuePlayback.indexAfterComplete(1, 3, QueuePlayback.REPEAT_ONE));
        assertEquals(0, QueuePlayback.indexAfterComplete(2, 3, QueuePlayback.REPEAT_ALL));
    }
}

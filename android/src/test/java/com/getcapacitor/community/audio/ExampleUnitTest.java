package ee.forgr.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.lang.reflect.Method;
import org.junit.Test;

public class ExampleUnitTest {

    private static final String IMAGE_DECODER_CLASS_PREFIX = "android.graphics.ImageDecoder";

    @Test
    public void nativeAudio_declaredMethods_haveNoImageDecoderTypes() {
        for (Method method : NativeAudio.class.getDeclaredMethods()) {
            assertNoImageDecoderType(method.getReturnType(), method.getName() + " return type");
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertNoImageDecoderType(parameterType, method.getName() + " parameter");
            }
        }
    }

    private static void assertNoImageDecoderType(Class<?> type, String context) {
        while (type.isArray()) {
            type = type.getComponentType();
        }
        assertFalse(
            context + " must not reference ImageDecoder (Capacitor reflects plugin methods at startup on API < 28)",
            type.getName().startsWith(IMAGE_DECODER_CLASS_PREFIX)
        );
    }

    @Test
    public void clampSeekPositionSeconds_respectsLowerAndUpperBounds() {
        assertEquals(0.0, NativeAudio.clampSeekPositionSeconds(5.0, 120.0, -15.0), 0.0001);
        assertEquals(120.0, NativeAudio.clampSeekPositionSeconds(110.0, 120.0, 15.0), 0.0001);
    }

    @Test
    public void clampSeekPositionSeconds_allowsForwardSeekWithoutKnownDuration() {
        assertEquals(35.0, NativeAudio.clampSeekPositionSeconds(20.0, 0.0, 15.0), 0.0001);
        assertEquals(0.0, NativeAudio.clampSeekPositionSeconds(3.0, 0.0, -15.0), 0.0001);
    }
}

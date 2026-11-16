package dev.sertas.app;

import dev.onvoid.webrtc.media.video.VideoDevice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Какую камеру включать: выбранную, сохранённую по имени или первую. */
class CameraChoiceTest {

    private static final VideoDevice BUILT_IN = device("FaceTime HD Camera", "builtin");
    private static final VideoDevice USB = device("Logitech C920", "usb-1");
    private static final List<VideoDevice> BOTH = List.of(BUILT_IN, USB);

    private static VideoDevice device(String name, String id) {
        return new VideoDevice(name, id) {};
    }

    @Test
    void selectedCameraWins() {
        assertSame(USB, CallController.pickCamera(BOTH, USB, "FaceTime HD Camera"));
    }

    @Test
    void savedNameUsedWhenNothingSelectedOrSelectedIsGone() {
        assertSame(USB, CallController.pickCamera(BOTH, null, "Logitech C920"));
        VideoDevice unplugged = device("Old webcam", "usb-9");
        assertSame(USB, CallController.pickCamera(BOTH, unplugged, "Logitech C920"));
    }

    @Test
    void fallsBackToFirstAndToNullWithoutCameras() {
        assertSame(BUILT_IN, CallController.pickCamera(BOTH, null, "Old webcam"));
        assertSame(BUILT_IN, CallController.pickCamera(BOTH, null, null));
        assertNull(CallController.pickCamera(List.of(), USB, "Logitech C920"));
    }
}

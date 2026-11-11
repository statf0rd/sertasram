package dev.sertas.engine;

import dev.onvoid.webrtc.media.video.VideoCaptureCapability;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Выбор режима камеры - без устройства, на списках режимов. */
class CameraSourceTest {

    private static VideoCaptureCapability cap(int w, int h, int fps) {
        return new VideoCaptureCapability(w, h, fps);
    }

    private static VideoCaptureCapability pick(VideoCaptureCapability... caps) {
        return CameraSource.pickCapability(List.of(caps), 1280, 720, 30);
    }

    @Test
    void prefersExactTargetMode() {
        assertEquals(cap(1280, 720, 30), pick(
                cap(640, 480, 30), cap(1280, 720, 15), cap(1280, 720, 30),
                cap(1280, 720, 60), cap(1920, 1080, 30)));
    }

    @Test
    void keepsFrameRateOverResolution() {
        // 720p только на 15 FPS - лучше 480p, но плавно
        assertEquals(cap(640, 480, 30), pick(cap(640, 480, 30), cap(1280, 720, 15)));
    }

    @Test
    void takesLargestFittingModeWhenNothingHoldsFps() {
        assertEquals(cap(1280, 720, 24), pick(cap(640, 480, 15), cap(1280, 720, 24)));
    }

    @Test
    void fallsBackToSmallestWhenAllModesExceedTarget() {
        assertEquals(cap(1920, 1080, 30), pick(cap(3840, 2160, 30), cap(1920, 1080, 30)));
    }

    @Test
    void noModesMeansCameraDefault() {
        assertNull(CameraSource.pickCapability(List.of(), 1280, 720, 30));
        assertNull(CameraSource.pickCapability(null, 1280, 720, 30));
    }
}

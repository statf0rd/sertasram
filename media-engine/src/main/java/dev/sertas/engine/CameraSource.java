package dev.sertas.engine;

import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.video.VideoCaptureCapability;
import dev.onvoid.webrtc.media.video.VideoDevice;
import dev.onvoid.webrtc.media.video.VideoDeviceSource;

import java.util.Comparator;
import java.util.List;

/**
 * Захват с камеры через встроенный {@link VideoDeviceSource}. Как и
 * {@link ScreenCaptureSource}, создаётся пустым при входе в звонок: трек камеры
 * согласуется отдельной {@code m=video} сразу, а захват включается позже
 * {@link #start} без повторной переговорки.
 *
 * <p>Реальный захват требует физической камеры и разрешения (TCC на macOS,
 * {@code NSCameraUsageDescription} в Info.plist бандла) - проверяется вручную.
 */
public final class CameraSource {

    /** Id трека камеры: по нему приёмник отличает камеру от экрана (см. SdpMsid). */
    public static final String LABEL = "camera";

    /** Целевой режим: 720p30 хватает для лица и не отнимает полосу у демонстрации. */
    static final int TARGET_WIDTH = 1280;
    static final int TARGET_HEIGHT = 720;
    static final int TARGET_FPS = 30;

    private final VideoDeviceSource source = new VideoDeviceSource();
    private volatile boolean running;

    /** Доступные камеры. */
    public static List<VideoDevice> cameras() {
        return MediaDevices.getVideoCaptureDevices();
    }

    /** Поддерживаемые режимы (разрешение/FPS) камеры. */
    public static List<VideoCaptureCapability> capabilities(VideoDevice device) {
        return MediaDevices.getVideoCaptureCapabilities(device);
    }

    /** Источник для создания видео-трека (создаётся при входе в звонок). */
    public VideoDeviceSource source() {
        return source;
    }

    /**
     * Начать захват в режиме, ближайшем к 720p30. Тяжёлая нативная работа (на
     * macOS ещё и запрос разрешения) - вызывать не на FX-потоке.
     */
    public void start(VideoDevice device) {
        start(device, pickCapability(capabilities(device), TARGET_WIDTH, TARGET_HEIGHT, TARGET_FPS));
    }

    /** @param capability режим захвата; {@code null} - режим камеры по умолчанию */
    public synchronized void start(VideoDevice device, VideoCaptureCapability capability) {
        if (running) {
            stop();
        }
        source.setVideoCaptureDevice(device);
        if (capability != null) {
            source.setVideoCaptureCapability(capability);
        }
        source.start();
        running = true;
    }

    /** Остановить захват (камера освобождается, индикатор гаснет). */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        source.stop();
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized void dispose() {
        stop();
        source.dispose();
    }

    /**
     * Режим камеры для захвата: среди режимов, не превышающих {@code maxWidth x maxHeight},
     * сначала те, что держат {@code fps}, затем самый крупный, затем FPS ближе к
     * целевому. Если все режимы крупнее цели - самый мелкий.
     *
     * @return {@code null}, если список пуст (камера не сообщила режимы)
     */
    static VideoCaptureCapability pickCapability(List<VideoCaptureCapability> caps,
                                                 int maxWidth, int maxHeight, int fps) {
        if (caps == null || caps.isEmpty()) {
            return null;
        }
        List<VideoCaptureCapability> fitting = caps.stream()
                .filter(c -> c.width <= maxWidth && c.height <= maxHeight)
                .toList();
        if (fitting.isEmpty()) {
            return caps.stream()
                    .min(Comparator.comparingLong(CameraSource::area)
                            .thenComparingInt(c -> Math.abs(c.frameRate - fps)))
                    .orElseThrow();
        }
        return fitting.stream()
                .max(Comparator.comparing((VideoCaptureCapability c) -> c.frameRate >= fps)
                        .thenComparingLong(CameraSource::area)
                        .thenComparingInt(c -> -Math.abs(c.frameRate - fps)))
                .orElseThrow();
    }

    private static long area(VideoCaptureCapability c) {
        return (long) c.width * c.height;
    }
}

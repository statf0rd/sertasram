package dev.sertas.engine;

import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;

/**
 * Нативный захват экрана (видео) на macOS через ScreenCaptureKit (`.dylib` + JNI).
 * Pull-модель: Java-поток тянет {@link #nativeRead} последний NV12-кадр, конвертит
 * NV12 → I420 и пушит в {@link CustomVideoSource}. Высокий FPS вместо медленного
 * встроенного DesktopCapturer.
 *
 * <p>Та же dylib, что и у {@link MacSystemAudioCapture} (свойство
 * {@code sertas.audio.dylib}); грузится один раз на оба класса.
 */
public final class MacScreenVideoCapture implements ScreenVideoCapture {

    private static final boolean LOADED = load();

    private volatile boolean running;
    private volatile long framesPushed;
    private Thread reader;

    private static boolean load() {
        String path = System.getProperty("sertas.audio.dylib");
        if (path == null || path.isBlank()) {
            return false;
        }
        try {
            System.load(path);
            return true;
        } catch (UnsatisfiedLinkError e) {
            // Уже загружена (например, MacSystemAudioCapture первым) — это успех.
            if (e.getMessage() != null && e.getMessage().contains("already loaded")) {
                return true;
            }
            System.err.println("sertasram: не удалось загрузить " + path + ": " + e.getMessage());
            return false;
        }
    }

    /** Доступен ли нативный захват экрана (dylib загружен). */
    public static boolean isAvailable() {
        return LOADED;
    }

    /** Сколько кадров протолкнуто в источник (диагностика «идёт ли захват»). */
    @Override
    public long framesPushed() {
        return framesPushed;
    }

    /** Начать захват экрана {@code width}×{@code height} @ {@code fps} в {@code sink}. */
    @Override
    public synchronized void start(CustomVideoSource sink, int width, int height, int fps) {
        if (running) {
            return;
        }
        int r = nativeStart(width, height, fps);
        System.err.println("[screencap] native nativeStart=" + r + " (" + width + "x" + height + "@" + fps + ")");
        if (r != 1) {
            throw new IllegalStateException(
                    "ScreenCaptureKit: не удалось начать захват экрана (нет разрешения Screen Recording?)");
        }
        running = true;
        reader = new Thread(() -> readLoop(sink, width, height, fps), "mac-screen-video");
        reader.setDaemon(true);
        reader.start();
    }

    @Override
    public synchronized void stop() {
        running = false;
        Thread r = reader;
        if (r != null) {
            r.interrupt();
            reader = null;
        }
        nativeStop();
    }

    private void readLoop(CustomVideoSource sink, int maxW, int maxH, int fps) {
        byte[] nv12 = new byte[maxW * maxH * 3 / 2];
        int[] dims = new int[2];
        long sleepMs = Math.max(1, 1000L / Math.max(1, fps) / 2); // опрашиваем чаще кадра
        while (running) {
            int n = nativeRead(nv12, nv12.length, dims);
            if (n <= 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            int fw = dims[0];
            int fh = dims[1];
            if (fw <= 0 || fh <= 0) {
                continue;
            }
            try {
                NativeI420Buffer i420 = NativeI420Buffer.allocate(fw, fh);
                VideoBufferConverter.convertToI420(nv12, i420, FourCC.NV12);
                VideoFrame frame = new VideoFrame(i420, System.nanoTime());
                sink.pushFrame(frame);
                frame.release();
                if (framesPushed++ == 0 || framesPushed % 150 == 0) {
                    System.err.println("[screencap] native кадр #" + framesPushed + " " + fw + "x" + fh);
                }
            } catch (Exception ignored) {
                // битый кадр — пропускаем
            }
        }
    }

    private static native int nativeStart(int width, int height, int fps);

    private static native int nativeRead(byte[] nv12, int maxBytes, int[] dims);

    private static native void nativeStop();
}

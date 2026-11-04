package dev.sertas.engine;

import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;

/**
 * Нативный захват экрана (видео) на Windows через DXGI Desktop Duplication (`.dll` + JNI).
 * Pull-модель: Java-поток тянет {@link #nativeRead} последний BGRA-кадр, конвертит
 * BGRA → I420 и пушит в {@link CustomVideoSource}. Высокий FPS вместо медленного
 * встроенного DesktopCapturer.
 *
 * <p>DLL грузится из пути в системном свойстве {@code sertas.capture.dll} (в бандле —
 * выставляется лаунчером). FourCC по умолчанию {@code ARGB} (= BGRA в памяти у
 * libyuv); если цвета неверны — переопределить через {@code -Dsertas.capture.fourcc=BGRA}.
 */
public final class WinScreenVideoCapture implements ScreenVideoCapture {

    private static final boolean LOADED = load();
    private static final FourCC FOURCC = resolveFourCc();

    private volatile boolean running;
    private volatile long framesPushed;
    private Thread reader;

    private static boolean load() {
        String path = System.getProperty("sertas.capture.dll");
        if (path == null || path.isBlank()) {
            return false;
        }
        try {
            System.load(path);
            return true;
        } catch (UnsatisfiedLinkError e) {
            System.err.println("sertasram: не удалось загрузить " + path + ": " + e.getMessage());
            return false;
        }
    }

    private static FourCC resolveFourCc() {
        return "BGRA".equalsIgnoreCase(System.getProperty("sertas.capture.fourcc", "ARGB"))
                ? FourCC.BGRA
                : FourCC.ARGB;
    }

    /** Доступен ли нативный захват экрана (dll загружена). */
    public static boolean isAvailable() {
        return LOADED;
    }

    @Override
    public long framesPushed() {
        return framesPushed;
    }

    @Override
    public synchronized void start(CustomVideoSource sink, int width, int height, int fps) {
        if (running) {
            return;
        }
        if (nativeStart(width, height, fps) != 1) {
            throw new IllegalStateException("DXGI: не удалось начать захват экрана");
        }
        running = true;
        reader = new Thread(() -> readLoop(sink, width, height, fps), "win-screen-video");
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
        byte[] bgra = new byte[maxW * maxH * 4];
        int[] dims = new int[2];
        long sleepMs = Math.max(1, 1000L / Math.max(1, fps) / 2);
        while (running) {
            int n = nativeRead(bgra, bgra.length, dims);
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
                VideoBufferConverter.convertToI420(bgra, i420, FOURCC);
                VideoFrame frame = new VideoFrame(i420, System.nanoTime());
                sink.pushFrame(frame);
                frame.release();
                framesPushed++;
            } catch (Exception ignored) {
                // битый кадр — пропускаем
            }
        }
    }

    private static native int nativeStart(int width, int height, int fps);

    private static native int nativeRead(byte[] bgra, int maxBytes, int[] dims);

    private static native void nativeStop();
}

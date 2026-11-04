package dev.sertas.engine;

import dev.sertas.engine.SystemAudioProvider.PcmSink;

import java.util.Arrays;

/**
 * Нативный захват системного звука на macOS через ScreenCaptureKit (`.dylib` + JNI).
 * Pull-модель: Java-поток тянет {@link #nativeRead}, нативная сторона копит
 * SCStream-колбэки в кольцевой буфер. Никаких апколлов Swift→JVM.
 *
 * <p>Библиотека грузится из пути в системном свойстве {@code sertas.audio.dylib}
 * (в бандле — выставляется лаунчером). Свойство не задано / dylib не загрузился →
 * {@link #isAvailable()} == false, провайдер не используется.
 *
 * <p>Сборка dylib: {@code scripts/build-macos-audio-dylib.sh}.
 */
public final class MacSystemAudioCapture implements SystemAudioProvider {

    private static final boolean LOADED = load();
    private static final int SAMPLE_RATE = 48_000;
    private static final int MAX_FRAMES = SAMPLE_RATE / 100; // 10мс = 480

    private volatile boolean running;
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
            System.err.println("sertasram: не удалось загрузить " + path + ": " + e.getMessage());
            return false;
        }
    }

    /** Доступен ли нативный захват (dylib загружен). */
    public static boolean isAvailable() {
        return LOADED;
    }

    @Override
    public synchronized void start(PcmSink sink) {
        if (running) {
            return;
        }
        int r = nativeStart();
        System.err.println("[demo] ScreenCaptureKit nativeStart=" + r);
        if (r != 1) {
            throw new IllegalStateException(
                    "ScreenCaptureKit: не удалось начать захват (нет разрешения Screen Recording?)");
        }
        running = true;
        reader = new Thread(() -> readLoop(sink), "mac-system-audio");
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

    private void readLoop(PcmSink sink) {
        float[] left = new float[MAX_FRAMES];
        float[] right = new float[MAX_FRAMES];
        while (running) {
            int n = nativeRead(left, right, MAX_FRAMES);
            if (n <= 0) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            // Reframer копирует вход синхронно → переиспользование массивов безопасно.
            if (n == MAX_FRAMES) {
                sink.onPcm(left, right, SAMPLE_RATE);
            } else {
                sink.onPcm(Arrays.copyOf(left, n), Arrays.copyOf(right, n), SAMPLE_RATE);
            }
        }
    }

    private static native int nativeStart();

    private static native int nativeRead(float[] left, float[] right, int maxFrames);

    private static native void nativeStop();
}

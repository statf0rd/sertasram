package dev.sertas.engine;

import dev.sertas.engine.SystemAudioProvider.PcmSink;

import java.util.Arrays;

/**
 * Нативный захват системного звука на Windows через WASAPI loopback (`.dll` + JNI).
 * Pull-модель, аналогично {@link MacSystemAudioCapture}: фоновый поток тянет
 * {@link #nativeRead}, нативная сторона копит mix render-устройства в кольцевой буфер.
 *
 * <p>Библиотека грузится из пути в системном свойстве {@code sertas.audio.dll}
 * (в бандле — выставляется лаунчером). Свойство не задано / dll не загрузилась →
 * {@link #isAvailable()} == false. Частота — от устройства ({@link #nativeSampleRate}),
 * {@code SystemAudioTrack} ре-фреймит под неё.
 *
 * <p>Сборка dll: {@code scripts/build-windows-audio-dll.bat} (MSVC, на Windows).
 */
public final class WinSystemAudioCapture implements SystemAudioProvider {

    private static final boolean LOADED = load();
    private static final int MAX_FRAMES = 4800; // до 100мс @48к за чтение

    private volatile boolean running;
    private Thread reader;
    private int sampleRate = 48_000;

    private static boolean load() {
        String path = System.getProperty("sertas.audio.dll");
        System.err.println("[demo] WASAPI dll prop=" + path);
        if (path == null || path.isBlank()) {
            System.err.println("[demo] WASAPI dll НЕ задан -Dsertas.audio.dll → нативный захват недоступен");
            return false;
        }
        try {
            // Путь в бандле относительный (lib\...); лаунчер делает cd в корень — резолвим в абсолютный.
            String abs = new java.io.File(path).getAbsolutePath();
            System.load(abs);
            System.err.println("[demo] WASAPI dll loaded=" + abs);
            return true;
        } catch (UnsatisfiedLinkError e) {
            System.err.println("sertasram: не удалось загрузить " + path + ": " + e.getMessage());
            return false;
        }
    }

    /** Доступен ли нативный захват (dll загружена). */
    public static boolean isAvailable() {
        return LOADED;
    }

    @Override
    public synchronized void start(PcmSink sink) {
        if (running) {
            return;
        }
        int r = nativeStart();
        System.err.println("[demo] WASAPI nativeStart=" + r);
        if (r != 1) {
            throw new IllegalStateException("WASAPI loopback: не удалось начать захват");
        }
        sampleRate = nativeSampleRate();
        System.err.println("[demo] WASAPI sampleRate=" + sampleRate);
        running = true;
        reader = new Thread(() -> readLoop(sink), "win-system-audio");
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
            if (n == MAX_FRAMES) {
                sink.onPcm(left, right, sampleRate);
            } else {
                sink.onPcm(Arrays.copyOf(left, n), Arrays.copyOf(right, n), sampleRate);
            }
        }
    }

    private static native int nativeStart();

    private static native int nativeSampleRate();

    private static native int nativeRead(float[] left, float[] right, int maxFrames);

    private static native void nativeStop();
}

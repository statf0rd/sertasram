package dev.sertas.app.ui;

import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import javafx.animation.AnimationTimer;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Плитка видео (удалённого или своего превью). Кадры приходят на нативном
 * WebRTC-потоке: там их конвертируем I420 → BGRA в общий direct-буфер; на
 * FX-потоке через {@link AnimationTimer} обновляем {@link PixelBuffer} (не чаще
 * refresh). Пока кадров нет (показ или камера выключены), {@link #activeProperty()}
 * равно {@code false} и раскладка ({@link VideoGrid}) плитку не показывает.
 */
public final class VideoTile {

    private static final double DEFAULT_ASPECT = 16.0 / 9;

    private static final long HIDE_AFTER_NS = 2_000_000_000L;
    private static final long STATS_WINDOW_NS = 5_000_000_000L;
    /** Каталог для периодических PNG-дампов принятых кадров (-Dsertas.dumpframes=DIR). */
    private static final String DUMP_DIR = System.getProperty("sertas.dumpframes");

    private final VideoTrack track;
    private final VideoTrackSink sink = this::onFrame;
    private final ImageView view = new ImageView();
    private final ReentrantLock lock = new ReentrantLock();
    private final AnimationTimer timer;

    private ByteBuffer buffer;
    private int width;
    private int height;
    private boolean dirty;
    private boolean sizeChanged;
    private PixelBuffer<ByteBuffer> pixelBuffer;
    private volatile long lastFrameNs;

    // Только FX-поток: есть ли свежие кадры и пропорции последнего кадра.
    private final ReadOnlyBooleanWrapper active = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyDoubleWrapper aspect = new ReadOnlyDoubleWrapper(DEFAULT_ASPECT);

    // Статистика приёма ([recv]) — читается/пишется только на нативном потоке кадров.
    private long statWindowStartNs;
    private int statFrames;

    public VideoTile(VideoTrack track) {
        this(track, false);
    }

    /** @param mirror отразить по горизонтали (своё превью камеры, как в зеркале) */
    public VideoTile(VideoTrack track, boolean mirror) {
        this.track = track;
        view.setPreserveRatio(true);
        view.setSmooth(true);
        // Ширину задаёт раскладка (VideoGrid.layoutChildren), высота - по пропорциям.
        view.setFitWidth(640);
        if (mirror) {
            view.setScaleX(-1);
        }
        track.addSink(sink);
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                render();
            }
        };
        timer.start();
    }

    /** Узел для размещения в сцене. */
    public ImageView node() {
        return view;
    }

    /** Есть свежие кадры (последний - не старше {@link #HIDE_AFTER_NS}). FX-поток. */
    public ReadOnlyBooleanProperty activeProperty() {
        return active.getReadOnlyProperty();
    }

    /** Ширина / высота последнего кадра (16:9, пока кадров не было). FX-поток. */
    public ReadOnlyDoubleProperty aspectProperty() {
        return aspect.getReadOnlyProperty();
    }

    /**
     * Сразу скрыть плитку (источник остановлен), не дожидаясь паузы в
     * {@link #HIDE_AFTER_NS}: иначе ещё 2 секунды висел бы застывший кадр.
     */
    public void clear() {
        lastFrameNs = 0;
    }

    /** Нативный поток: конвертация кадра в BGRA. Package-private — вызывается тестом напрямую. */
    void onFrame(VideoFrame frame) {
        try {
            int fw = frame.buffer.getWidth();
            int fh = frame.buffer.getHeight();
            byte[] dump = null;
            lock.lock();
            try {
                if (buffer == null || fw != width || fh != height) {
                    buffer = ByteBuffer.allocateDirect(fw * fh * 4);
                    width = fw;
                    height = fh;
                    sizeChanged = true;
                }
                // libyuv FourCC.ARGB = байты B,G,R,A в памяти = JavaFX BYTE_BGRA.
                VideoBufferConverter.convertFromI420(frame.buffer, buffer, FourCC.ARGB);
                dirty = true;
                dump = statsTick(fw, fh) ? copyBgra() : null;
            } finally {
                lock.unlock();
            }
            lastFrameNs = System.nanoTime();
            if (dump != null) {
                dumpPng(dump, fw, fh);
            }
        } catch (Exception ignored) {
            // битый/неподдержанный кадр — пропускаем
        } finally {
            // ОБЯЗАТЕЛЬНО: JNI-сторона webrtc-java копирует буфер и делает AddRef
            // перед вызовом sink'а, так что владелец копии — мы. Без release
            // каждый принятый кадр остаётся в нативной куче (1080p ≈ 3МБ, при
            // 30 кадрах/с ≈ 90МБ/с — за минуты десятки гигабайт и своп; Java-куча
            // при этом чистая, поэтому OutOfMemoryError не возникает).
            try {
                frame.release();
            } catch (RuntimeException ignored) {
                // нативный кадр уже освобождён — считать нечего
            }
        }
    }

    /** Раз в {@link #STATS_WINDOW_NS} логирует FPS/размер приёма; true — пора дампить кадр. */
    private boolean statsTick(int fw, int fh) {
        long now = System.nanoTime();
        statFrames++;
        if (statWindowStartNs == 0) {
            statWindowStartNs = now;
            return false;
        }
        long elapsed = now - statWindowStartNs;
        if (elapsed < STATS_WINDOW_NS) {
            return false;
        }
        double fps = statFrames * 1e9 / elapsed;
        System.err.println(String.format("[recv] video %dx%d fps=%.1f (%d кадров за %.1fс)",
                fw, fh, fps, statFrames, elapsed / 1e9));
        statFrames = 0;
        statWindowStartNs = now;
        return DUMP_DIR != null && !DUMP_DIR.isBlank();
    }

    /** Снять копию BGRA-буфера под уже взятым локом (для PNG-дампа вне лока). */
    private byte[] copyBgra() {
        byte[] copy = new byte[width * height * 4];
        buffer.duplicate().position(0).get(copy);
        return copy;
    }

    /** Сохранить принятый кадр как PNG — объективная проверка качества картинки. */
    private static void dumpPng(byte[] bgra, int w, int h) {
        try {
            int[] px = new int[w * h];
            for (int i = 0, j = 0; j < px.length; i += 4, j++) {
                px[j] = (bgra[i + 3] & 0xff) << 24 | (bgra[i + 2] & 0xff) << 16
                        | (bgra[i + 1] & 0xff) << 8 | (bgra[i] & 0xff);
            }
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, w, h, px, 0, w);
            Path dir = Path.of(DUMP_DIR);
            Files.createDirectories(dir);
            Path file = dir.resolve("recv-" + System.currentTimeMillis() + ".png");
            ImageIO.write(img, "png", file.toFile());
            System.err.println("[recv] дамп кадра: " + file);
        } catch (Exception e) {
            System.err.println("[recv] дамп кадра не удался: " + e);
        }
    }

    /** FX-поток: применяем последний кадр и управляем видимостью. */
    private void render() {
        lock.lock();
        try {
            if (sizeChanged && buffer != null) {
                pixelBuffer = new PixelBuffer<>(width, height, buffer, PixelFormat.getByteBgraPreInstance());
                view.setImage(new WritableImage(pixelBuffer));
                aspect.set((double) width / height);
                sizeChanged = false;
                dirty = false;
            } else if (dirty && pixelBuffer != null) {
                pixelBuffer.updateBuffer(b -> null);
                dirty = false;
            }
        } finally {
            lock.unlock();
        }
        long last = lastFrameNs;
        active.set(last != 0 && (System.nanoTime() - last) < HIDE_AFTER_NS);
    }

    public void dispose() {
        timer.stop();
        int id = System.identityHashCode(track);
        // Диагностика use-after-free: если в логе есть «removeSink CALLING» без
        // парной «DONE» как последняя строка перед падением — нативный трек уже
        // освобождён (pc.close/factory.dispose) и removeSink бьёт по висячему
        // указателю. Намеренно НЕ читаем track.getState() — оно само разыменует
        // тот же handle и крашнет процесс раньше времени.
        System.err.println("[life] VideoTile.dispose track=" + id
                + " thread=" + Thread.currentThread().getName() + " t=" + System.nanoTime());
        try {
            System.err.println("[life] removeSink CALLING track=" + id);
            track.removeSink(sink);
            System.err.println("[life] removeSink DONE track=" + id);
        } catch (RuntimeException ignored) {
            // трек уже освобождён (нативный SIGSEGV этим НЕ ловится — см. лог выше)
        }
    }
}

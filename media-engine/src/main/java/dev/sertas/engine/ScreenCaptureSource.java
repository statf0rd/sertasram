package dev.sertas.engine;

import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.VideoDesktopSource;
import dev.onvoid.webrtc.media.video.VideoTrackSource;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;

import java.util.List;

/**
 * Демонстрация экрана. По умолчанию — нативный захват ({@link MacScreenVideoCapture},
 * ScreenCaptureKit) для высокого FPS; если dylib недоступен — встроенный
 * {@link VideoDesktopSource} (libwebrtc DesktopCapturer, медленнее).
 *
 * <p>Источник создаётся пустым при входе в звонок — чтобы видео-m-line
 * согласовался сразу, без renegotiation. Захват начинается позже: выбрать экран
 * через {@link #select} и {@link #start}. Системный звук демонстрации — отдельный
 * трек, здесь только видео.
 */
public final class ScreenCaptureSource {

    /** Id трека демонстрации экрана: по нему приёмник отличает экран от камеры. */
    public static final String LABEL = "screen";

    /**
     * Пресеты качества демонстрации: разрешение и FPS захвата, потолок битрейта
     * видео экрана и битрейт Opus для звука демонстрации. Порядок - по росту полосы.
     * Значения стартовые, уточняются замерами (getStats в CSV).
     */
    public enum Quality {
        ECONOMY(1280, 720, 30, 1_500_000, 96_000),    // слабая сеть
        SMOOTH(1280, 720, 60, 4_000_000, 128_000),    // плавность: игры, видео
        BALANCED(1920, 1080, 30, 5_000_000, 192_000), // по умолчанию
        CRISP(2560, 1440, 15, 6_000_000, 256_000);    // чёткость: текст, код, слайды

        /** Пресет по умолчанию; его битрейты совпадают с прежними константами. */
        public static final Quality DEFAULT = BALANCED;

        final int maxWidth;
        final int maxHeight;
        final int fps;
        private final int videoMaxBitrate;
        private final int opusBitrate;

        Quality(int maxWidth, int maxHeight, int fps, int videoMaxBitrate, int opusBitrate) {
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
            this.fps = fps;
            this.videoMaxBitrate = videoMaxBitrate;
            this.opusBitrate = opusBitrate;
        }

        public int maxWidth() {
            return maxWidth;
        }

        public int maxHeight() {
            return maxHeight;
        }

        public int fps() {
            return fps;
        }

        /** Потолок битрейта видео экрана, бит/с. */
        public int videoMaxBitrate() {
            return videoMaxBitrate;
        }

        /** Битрейт Opus звука демонстрации, бит/с. */
        public int opusBitrate() {
            return opusBitrate;
        }
    }

    // Нативный путь (macOS — ScreenCaptureKit, Windows — DXGI): кадры толкаются
    // в custom; иначе захватывает встроенный builtin.
    private final ScreenVideoCapture nativeCap;
    private final boolean useNative;
    private final CustomVideoSource custom;
    private final VideoDesktopSource builtin;

    private Quality quality = Quality.DEFAULT;

    public ScreenCaptureSource() {
        nativeCap = pickNative();
        useNative = nativeCap != null;
        if (useNative) {
            custom = new CustomVideoSource();
            builtin = null;
        } else {
            custom = null;
            builtin = new VideoDesktopSource();
        }
    }

    private static ScreenVideoCapture pickNative() {
        // Принудительно встроенный (стабильный, медленнее) захват, если нативный
        // мудрит: -Dsertas.screencap=builtin.
        if ("builtin".equalsIgnoreCase(System.getProperty("sertas.screencap"))) {
            System.err.println("[screencap] forced builtin (VideoDesktopSource)");
            return null;
        }
        if (MacScreenVideoCapture.isAvailable()) {
            System.err.println("[screencap] native ScreenCaptureKit (macOS)");
            return new MacScreenVideoCapture();
        }
        if (WinScreenVideoCapture.isAvailable()) {
            System.err.println("[screencap] native DXGI (Windows)");
            return new WinScreenVideoCapture();
        }
        System.err.println("[screencap] builtin (нативный недоступен)");
        return null;
    }

    /** Доступные экраны. На macOS требует разрешения Screen Recording (TCC). */
    public static List<DesktopSource> screens() {
        ScreenCapturer capturer = new ScreenCapturer();
        try {
            return capturer.getDesktopSources();
        } finally {
            capturer.dispose();
        }
    }

    /** Выбрать экран и качество. Вызывать до {@link #start}. */
    public void select(long screenId, Quality quality) {
        this.quality = quality;
        if (!useNative) {
            builtin.setSourceId(screenId, false); // false = экран (true было бы окно)
            builtin.setFrameRate(quality.fps);
            builtin.setMaxFrameSize(quality.maxWidth, quality.maxHeight);
        }
        // Нативный захват берёт основной экран; разрешение/FPS применяются в start().
    }

    /** Пресет, выбранный последним {@link #select}. */
    public Quality quality() {
        return quality;
    }

    /** Источник для создания видео-трека (создаётся при входе в звонок). */
    public VideoTrackSource source() {
        return useNative ? custom : builtin;
    }

    /** true — используется нативный (ScreenCaptureKit) захват. */
    public boolean isNative() {
        return useNative;
    }

    public void start() {
        if (useNative) {
            nativeCap.start(custom, quality.maxWidth, quality.maxHeight, quality.fps);
        } else {
            builtin.start();
        }
    }

    public void stop() {
        if (useNative) {
            nativeCap.stop();
        } else {
            builtin.stop();
        }
    }

    public void dispose() {
        if (useNative) {
            custom.dispose();
        } else {
            builtin.dispose();
        }
    }
}

package dev.sertas.app;

import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.video.VideoDevice;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.sertas.app.ui.ParticipantModel;
import dev.sertas.app.ui.VideoGrid;
import dev.sertas.app.ui.VideoTile;
import dev.sertas.engine.CameraSource;
import dev.sertas.engine.MacSystemAudioCapture;
import dev.sertas.engine.MeshCoordinator;
import dev.sertas.engine.MeshListener;
import dev.sertas.engine.RemoteAudioMixer;
import dev.sertas.engine.ScreenAudioConnection;
import dev.sertas.engine.ScreenCaptureSource;
import dev.sertas.engine.ScreenCaptureSource.Quality;
import dev.sertas.engine.StatsRecorder;
import dev.sertas.engine.SystemAudioProvider;
import dev.sertas.engine.SystemAudioTrack;
import dev.sertas.engine.WebRtcEngine;
import dev.sertas.engine.WinSystemAudioCapture;
import dev.sertas.media.OpusSdpMunger;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ObservableStringValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Связывает {@link MeshCoordinator} с JavaFX-UI. Колбэки меша приходят на
 * нативных/WS-потоках и маршалятся на FX-поток через {@link Platform#runLater}.
 */
public final class CallController implements MeshListener {

    /** Ключ плитки своего превью камеры в {@link #videoGrid}. */
    private static final String SELF_CAMERA = "self:" + CameraSource.LABEL;

    private final ObservableList<ParticipantModel> participants = FXCollections.observableArrayList();
    // Плитки "peerId:метка" (экран, камера) и своё превью; только FX-поток.
    private final VideoGrid videoGrid = new VideoGrid();
    private final Map<String, ParticipantModel> byId = new HashMap<>();   // только FX-поток
    private final Set<String> wiredGains = new HashSet<>();               // (peerId:Kind) с навешанным слушателем; FX-поток

    /** Музыкальный профиль Opus для секции трека звука демо. */
    // В сессиях звука демо ровно одна m=audio, поэтому мунжим весь SDP — и offer, и
    // answer: стерео у Opus-энкодера отправителя включается только если stereo=1
    // стоит в ОТВЕТЕ зрителя (answer по метке трека не найти — у recvonly нет msid).
    private static final UnaryOperator<String> SCREEN_AUDIO_MUSIC = OpusSdpMunger::applyMusicProfile;

    private WebRtcEngine engine;
    private MeshCoordinator mesh;
    private AudioTrack mic;
    private ScreenCaptureSource screen;
    private CameraSource camera;
    private StatsRecorder stats; // null - запись статистики выключена (-Dsertas.stats)
    private RemoteAudioMixer audioMixer;
    private ParticipantModel self;

    // Звук демо: отдача — с отдельного движка pushOnly (ADM без потока захвата, см.
    // диагноз гонки в ScreenAudioConnection), приём — на ГЛАВНОМ движке: его реальный
    // ADM сам играет удалённый трек (стерео, тактируется устройством, попадает в
    // AEC-референс микрофона). Сигналинг — поверх control data-channel главного соединения.
    private WebRtcEngine audioSendEngine; // отдача звука демо (pushOnly: ADM без захвата)
    private SystemAudioTrack screenAudio;
    private final Map<String, ScreenAudioConnection> screenAudioConns = new ConcurrentHashMap<>();
    private boolean micMuted = false;
    private volatile boolean sharing = false; // пишется из фонового потока старта показа
    private volatile boolean cameraOn = false; // пишется из фонового потока старта камеры
    private boolean screenAudioOn = false;
    private volatile Consumer<String> errorSink; // получатель ошибок для UI (вызывается в FX-потоке)

    /*
     * Старт/стоп показа и камеры, смена камеры и пресета - тяжёлая нативная
     * работа (TCC, ScreenCaptureKit, открытие устройства), поэтому не на FX-потоке.
     * Один последовательный поток на звонок: быстрые нажатия выполняются по
     * порядку, и «стоп» не обгонит ещё идущий «старт».
     */
    private ExecutorService mediaOps;
    private volatile Quality quality = savedQuality();
    private volatile VideoDevice selectedCamera; // null - первая найденная (или сохранённая по имени)
    private volatile long sharedScreenId;

    public ObservableList<ParticipantModel> participants() {
        return participants;
    }

    /** Раскладка видео: демонстрации, камеры участников и своё превью. */
    public VideoGrid videoPane() {
        return videoGrid;
    }

    /** Куда показывать ошибки (в т.ч. неудачный старт показа); вызывается в FX-потоке. */
    public void setErrorSink(Consumer<String> sink) {
        this.errorSink = sink;
    }

    /** Создать движок, треки (микрофон, экран, камера) и войти в комнату. */
    public void join(String url, String room, String name) {
        // Диагностика окружения: demoaudio=off здесь + работающее видео + ноль
        // [demo]-строк = демо-звук выключен фича-флагом (упакованный лаунчер не
        // передаёт -Dsertas.demoaudio=on). os/audio.dll — для Windows-провайдера.
        System.err.println("[demo] join demoaudio=" + System.getProperty("sertas.demoaudio", "off")
                + " mixer=" + System.getProperty("sertas.mixer", "off")
                + " os=" + System.getProperty("os.name")
                + " audio.dll=" + System.getProperty("sertas.audio.dll", "unset"));
        engine = new WebRtcEngine();
        mesh = new MeshCoordinator(engine, this);
        mediaOps = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "media-ops");
            t.setDaemon(true);
            return t;
        });

        // Per-источниковый микшер у слушателя ВЫКЛЮЧЕН по умолчанию: на реальном
        // железе кастомный setAudioSource заменяет штатный playout libwebrtc и не
        // выдаёт звук (голос пропадает). Включается -Dsertas.mixer=on — после
        // доводки playout на железе станет дефолтом. Когда выключен — audioMixer
        // остаётся null, и звук идёт штатным путём libwebrtc.
        if ("on".equalsIgnoreCase(System.getProperty("sertas.mixer", "off"))) {
            audioMixer = new RemoteAudioMixer();
            engine.audioDeviceModule().setAudioSource(audioMixer);
        }

        mic = engine.createMicTrack();
        mesh.addLocalTrack(mic);

        // Экранный трек согласуем сразу (но не захватываем) — чтобы потом начать
        // демонстрацию без повторной переговорки.
        screen = new ScreenCaptureSource();
        VideoTrack screenTrack = engine.createVideoTrack(ScreenCaptureSource.LABEL, screen.source());
        mesh.addLocalTrack(screenTrack);

        // Камера - отдельным видеотреком (своя m=video рядом с экраном), тоже
        // согласуется сразу, а захват включается кнопкой.
        camera = new CameraSource();
        VideoTrack cameraTrack = engine.createVideoTrack(CameraSource.LABEL, camera.source());
        mesh.addLocalTrack(cameraTrack);
        // Своё превью камеры - зеркально; видно, пока идут кадры (камера включена).
        videoGrid.put(SELF_CAMERA, new VideoTile(cameraTrack, true), VideoGrid.Kind.CAMERA,
                new SimpleStringProperty("Вы"));

        // Потолок видео экрана - из выбранного пресета (и для пиров, что придут позже).
        mesh.setMaxBitrate(ScreenCaptureSource.LABEL, quality.videoMaxBitrate());

        // Звук демо — отдельными соединениями (см. ScreenAudioConnection) на ДВУХ
        // отдельных движках: отдача — pushOnly (ADM kDummyAudio, без потока захвата),
        // приём — headless (виртуальный playout кормит sink'и). Любой ADM с потоком
        // захвата (реальный ADM голоса или headless) кормит все AudioSendStream
        // фабрики и вместе с pushAudio даёт двух производителей → фатальная
        // RTC_CHECK_RUNS_SERIALIZED в audio_send_stream.cc (краш при включении
        // «Звук демонстрации»). Трек НЕ добавляем в главный меш.
        if ("on".equalsIgnoreCase(System.getProperty("sertas.demoaudio", "off"))) {
            audioSendEngine = WebRtcEngine.pushOnly();
            screenAudio = new SystemAudioTrack(audioSendEngine);
            System.err.println("[demo] звук демо включён (send=pushOnly, recv=главный движок)");
        }

        Platform.runLater(() -> {
            self = new ParticipantModel("self", name);
            self.setState("вы");
            participants.add(0, self);
        });

        stats = StatsRecorder.startIfEnabled(this::statsTargets);

        mesh.start(url, room, name);
    }

    /** Соединения для записи статистики: основное и звук демо по каждому пиру. */
    private List<StatsRecorder.Target> statsTargets() {
        List<StatsRecorder.Target> targets = new ArrayList<>();
        MeshCoordinator m = mesh;
        if (m != null) {
            m.sessions().forEach((peer, session) ->
                    targets.add(new StatsRecorder.Target("main", peer, session)));
        }
        screenAudioConns.forEach((peer, conn) -> {
            targets.add(new StatsRecorder.Target("demo-audio-out", peer, conn.outgoing()));
            targets.add(new StatsRecorder.Target("demo-audio-in", peer, conn.incoming()));
        });
        return targets;
    }

    public void setMicMuted(boolean muted) {
        micMuted = muted;
        if (mic != null) {
            mic.setEnabled(!muted);
        }
    }

    public boolean isMicMuted() {
        return micMuted;
    }

    /** Выполнить на потоке медиа-операций звонка; вне звонка - ничего. */
    private void onMediaThread(String what, Runnable task) {
        ExecutorService ops = mediaOps;
        if (ops == null) {
            return;
        }
        try {
            ops.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    System.err.println("[media] " + what + " не удалось: " + e);
                    onError(e);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // звонок уже завершается
        }
    }

    /** Начать демонстрацию основного экрана с выбранным пресетом качества. */
    public void startScreenShare() {
        ScreenCaptureSource src = screen;
        if (src == null) {
            return;
        }
        onMediaThread("старт показа", () -> {
            List<DesktopSource> screens = ScreenCaptureSource.screens();
            if (screens.isEmpty()) {
                onError(new IllegalStateException("нет доступных экранов (проверьте разрешение)"));
                return;
            }
            Quality q = quality;
            sharedScreenId = screens.get(0).id;
            src.select(sharedScreenId, q);
            src.start();
            sharing = true;
            System.err.println("[screen] показ запущен: " + q);
        });
    }

    public void stopScreenShare() {
        onMediaThread("стоп показа", this::stopScreenNow);
    }

    private void stopScreenNow() {
        if (screen != null) {
            try {
                screen.stop();
            } catch (RuntimeException ignored) {
                // источник уже остановлен
            }
        }
        sharing = false;
    }

    public boolean isSharing() {
        return sharing;
    }

    /** Выбранный пресет качества демонстрации. */
    public Quality quality() {
        return quality;
    }

    /**
     * Сменить пресет: потолок видео экрана и битрейт Opus звука демо меняются
     * сразу (без переговорки), а идущий показ перезапускается с новым
     * разрешением и FPS. Выбор запоминается.
     */
    public void setQuality(Quality q) {
        if (q == null || q == quality) {
            return;
        }
        quality = q;
        JoinPrefs.put("quality", q.name());
        onMediaThread("смена пресета", () -> {
            MeshCoordinator m = mesh;
            if (m != null) {
                m.setMaxBitrate(ScreenCaptureSource.LABEL, q.videoMaxBitrate());
            }
            screenAudioConns.values().forEach(c -> c.setMaxBitrate(q.opusBitrate()));
            ScreenCaptureSource src = screen;
            if (sharing && src != null) {
                src.stop();
                src.select(sharedScreenId, q);
                src.start();
            }
            System.err.println("[screen] пресет " + q + (sharing ? " (показ перезапущен)" : ""));
        });
    }

    private static Quality savedQuality() {
        String saved = JoinPrefs.get("quality");
        for (Quality q : Quality.values()) {
            if (q.name().equals(saved)) {
                return q;
            }
        }
        return Quality.DEFAULT;
    }

    /**
     * Список камер - для выбора в UI. Перечисление нативное, поэтому на потоке
     * медиа-операций; результат отдаётся в FX-поток.
     */
    public void refreshCameras(Consumer<List<VideoDevice>> onFx) {
        onMediaThread("список камер", () -> {
            List<VideoDevice> cameras = List.copyOf(CameraSource.cameras());
            Platform.runLater(() -> onFx.accept(cameras));
        });
    }

    /** Камера, с которой будет (или идёт) захват: выбранная, сохранённая по имени или первая. */
    public static VideoDevice pickCamera(List<VideoDevice> cameras, VideoDevice selected, String savedName) {
        if (cameras.isEmpty()) {
            return null;
        }
        if (selected != null && cameras.contains(selected)) {
            return selected;
        }
        if (savedName != null) {
            for (VideoDevice d : cameras) {
                if (savedName.equals(d.getName())) {
                    return d;
                }
            }
        }
        return cameras.get(0);
    }

    /** Выбранная камера ({@code null} - не выбрана, возьмётся сохранённая или первая). */
    public VideoDevice selectedCamera() {
        return selectedCamera;
    }

    /** Сохранённое имя камеры ({@code null} - не выбиралась). */
    public static String savedCameraName() {
        return JoinPrefs.get("camera");
    }

    /** Выбрать камеру; если камера включена - переключить захват на неё. Выбор запоминается. */
    public void selectCamera(VideoDevice device) {
        if (device == null || device.equals(selectedCamera)) {
            return;
        }
        selectedCamera = device;
        JoinPrefs.put("camera", device.getName());
        CameraSource cam = camera;
        if (cameraOn && cam != null) {
            onMediaThread("смена камеры", () -> {
                cam.start(device); // остановит текущую и откроет новую
                System.err.println("[camera] переключено на: " + device.getName());
            });
        }
    }

    /**
     * Включить камеру (выбранную, иначе сохранённую или первую, режим около
     * 720p30). Открытие устройства и на macOS запрос разрешения - не на FX-потоке.
     */
    public void startCamera() {
        CameraSource cam = camera;
        if (cam == null) {
            return;
        }
        onMediaThread("старт камеры", () -> {
            VideoDevice device = pickCamera(CameraSource.cameras(), selectedCamera, savedCameraName());
            if (device == null) {
                onError(new IllegalStateException("камера не найдена (проверьте подключение и разрешение)"));
                return;
            }
            cam.start(device);
            cameraOn = true;
            System.err.println("[camera] захват запущен: " + device.getName());
        });
    }

    public void stopCamera() {
        onMediaThread("стоп камеры", () -> {
            stopCameraNow();
            // Убрать превью сразу, а не через 2 секунды застывшего кадра.
            Platform.runLater(() -> {
                VideoTile self = videoGrid.tile(SELF_CAMERA);
                if (self != null) {
                    self.clear();
                }
            });
        });
    }

    private void stopCameraNow() {
        if (camera != null) {
            try {
                camera.stop();
            } catch (RuntimeException ignored) {
                // источник уже остановлен
            }
        }
        cameraOn = false;
    }

    public boolean isCameraOn() {
        return cameraOn;
    }

    /** Включить передачу системного звука демонстрации зрителям. */
    public void startScreenAudio() {
        if (screenAudio == null) {
            return;
        }
        SystemAudioProvider provider = nativeSystemAudioProvider();
        System.err.println("[demo] startScreenAudio: provider=" + (provider == null ? "НЕТ (нативный захват недоступен)"
                : provider.getClass().getSimpleName()) + ", соединений=" + screenAudioConns.size());
        if (provider == null) {
            onError(new IllegalStateException(
                    "нативный захват звука демо недоступен на этой платформе "
                            + "(нужен dylib/dll — см. scripts/build-*-audio-*)"));
            return;
        }
        try {
            screenAudio.start(provider);
            screenAudioOn = true;
            System.err.println("[demo] захват звука демо запущен");
        } catch (RuntimeException e) {
            onError(e);
        }
    }

    /** Нативный провайдер системного звука под текущую ОС (null — недоступен). */
    private static SystemAudioProvider nativeSystemAudioProvider() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) {
            return MacSystemAudioCapture.isAvailable() ? new MacSystemAudioCapture() : null;
        }
        if (os.contains("win")) {
            return WinSystemAudioCapture.isAvailable() ? new WinSystemAudioCapture() : null;
        }
        return null;
    }

    public void stopScreenAudio() {
        if (screenAudio != null) {
            screenAudio.stop();
        }
        screenAudioOn = false;
    }

    public boolean isScreenAudioOn() {
        return screenAudioOn;
    }

    public void leave() {
        System.err.println("[life] leave STAGE=entry thread=" + Thread.currentThread().getName()
                + " t=" + System.nanoTime());
        // Статистику останавливаем первой: ни один опрос не должен застать
        // соединения на середине закрытия.
        if (stats != null) {
            stats.close();
            stats = null;
        }
        // Медиа-операции: дождаться идущей (старт камеры/показа) и отменить
        // остальные, дальше остановка - синхронно, до освобождения движка.
        ExecutorService ops = mediaOps;
        mediaOps = null;
        if (ops != null) {
            ops.shutdownNow();
            try {
                ops.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Своё превью висит sink'ом на локальном треке камеры: снять его, пока
        // движок жив (removeSink идёт через его потоки).
        videoGrid.remove(SELF_CAMERA);
        stopScreenNow();
        stopCameraNow();
        if (screenAudio != null) {
            screenAudio.stop();
            screenAudio = null;
        }
        screenAudioOn = false;
        screenAudioConns.values().forEach(ScreenAudioConnection::close);
        screenAudioConns.clear();
        if (mesh != null) {
            System.err.println("[life] leave STAGE=mesh.stop t=" + System.nanoTime());
            mesh.stop();
            mesh = null;
        }
        if (engine != null) {
            System.err.println("[life] leave STAGE=engine.dispose t=" + System.nanoTime());
            engine.dispose();
            engine = null;
        }
        if (audioSendEngine != null) {
            audioSendEngine.dispose();
            audioSendEngine = null;
        }
        mic = null;
        screen = null;
        camera = null;
        audioMixer = null;
        Platform.runLater(() -> {
            // ВАЖНО: этот runLater исполняется ПОСЛЕ синхронных mesh.stop()/engine.dispose()
            // выше (leave() сам на FX-потоке) — нативные треки уже освобождены здесь.
            System.err.println("[life] leave STAGE=tiles.dispose-deferred tiles=" + videoGrid.size()
                    + " t=" + System.nanoTime());
            videoGrid.clear();
            participants.clear();
            byId.clear();
            wiredGains.clear();
            self = null;
        });
    }

    @Override
    public void onPeerJoined(String peerId, String name) {
        Platform.runLater(() -> {
            ParticipantModel m = byId.get(peerId);
            if (m == null) {
                m = new ParticipantModel(peerId, name);
                byId.put(peerId, m);
                participants.add(m);
            } else {
                m.setName(name);
            }
        });
    }

    @Override
    public void onPeerLeft(String peerId) {
        Platform.runLater(() -> {
            ParticipantModel m = byId.remove(peerId);
            if (m != null) {
                participants.remove(m);
            }
            System.err.println("[life] onPeerLeft→dispose tiles peer=" + peerId
                    + " thread=" + Thread.currentThread().getName() + " t=" + System.nanoTime());
            videoGrid.removeByPrefix(peerId + ":");
            if (audioMixer != null) {
                audioMixer.detach(peerId);
            }
            wiredGains.remove(peerId + ":" + RemoteAudioMixer.Kind.VOICE);
            wiredGains.remove(peerId + ":" + RemoteAudioMixer.Kind.DEMO);
            ScreenAudioConnection conn = screenAudioConns.remove(peerId);
            if (conn != null) {
                conn.close();
            }
        });
    }

    @Override
    public void onPeerState(String peerId, RTCPeerConnectionState state) {
        Platform.runLater(() -> {
            ParticipantModel m = byId.get(peerId);
            if (m != null) {
                m.setState(stateLabel(state));
            }
        });
    }

    @Override
    public void onControlChannel(String peerId, RTCDataChannel channel, boolean initiator) {
        if (audioSendEngine == null || screenAudio == null || engine == null) {
            return; // звук демо выключен
        }
        System.err.println("[demo] onControlChannel peer=" + peerId + " initiator=" + initiator);
        ScreenAudioConnection conn = new ScreenAudioConnection(
                audioSendEngine, engine, channel, screenAudio.track(),
                t -> onRemoteScreenAudio(peerId, t), SCREEN_AUDIO_MUSIC);
        conn.setMaxBitrate(quality.opusBitrate()); // битрейт Opus - из пресета
        ScreenAudioConnection old = screenAudioConns.put(peerId, conn);
        if (old != null) {
            old.close();
        }
    }

    /**
     * Удалённый трек звука демо (на отдельном соединении, но на главном движке):
     * реальный ADM libwebrtc играет его сам. При включённом per-источниковом микшере
     * (-Dsertas.mixer=on) подключаем к микшеру как вид DEMO, как и треки главного меша.
     */
    private void onRemoteScreenAudio(String peerId, RTCRtpTransceiver transceiver) {
        MediaStreamTrack track = transceiver.getReceiver().getTrack();
        if (track instanceof AudioTrack audio) {
            Platform.runLater(() -> attachRemoteAudio(peerId, audio));
        }
    }

    @Override
    public void onRemoteTrack(String peerId, RTCRtpTransceiver transceiver, String label) {
        Platform.runLater(() -> {
            MediaStreamTrack track = transceiver.getReceiver().getTrack();
            if (track == null) {
                return;
            }
            if (MediaStreamTrack.AUDIO_TRACK_KIND.equals(track.getKind())) {
                attachRemoteAudio(peerId, (AudioTrack) track);
                if ("on".equalsIgnoreCase(System.getProperty("sertas.audiostats", "off"))) {
                    wireAudioStats(peerId, (AudioTrack) track);
                }
                return;
            }
            if (!MediaStreamTrack.VIDEO_TRACK_KIND.equals(track.getKind())) {
                return;
            }
            // У пира два видеотрека - экран и камера: плитка на каждый. Старый клиент
            // без метки в SDP шлёт только экран.
            String trackLabel = label != null ? label : ScreenCaptureSource.LABEL;
            String key = peerId + ":" + trackLabel;
            boolean isCamera = CameraSource.LABEL.equals(trackLabel);
            System.err.println("[life] onRemoteTrack tile=" + key + " kind=" + track.getKind()
                    + " replacingOld=" + (videoGrid.tile(key) != null) + " t=" + System.nanoTime());
            videoGrid.put(key, new VideoTile((VideoTrack) track),
                    isCamera ? VideoGrid.Kind.CAMERA : VideoGrid.Kind.SCREEN,
                    caption(peerId, isCamera));
        });
    }

    /** Подпись плитки: имя участника (следит за переименованием), у экрана - с пометкой. */
    private ObservableStringValue caption(String peerId, boolean camera) {
        ParticipantModel m = byId.get(peerId);
        if (m == null) {
            return new SimpleStringProperty(camera ? peerId : peerId + " - экран");
        }
        return camera ? m.nameProperty() : Bindings.concat(m.nameProperty(), " - экран");
    }

    /**
     * Диагностика приёма голоса (-Dsertas.audiostats=on): RMS декодированного PCM
     * раз в ~5с. RMS > 0 — звук реально доходит и декодируется (не тишина).
     */
    private static void wireAudioStats(String peerId, AudioTrack audio) {
        audio.addSink(new dev.onvoid.webrtc.media.audio.AudioTrackSink() {
            private long sumSq;
            private long samples;
            private long windowStartNs;

            @Override
            public void onData(byte[] data, int bitsPerSample, int sampleRate,
                               int channels, int frames) {
                if (bitsPerSample != 16) {
                    return;
                }
                int n = Math.min(data.length, frames * channels * 2);
                for (int i = 0; i + 1 < n; i += 2) {
                    int v = (short) ((data[i] & 0xff) | (data[i + 1] << 8));
                    sumSq += (long) v * v;
                    samples++;
                }
                long now = System.nanoTime();
                if (windowStartNs == 0) {
                    windowStartNs = now;
                } else if (now - windowStartNs >= 5_000_000_000L && samples > 0) {
                    long rms = Math.round(Math.sqrt((double) sumSq / samples));
                    System.err.println("[recv] audio peer=" + peerId + " rms=" + rms
                            + " (" + sampleRate + "Гц x" + channels + ", сэмплов=" + samples + ")");
                    sumSq = 0;
                    samples = 0;
                    windowStartNs = now;
                }
            }
        });
    }

    /** Подключить удалённый аудио-трек к микшеру и связать его громкость со слайдером участника. */
    private void attachRemoteAudio(String peerId, AudioTrack audio) {
        if (audioMixer == null) {
            return;
        }
        audioMixer.attach(peerId, audio); // идемпотентно
        RemoteAudioMixer.Kind kind = RemoteAudioMixer.kindOf(audio);
        ParticipantModel m = byId.get(peerId);
        String key = peerId + ":" + kind;
        // Слушатель вешаем один раз на (участник, вид) — иначе дубли при повторном onRemoteTrack.
        if (m != null && wiredGains.add(key)) {
            DoubleProperty gain = kind == RemoteAudioMixer.Kind.DEMO
                    ? m.demoGainProperty() : m.voiceGainProperty();
            audioMixer.setGain(peerId, kind, (float) gain.get());
            gain.addListener((obs, o, n) -> {
                RemoteAudioMixer mx = audioMixer; // снимок: после leave() поле null → no-op
                if (mx != null) {
                    mx.setGain(peerId, kind, n.floatValue());
                }
            });
        }
    }

    @Override
    public void onError(Throwable error) {
        System.err.println("mesh error: " + error);
        Consumer<String> sink = errorSink;
        if (sink != null) {
            String msg = error.getMessage() != null ? error.getMessage() : String.valueOf(error);
            Platform.runLater(() -> sink.accept(msg));
        }
    }

    private static String stateLabel(RTCPeerConnectionState state) {
        return switch (state) {
            case NEW -> "…";
            case CONNECTING -> "соединение…";
            case CONNECTED -> "на связи";
            case DISCONNECTED -> "отключён";
            case FAILED -> "ошибка";
            case CLOSED -> "закрыт";
        };
    }
}

package dev.sertas.app;

import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.sertas.app.ui.ParticipantModel;
import dev.sertas.app.ui.VideoTile;
import dev.sertas.engine.CameraSource;
import dev.sertas.engine.MacSystemAudioCapture;
import dev.sertas.engine.MeshCoordinator;
import dev.sertas.engine.MeshListener;
import dev.sertas.engine.RemoteAudioMixer;
import dev.sertas.engine.ScreenAudioConnection;
import dev.sertas.engine.ScreenCaptureSource;
import dev.sertas.engine.StatsRecorder;
import dev.sertas.engine.SystemAudioProvider;
import dev.sertas.engine.SystemAudioTrack;
import dev.sertas.engine.WebRtcEngine;
import dev.sertas.engine.WinSystemAudioCapture;
import dev.sertas.media.OpusSdpMunger;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.layout.FlowPane;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Связывает {@link MeshCoordinator} с JavaFX-UI. Колбэки меша приходят на
 * нативных/WS-потоках и маршалятся на FX-поток через {@link Platform#runLater}.
 */
public final class CallController implements MeshListener {

    private final ObservableList<ParticipantModel> participants = FXCollections.observableArrayList();
    private final FlowPane videoPane = new FlowPane(12, 12);
    private final Map<String, ParticipantModel> byId = new HashMap<>();   // только FX-поток
    private final Map<String, VideoTile> tiles = new HashMap<>();         // "peerId:метка" → плитка; только FX-поток
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

    public CallController() {
        videoPane.setPadding(new Insets(12));
    }

    public ObservableList<ParticipantModel> participants() {
        return participants;
    }

    /** Панель с видео-плитками удалённых участников. */
    public FlowPane videoPane() {
        return videoPane;
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

    /**
     * Начать демонстрацию экрана (основной экран). Захват/энумерация экранов —
     * тяжёлая нативная работа (TCC, ScreenCaptureKit), поэтому в ОТДЕЛЬНОМ потоке:
     * иначе FX-поток блокируется и UI «лагает» при нажатии «Демонстрация».
     */
    public void startScreenShare() {
        ScreenCaptureSource src = screen;
        if (src == null) {
            return;
        }
        new Thread(() -> {
            try {
                List<dev.onvoid.webrtc.media.video.desktop.DesktopSource> screens = ScreenCaptureSource.screens();
                if (screens.isEmpty()) {
                    onError(new IllegalStateException("нет доступных экранов (проверьте разрешение)"));
                    return;
                }
                src.select(screens.get(0).id, ScreenCaptureSource.Quality.BALANCED);
                src.start();
                sharing = true;
            } catch (RuntimeException e) {
                onError(e);
            }
        }, "screen-share-start").start();
    }

    public void stopScreenShare() {
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

    /**
     * Включить камеру (первую найденную, режим около 720p30). Открытие устройства
     * и на macOS запрос разрешения - в отдельном потоке, как и старт показа.
     */
    public void startCamera() {
        CameraSource cam = camera;
        if (cam == null) {
            return;
        }
        new Thread(() -> {
            try {
                List<dev.onvoid.webrtc.media.video.VideoDevice> cameras = CameraSource.cameras();
                if (cameras.isEmpty()) {
                    onError(new IllegalStateException("камера не найдена (проверьте подключение и разрешение)"));
                    return;
                }
                cam.start(cameras.get(0));
                cameraOn = true;
                System.err.println("[camera] захват запущен: " + cameras.get(0).getName());
            } catch (RuntimeException e) {
                onError(e);
            }
        }, "camera-start").start();
    }

    public void stopCamera() {
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
        stopScreenShare();
        stopCamera();
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
            System.err.println("[life] leave STAGE=tiles.dispose-deferred tiles=" + tiles.size()
                    + " t=" + System.nanoTime());
            tiles.values().forEach(VideoTile::dispose);
            tiles.clear();
            videoPane.getChildren().clear();
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
            tiles.entrySet().removeIf(e -> {
                if (!e.getKey().startsWith(peerId + ":")) {
                    return false;
                }
                System.err.println("[life] onPeerLeft→dispose tile=" + e.getKey()
                        + " thread=" + Thread.currentThread().getName() + " t=" + System.nanoTime());
                videoPane.getChildren().remove(e.getValue().node());
                e.getValue().dispose();
                return true;
            });
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
            String key = peerId + ":" + (label != null ? label : ScreenCaptureSource.LABEL);
            VideoTile old = tiles.remove(key);
            System.err.println("[life] onRemoteTrack tile=" + key + " kind=" + track.getKind()
                    + " replacingOld=" + (old != null) + " t=" + System.nanoTime());
            if (old != null) {
                videoPane.getChildren().remove(old.node());
                old.dispose();
            }
            VideoTile tile = new VideoTile((VideoTrack) track);
            tiles.put(key, tile);
            videoPane.getChildren().add(tile.node());
        });
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

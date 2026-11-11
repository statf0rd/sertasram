package dev.sertas.engine;

import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.sertas.protocol.IceServer;
import dev.sertas.protocol.Peer;
import dev.sertas.protocol.SignalMessage;
import dev.sertas.protocol.SignalMessage.Answer;
import dev.sertas.protocol.SignalMessage.Ice;
import dev.sertas.protocol.SignalMessage.Join;
import dev.sertas.protocol.SignalMessage.Offer;
import dev.sertas.protocol.SignalMessage.PeerJoined;
import dev.sertas.protocol.SignalMessage.PeerLeft;
import dev.sertas.protocol.SignalMessage.RoomState;
import dev.sertas.protocol.SignalMessage.TrackMeta;
import dev.sertas.media.OpusSdpMunger;
import dev.sertas.signaling.client.SignalingClient;
import dev.sertas.signaling.client.SignalingListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Оркестратор P2P-меша: связывает {@link SignalingClient} с {@link PeerSession}
 * (по одной на пира). Без glare: новичок, получив список комнаты, шлёт offer
 * всем присутствующим; уже присутствующие отвечают answer'ом. Так на каждую пару
 * приходится ровно один инициатор.
 */
public final class MeshCoordinator implements SignalingListener {

    private final WebRtcEngine engine;
    private final MeshListener listener;
    private final SignalingClient signaling = new SignalingClient(this);
    private final Map<String, PeerSession> sessions = new ConcurrentHashMap<>();
    private final List<MediaStreamTrack> localTracks = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Музыкальный профиль Opus навешивается только на секцию трека звука демо. */
    private static final UnaryOperator<String> SCREEN_AUDIO_MUSIC =
            sdp -> OpusSdpMunger.applyMusicProfileToTrack(sdp, SystemAudioTrack.LABEL);

    private volatile String room;
    private volatile String name;
    private volatile String selfId;
    /** ICE-серверы: дефолт локальный, заменяются серверными из RoomState. */
    private volatile List<RTCIceServer> iceServers = IceServersConfig.resolve();

    public MeshCoordinator(WebRtcEngine engine, MeshListener listener) {
        this.engine = engine;
        this.listener = listener;
    }

    /**
     * Зарегистрировать локальный трек (микрофон/камера/экран), который будет
     * добавлен в каждую peer-сессию. Вызывать ДО {@link #start} — текущая
     * версия не делает renegotiation для уже подключённых пиров.
     */
    public void addLocalTrack(MediaStreamTrack track) {
        localTracks.add(track);
    }

    /** Подключиться к сигналинг-серверу и войти в комнату. */
    public void start(String url, String room, String name) {
        this.room = room;
        this.name = name;
        signaling.connect(url);
    }

    public String selfId() {
        return selfId;
    }

    /** Снимок текущих peer-сессий (peerId → сессия) - для сбора статистики. */
    public Map<String, PeerSession> sessions() {
        return Map.copyOf(sessions);
    }

    public void stop() {
        System.err.println("[life] mesh.stop thread=" + Thread.currentThread().getName()
                + " t=" + System.nanoTime());
        sessions.values().forEach(PeerSession::close);
        sessions.clear();
        signaling.close();
    }

    @Override
    public void onOpen() {
        signaling.send(new Join(room, name));
    }

    @Override
    public void onMessage(SignalMessage msg) {
        if (msg instanceof RoomState rs) {
            selfId = rs.selfId();
            applyIceServers(rs.iceServers());
            for (Peer p : rs.peers()) {
                listener.onPeerJoined(p.id(), p.name());
                onPeerKnown(p.id());
            }
        } else if (msg instanceof PeerJoined pj) {
            listener.onPeerJoined(pj.id(), pj.name());
            onPeerKnown(pj.id());
        } else if (msg instanceof PeerLeft pl) {
            // pc.close() здесь — на WS-потоке; removeSink тайла откладывается на FX
            // (listener.onPeerLeft → Platform.runLater). Гонка двух потоков по одному
            // нативному треку — потенциальный use-after-free.
            System.err.println("[life] mesh PeerLeft peer=" + pl.id() + " thread="
                    + Thread.currentThread().getName() + " t=" + System.nanoTime());
            PeerSession s = sessions.remove(pl.id());
            if (s != null) {
                s.close();
            }
            listener.onPeerLeft(pl.id());
        } else if (msg instanceof Offer o) {
            answererFor(o.peer()).onRemoteDescription(new RTCSessionDescription(RTCSdpType.OFFER, o.sdp()));
        } else if (msg instanceof Answer a) {
            PeerSession s = sessions.get(a.peer());
            if (s != null) {
                s.onRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, a.sdp()));
            }
        } else if (msg instanceof Ice ice) {
            PeerSession s = sessions.get(ice.peer());
            if (s != null) {
                s.onRemoteIceCandidate(new RTCIceCandidate(ice.sdpMid(), ice.sdpMLineIndex(), ice.candidate()));
            }
        } else if (msg instanceof TrackMeta) {
            // метаданные треков — обработка в UI на следующем этапе
        }
    }

    @Override
    public void onError(Throwable t) {
        listener.onError(t);
    }

    /**
     * Узнали о пире. Инициатор — тот, у кого id лексикографически меньше: обе
     * стороны вычисляют одного инициатора независимо, без гонки и glare.
     */
    private void onPeerKnown(String peerId) {
        PeerSession session = sessions.computeIfAbsent(peerId, this::newSession);
        boolean iInitiate = selfId != null && selfId.compareTo(peerId) < 0;
        if (iInitiate) {
            RTCDataChannel control = session.createDataChannel("control");
            listener.onControlChannel(peerId, control, true);
            session.createOffer();
        }
    }

    /** Получить (или создать) сессию для пира (без инициации offer). */
    private PeerSession answererFor(String peerId) {
        return sessions.computeIfAbsent(peerId, this::newSession);
    }

    private PeerSession newSession(String peerId) {
        PeerSession[] self = new PeerSession[1]; // onTrack приходит уже после конструктора
        PeerSession session = new PeerSession(engine, new PeerSession.Signals() {
            @Override
            public void onLocalDescription(RTCSessionDescription d) {
                if (d.sdpType == RTCSdpType.OFFER) {
                    signaling.send(new Offer(peerId, d.sdp));
                } else {
                    signaling.send(new Answer(peerId, d.sdp));
                }
            }

            @Override
            public void onLocalIceCandidate(RTCIceCandidate c) {
                signaling.send(new Ice(peerId, c.sdp, c.sdpMid, c.sdpMLineIndex));
            }

            @Override
            public void onConnectionState(dev.onvoid.webrtc.RTCPeerConnectionState state) {
                listener.onPeerState(peerId, state);
            }

            @Override
            public void onDataChannel(RTCDataChannel channel) {
                listener.onControlChannel(peerId, channel, false);
            }

            @Override
            public void onTrack(RTCRtpTransceiver transceiver) {
                // id удалённого трека тут не годится: у трансивера, созданного своим
                // addTrack до переговорки, он случайный. Метку берём из SDP пира.
                listener.onRemoteTrack(peerId, transceiver, self[0].remoteTrackLabel(transceiver));
            }

            @Override
            public void onError(Throwable error) {
                listener.onError(error);
            }
        }, iceServers, SCREEN_AUDIO_MUSIC);
        self[0] = session;
        for (MediaStreamTrack track : localTracks) {
            session.addTrack(track);
        }
        return session;
    }

    /** Конвертировать ICE-серверы из протокола в формат webrtc-java. */
    private void applyIceServers(List<IceServer> servers) {
        if (servers == null || servers.isEmpty()) {
            return; // оставляем локальный дефолт
        }
        List<RTCIceServer> converted = new ArrayList<>();
        for (IceServer s : servers) {
            RTCIceServer r = new RTCIceServer();
            r.urls = new ArrayList<>(s.urls());
            r.username = s.username();
            r.password = s.credential();
            converted.add(r);
        }
        iceServers = converted;
    }
}


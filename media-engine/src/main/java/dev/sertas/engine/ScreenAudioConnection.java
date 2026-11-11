package dev.sertas.engine;

import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.media.MediaStreamTrack;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Соединение для звука демонстрации с одним пиром: ДВЕ односторонние
 * {@link PeerSession}, чьи offer/answer/ICE туннелируются через УЖЕ существующий
 * control data-channel главного соединения — без правок сигналинг-сервера/протокола.
 *
 * <ul>
 *   <li><b>out</b> — наш screen-audio трек → пиру. Живёт на движке
 *       {@link WebRtcEngine#pushOnly()}: у его ADM нет потока захвата, и единственный
 *       производитель кадров для {@code AudioSendStream} — наш push-поток.</li>
 *   <li><b>in</b> — трек пира → нам. Живёт на движке, чей ADM должен его играть: в
 *       приложении — ГЛАВНЫЙ (реальный ADM: стерео, тактирование устройством,
 *       AEC-референс), в тестах — {@link WebRtcEngine#headless()} (виртуальный
 *       playout гонит PCM в sink'и). Отдающих потоков у in-сессии нет, так что
 *       захват ADM ей не мешает.</li>
 * </ul>
 *
 * <p>Почему нельзя одним соединением на одном движке: любой ADM с потоком захвата
 * (реальный или headless) кормит ВСЕ отдающие потоки фабрики, и вместе с
 * {@code CustomAudioSource.pushAudio} получается два производителя для одного
 * {@code AudioSendStream::SendAudioData} — libwebrtc падает на
 * {@code RTC_CHECK_RUNS_SERIALIZED} (audio_send_stream.cc). Разнос по движкам
 * убирает второго производителя, а не прячет гонку.
 *
 * <p>Протокол поверх control-канала (первый символ — вид, дальше base64):
 * {@code O} — offer нашей out-сессии (пир отдаёт в свою in-сессию), {@code A} — answer
 * in-сессии, {@code I} — ICE out-сессии, {@code J} — ICE in-сессии. Обе стороны
 * симметричны: каждая сама оффер-ит свою out-сессию, glare невозможен.
 *
 * <p>Потоки: сообщения control-канала приходят на сетевом потоке ГЛАВНОГО движка,
 * а вызовы {@code setRemoteDescription}/{@code addIceCandidate}/{@code createOffer}
 * — блокирующие Invoke на signaling-поток движка звука. Тот, в свою очередь, из
 * своих колбэков шлёт в control-канал (Invoke на сетевой поток главного движка).
 * Чтобы эти два потока не ждали друг друга (deadlock), всё, что трогает
 * PeerSession'ы звука, выполняется на собственном последовательном потоке.
 */
public final class ScreenAudioConnection {

    private final PeerSession out; // null у чистого зрителя (без локального трека)
    private final PeerSession in;
    private final RTCDataChannel channel;
    private final AtomicBoolean offered = new AtomicBoolean();
    private final ExecutorService signaling = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "screen-audio-signaling");
        t.setDaemon(true);
        return t;
    });

    /**
     * @param sendEngine         движок отдачи ({@link WebRtcEngine#pushOnly()}); трек должен быть создан на нём
     * @param recvEngine         движок приёма — тот, чей ADM играет трек (главный; в тестах headless)
     * @param channel            control data-channel главного соединения (транспорт сигналинга)
     * @param localTrack         локальный screen-audio трек (null у чистого зрителя)
     * @param onRemoteTrack      удалённый screen-audio трек прибыл (для воспроизведения)
     * @param localSdpTransform  SDP-munging (музыкальный профиль Opus)
     */
    public ScreenAudioConnection(WebRtcEngine sendEngine, WebRtcEngine recvEngine, RTCDataChannel channel,
                                 MediaStreamTrack localTrack,
                                 Consumer<RTCRtpTransceiver> onRemoteTrack,
                                 UnaryOperator<String> localSdpTransform) {
        this.channel = channel;
        System.err.println("[demo] ScreenAudioConnection создан (localTrack=" + (localTrack != null) + ")");

        this.in = new PeerSession(recvEngine, new PeerSession.Signals() {
            @Override
            public void onLocalDescription(RTCSessionDescription d) {
                System.err.println("[demo] saConn.in → answer");
                send('A', d.sdp);
            }

            @Override
            public void onLocalIceCandidate(RTCIceCandidate c) {
                send('J', c.sdpMid + "\n" + c.sdpMLineIndex + "\n" + c.sdp);
            }

            @Override
            public void onConnectionState(dev.onvoid.webrtc.RTCPeerConnectionState state) {
                System.err.println("[demo] saConn.in state=" + state);
            }

            @Override
            public void onTrack(RTCRtpTransceiver t) {
                System.err.println("[demo] saConn.in ← удалённый screen-audio трек прибыл");
                onRemoteTrack.accept(t);
            }

            @Override
            public void onError(Throwable e) {
                System.err.println("[demo] saConn.in error: " + e);
            }
        }, null, localSdpTransform);

        if (localTrack != null) {
            this.out = new PeerSession(sendEngine, new PeerSession.Signals() {
                @Override
                public void onLocalDescription(RTCSessionDescription d) {
                    System.err.println("[demo] saConn.out → offer");
                    send('O', d.sdp);
                }

                @Override
                public void onLocalIceCandidate(RTCIceCandidate c) {
                    send('I', c.sdpMid + "\n" + c.sdpMLineIndex + "\n" + c.sdp);
                }

                @Override
                public void onConnectionState(dev.onvoid.webrtc.RTCPeerConnectionState state) {
                    System.err.println("[demo] saConn.out state=" + state);
                }

                @Override
                public void onError(Throwable e) {
                    System.err.println("[demo] saConn.out error: " + e);
                }
            }, null, localSdpTransform);
            this.out.addTrack(localTrack);
        } else {
            this.out = null;
        }

        channel.registerObserver(new RTCDataChannelObserver() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {}

            @Override
            public void onStateChange() {
                async(() -> maybeOffer());
            }

            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {
                // Копируем данные сразу: буфер нативный и живёт только в колбэке.
                byte[] b = new byte[buffer.data.remaining()];
                buffer.data.get(b);
                async(() -> handle(b));
            }
        });
        async(this::maybeOffer); // вдруг канал уже открыт
    }

    private void async(Runnable step) {
        try {
            signaling.execute(() -> {
                try {
                    step.run();
                } catch (RuntimeException e) {
                    System.err.println("[demo] saConn signaling error: " + e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // соединение уже закрыто
        }
    }

    private void maybeOffer() {
        if (out != null && channel.getState() == RTCDataChannelState.OPEN && offered.compareAndSet(false, true)) {
            System.err.println("[demo] control-канал OPEN → создаю offer звука демо");
            out.createOffer();
        }
    }

    private void send(char kind, String payload) {
        String msg = kind + Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        try {
            channel.send(new RTCDataChannelBuffer(ByteBuffer.wrap(msg.getBytes(StandardCharsets.UTF_8)), false));
        } catch (Exception ignored) {
            // канал закрылся — соединение пересоберётся при следующем control-канале
        }
    }

    private void handle(byte[] b) {
        String s = new String(b, StandardCharsets.UTF_8);
        if (s.isEmpty()) {
            return;
        }
        char kind = s.charAt(0);
        String payload;
        try {
            payload = new String(Base64.getDecoder().decode(s.substring(1)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return; // чужое сообщение на канале — игнор
        }
        switch (kind) {
            case 'O' -> {
                System.err.println("[demo] saConn.in ← offer");
                in.onRemoteDescription(new RTCSessionDescription(RTCSdpType.OFFER, payload));
            }
            case 'A' -> {
                System.err.println("[demo] saConn.out ← answer");
                if (out != null) {
                    out.onRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, payload));
                }
            }
            case 'I' -> in.onRemoteIceCandidate(parseCandidate(payload));
            case 'J' -> {
                if (out != null) {
                    out.onRemoteIceCandidate(parseCandidate(payload));
                }
            }
            default -> { /* чужое сообщение на канале — игнор */ }
        }
    }

    private static RTCIceCandidate parseCandidate(String payload) {
        String[] p = payload.split("\n", 3);
        return new RTCIceCandidate(p[0], Integer.parseInt(p[1]), p[2]);
    }

    /** Сессия отдачи звука демо ({@code null} у чистого зрителя) - для статистики. */
    public PeerSession outgoing() {
        return out;
    }

    /** Сессия приёма звука демо - для статистики. */
    public PeerSession incoming() {
        return in;
    }

    public void close() {
        signaling.shutdownNow();
        if (out != null) {
            out.close();
        }
        in.close();
    }
}

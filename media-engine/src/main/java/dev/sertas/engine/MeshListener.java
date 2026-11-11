package dev.sertas.engine;

import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpTransceiver;

/** События меша наружу (в UI). Вызываются на нативных потоках WebRTC / WS. */
public interface MeshListener {

    default void onPeerJoined(String peerId, String name) {}

    default void onPeerLeft(String peerId) {}

    default void onPeerState(String peerId, RTCPeerConnectionState state) {}

    /**
     * Удалённый трек (аудио/видео) от пира — для подписки на рендер/воспроизведение.
     *
     * @param label id трека у отправителя из его SDP ({@code mic}, {@code screen},
     *              {@code camera}); {@code null}, если отправитель его не сообщил
     */
    default void onRemoteTrack(String peerId, RTCRtpTransceiver transceiver, String label) {}

    /**
     * Управляющий data-channel с пиром открыт.
     *
     * @param initiator {@code true} — мы создали канал (инициатор пары); нужно
     *                  для согласования второго соединения (звук демо) поверх канала.
     */
    default void onControlChannel(String peerId, RTCDataChannel channel, boolean initiator) {}

    default void onError(Throwable error) {}
}

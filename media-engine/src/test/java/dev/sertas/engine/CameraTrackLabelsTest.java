package dev.sertas.engine;

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.VideoTrack;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Камера идёт отдельной {@code m=video} рядом с экраном, и обе стороны меша
 * узнают, какой удалённый трек - экран, а какой - камера.
 *
 * <p>Как в меше, обе стороны добавляют свои треки ДО переговорки: трансиверы
 * отвечающего создаются его {@code addTrack}, поэтому id удалённых треков у него
 * случайные, и метку можно взять только из SDP другой стороны.
 */
class CameraTrackLabelsTest {

    @Test
    void bothSidesLabelRemoteScreenAndCameraTracks() throws Exception {
        WebRtcEngine engine = WebRtcEngine.headless();
        CameraSource cameraA = new CameraSource();
        CameraSource cameraB = new CameraSource();
        CustomVideoSource screenA = new CustomVideoSource();
        CustomVideoSource screenB = new CustomVideoSource();
        PeerSession[] peers = new PeerSession[2];
        Map<String, String> labelsAtA = new ConcurrentHashMap<>(); // label -> kind
        Map<String, String> labelsAtB = new ConcurrentHashMap<>();
        CountDownLatch allTracks = new CountDownLatch(6);

        try {
            peers[0] = new PeerSession(engine, signals(peers, 1, labelsAtA, allTracks));
            peers[1] = new PeerSession(engine, signals(peers, 0, labelsAtB, allTracks));
            for (int i = 0; i < 2; i++) {
                AudioTrack mic = engine.createMicTrack();
                VideoTrack screen = engine.createVideoTrack(ScreenCaptureSource.LABEL,
                        i == 0 ? screenA : screenB);
                // Камера - из НЕзапущенного источника, как при входе в звонок.
                VideoTrack camera = engine.createVideoTrack(CameraSource.LABEL,
                        (i == 0 ? cameraA : cameraB).source());
                peers[i].addTrack(mic);
                peers[i].addTrack(screen);
                peers[i].addTrack(camera);
            }

            peers[0].createOffer();

            assertTrue(allTracks.await(15, TimeUnit.SECONDS),
                    "не все треки пришли: A=" + labelsAtA + " B=" + labelsAtB);
            Map<String, String> expected = Map.of(
                    "mic", MediaStreamTrack.AUDIO_TRACK_KIND,
                    ScreenCaptureSource.LABEL, MediaStreamTrack.VIDEO_TRACK_KIND,
                    CameraSource.LABEL, MediaStreamTrack.VIDEO_TRACK_KIND);
            assertEquals(expected, labelsAtA, "инициатор");
            assertEquals(expected, labelsAtB, "отвечающий");

            // Свои метки по локальному SDP: камера и экран - разные m-секции.
            Set<String> ownLabels = Set.copyOf(peers[0].trackLabels(false).values());
            assertEquals(Set.of("mic", ScreenCaptureSource.LABEL, CameraSource.LABEL), ownLabels);
        } finally {
            for (PeerSession p : peers) {
                if (p != null) {
                    p.close();
                }
            }
            cameraA.dispose();
            cameraB.dispose();
            engine.dispose();
        }
    }

    private static PeerSession.Signals signals(PeerSession[] peers, int other,
                                               Map<String, String> labels, CountDownLatch tracks) {
        return new PeerSession.Signals() {
            @Override
            public void onLocalDescription(RTCSessionDescription d) {
                peers[other].onRemoteDescription(d);
            }

            @Override
            public void onLocalIceCandidate(RTCIceCandidate c) {
                peers[other].onRemoteIceCandidate(c);
            }

            @Override
            public void onTrack(RTCRtpTransceiver transceiver) {
                PeerSession self = peers[1 - other];
                String label = self.remoteTrackLabel(transceiver);
                String kind = transceiver.getReceiver().getTrack().getKind();
                System.err.println("[test] onTrack label=" + label + " kind=" + kind
                        + " remoteTrackId=" + transceiver.getReceiver().getTrack().getId());
                if (label != null) {
                    labels.put(label, kind);
                }
                tracks.countDown();
            }

            @Override
            public void onError(Throwable error) {
                error.printStackTrace();
            }
        };
    }
}

package dev.sertas.engine;

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.media.video.CustomVideoSource;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Потолки битрейта по трекам: значения по умолчанию, смена на ходу, новые сессии меша. */
class PeerSessionBitrateTest {

    @Test
    void defaultsAndChangeSurviveNegotiation() throws Exception {
        WebRtcEngine engine = WebRtcEngine.headless();
        CustomVideoSource screen = new CustomVideoSource();
        CustomVideoSource camera = new CustomVideoSource();
        PeerSession[] peers = new PeerSession[2];
        CountDownLatch connected = new CountDownLatch(2);
        try {
            peers[0] = new PeerSession(engine, signals(peers, 1, connected));
            peers[1] = new PeerSession(engine, signals(peers, 0, connected));
            peers[0].addTrack(engine.createMicTrack());
            peers[0].addTrack(engine.createVideoTrack(ScreenCaptureSource.LABEL, screen));
            peers[0].addTrack(engine.createVideoTrack(CameraSource.LABEL, camera));

            PeerSession a = peers[0];
            assertEquals(5_000_000, a.maxBitrate(ScreenCaptureSource.LABEL));
            assertEquals(1_500_000, a.maxBitrate(CameraSource.LABEL));
            assertFalse(a.setMaxBitrate("нет-такого", 1_000_000));

            assertTrue(a.setMaxBitrate(ScreenCaptureSource.LABEL, 4_000_000), "до переговорки");
            a.createOffer();
            assertTrue(connected.await(15, TimeUnit.SECONDS), "соединение не установилось");
            assertEquals(4_000_000, a.maxBitrate(ScreenCaptureSource.LABEL), "потолок пережил переговорку");

            assertTrue(a.setMaxBitrate(ScreenCaptureSource.LABEL, 6_000_000), "на ходу");
            assertEquals(6_000_000, a.maxBitrate(ScreenCaptureSource.LABEL));
            assertEquals(1_500_000, a.maxBitrate(CameraSource.LABEL), "камеру не задело");

            a.close();
            assertFalse(a.setMaxBitrate(ScreenCaptureSource.LABEL, 1_000_000), "после close");
            assertNull(a.maxBitrate(ScreenCaptureSource.LABEL));
        } finally {
            for (PeerSession p : peers) {
                if (p != null) {
                    p.close();
                }
            }
            screen.dispose();
            camera.dispose();
            engine.dispose();
        }
    }

    private static PeerSession.Signals signals(PeerSession[] peers, int other, CountDownLatch connected) {
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
            public void onConnectionState(RTCPeerConnectionState state) {
                if (state == RTCPeerConnectionState.CONNECTED) {
                    connected.countDown();
                }
            }

            @Override
            public void onError(Throwable error) {
                error.printStackTrace();
            }
        };
    }
}

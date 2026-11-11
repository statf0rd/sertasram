package dev.sertas.engine;

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class StatsRecorderTest {

    private static final Path HOME = Path.of("/home/u");

    @Test
    void disabledByDefault() {
        assertEquals(Optional.empty(), StatsRecorder.resolveDir(null, null, HOME));
        assertEquals(Optional.empty(), StatsRecorder.resolveDir("off", "on", HOME));
    }

    @Test
    void onMeansDefaultDirectoryAndPropertyBeatsEnv() {
        Path def = HOME.resolve(".sertas").resolve("stats");
        assertEquals(Optional.of(def), StatsRecorder.resolveDir("on", null, HOME));
        assertEquals(Optional.of(def), StatsRecorder.resolveDir(null, "true", HOME));
        assertEquals(Optional.of(Path.of("/tmp/run1")), StatsRecorder.resolveDir("/tmp/run1", "on", HOME));
    }

    /**
     * Реальное соединение на loopback: A шлёт видео камеры, B принимает. В CSV
     * должны попасть отдача у A и приём у B с меткой {@code camera}, FPS и битрейтом.
     */
    @Test
    void recordsLiveVideoStatsToCsv(@TempDir Path dir) throws Exception {
        WebRtcEngine engine = WebRtcEngine.headless();
        CustomVideoSource cameraSource = new CustomVideoSource();
        PeerSession[] peers = new PeerSession[2];
        CountDownLatch connected = new CountDownLatch(2);
        AtomicBoolean pushing = new AtomicBoolean(true);
        Thread pusher = new Thread(() -> pushFrames(cameraSource, pushing), "test-camera-frames");
        Path csv = dir.resolve("stats.csv");
        StatsRecorder recorder = null;

        try {
            peers[0] = new PeerSession(engine, signals(peers, 1, connected));
            peers[1] = new PeerSession(engine, signals(peers, 0, connected));
            VideoTrack camera = engine.createVideoTrack(CameraSource.LABEL, cameraSource);
            peers[0].addTrack(engine.createMicTrack());
            peers[0].addTrack(camera);
            pusher.start();
            peers[0].createOffer();
            assertTrue(connected.await(15, TimeUnit.SECONDS), "соединение не установилось");

            recorder = new StatsRecorder(csv, () -> List.of(
                    new StatsRecorder.Target("main", "b", peers[0]),
                    new StatsRecorder.Target("main", "a", peers[1])), Duration.ofMillis(500));
            Thread.sleep(4000);
        } finally {
            if (recorder != null) {
                recorder.close();
            }
            pushing.set(false);
            pusher.join(2000);
            for (PeerSession p : peers) {
                if (p != null) {
                    p.close();
                }
            }
            cameraSource.dispose();
            engine.dispose();
        }

        List<String> lines = Files.readAllLines(csv);
        System.err.println(String.join("\n", lines));
        assertEquals(StatsSampler.HEADER, lines.get(0));
        List<String[]> rows = lines.stream().skip(1).map(l -> l.split(",", -1)).toList();
        // столбцы: 2 connection, 3 peer, 4 direction, 5 kind, 6 label, 8 fps, 9 width, 11 bitrate_kbps
        assertTrue(rows.stream().anyMatch(r -> r[4].equals("out") && r[5].equals("video")
                        && r[6].equals(CameraSource.LABEL) && positive(r[11])),
                "нет отдачи камеры с битрейтом");
        assertTrue(rows.stream().anyMatch(r -> r[3].equals("a") && r[4].equals("in") && r[5].equals("video")
                        && r[6].equals(CameraSource.LABEL) && positive(r[8]) && r[9].equals("320")
                        && positive(r[11])),
                "нет приёма камеры с FPS и битрейтом: " + rows.stream().map(Arrays::toString).toList());
    }

    private static boolean positive(String v) {
        return !v.isEmpty() && Double.parseDouble(v) > 0;
    }

    /** 30 кадров/с 320x240, как с камеры. */
    private static void pushFrames(CustomVideoSource source, AtomicBoolean pushing) {
        long ts = 0;
        while (pushing.get()) {
            NativeI420Buffer buffer = NativeI420Buffer.allocate(320, 240);
            VideoFrame frame = new VideoFrame(buffer, ts);
            source.pushFrame(frame);
            frame.release();
            ts += 33_333_333L;
            try {
                Thread.sleep(33);
            } catch (InterruptedException e) {
                return;
            }
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

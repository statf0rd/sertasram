package dev.sertas.engine;

import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsType;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import dev.sertas.media.OpusSdpMunger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Битрейт Opus звука демо из пресета реально доходит до кодера: на белом шуме
 * (кодер VBR тратит весь разрешённый битрейт) отдача следует потолку отправителя
 * вниз и вверх, на ходу, без переговорки.
 */
class ScreenAudioBitrateTest {

    @Test
    void opusFollowsPresetBitrate() throws Exception {
        WebRtcEngine engine = WebRtcEngine.headless();
        WebRtcEngine sendEngine = WebRtcEngine.pushOnly();
        PeerSession[] main = new PeerSession[2];
        RTCDataChannel[] ctrl = new RTCDataChannel[2];
        CountDownLatch ctrlReady = new CountDownLatch(2);
        UnaryOperator<String> music = OpusSdpMunger::applyMusicProfile;

        main[0] = new PeerSession(engine, new PeerSession.Signals() {
            @Override public void onLocalDescription(RTCSessionDescription d) { main[1].onRemoteDescription(d); }
            @Override public void onLocalIceCandidate(RTCIceCandidate c) { main[1].onRemoteIceCandidate(c); }
        });
        main[1] = new PeerSession(engine, new PeerSession.Signals() {
            @Override public void onLocalDescription(RTCSessionDescription d) { main[0].onRemoteDescription(d); }
            @Override public void onLocalIceCandidate(RTCIceCandidate c) { main[0].onRemoteIceCandidate(c); }
            @Override public void onDataChannel(RTCDataChannel channel) { ctrl[1] = channel; ctrlReady.countDown(); }
        });
        ctrl[0] = main[0].createDataChannel("control");
        ctrlReady.countDown();
        main[0].createOffer();
        assertTrue(ctrlReady.await(20, TimeUnit.SECONDS), "control-каналы не готовы");

        SystemAudioTrack sat = new SystemAudioTrack(sendEngine);
        ScreenAudioConnection sender = new ScreenAudioConnection(
                sendEngine, engine, ctrl[0], sat.track(), t -> {}, music);
        ScreenAudioConnection viewer = new ScreenAudioConnection(
                sendEngine, engine, ctrl[1], null, t -> {}, music);
        NoiseProvider noise = new NoiseProvider();
        try {
            sender.setMaxBitrate(48_000);
            sat.start(noise);
            Thread.sleep(3000); // переговорка и разгон кодера
            double low = kbps(sender.outgoing(), 3000);

            sender.setMaxBitrate(256_000);
            Thread.sleep(1500);
            double high = kbps(sender.outgoing(), 3000);

            System.err.printf("[test] Opus: потолок 48 кбит/с -> %.0f кбит/с, 256 кбит/с -> %.0f кбит/с%n",
                    low, high);
            assertTrue(low > 20 && low < 70, "потолок 48 кбит/с не соблюдён: " + low);
            assertTrue(high > 150, "битрейт не вырос после смены пресета: " + high);
        } finally {
            sat.stop();
            sender.close();
            viewer.close();
            main[0].close();
            main[1].close();
            engine.dispose();
            sendEngine.dispose();
        }
    }

    /** Средний битрейт отдачи аудио за окно, кбит/с (по bytesSent из getStats). */
    private static double kbps(PeerSession session, long windowMs) throws Exception {
        double[] first = bytesSent(session);
        Thread.sleep(windowMs);
        double[] second = bytesSent(session);
        return (second[0] - first[0]) * 8 / ((second[1] - first[1]) / 1e6) / 1000;
    }

    /** [байт отправлено, время отчёта в мкс]. */
    private static double[] bytesSent(PeerSession session) throws Exception {
        CompletableFuture<double[]> result = new CompletableFuture<>();
        assertTrue(session.requestStats(report -> {
            for (RTCStats s : report.getStats().values()) {
                if (s.getType() == RTCStatsType.OUTBOUND_RTP
                        && "audio".equals(String.valueOf(s.getAttributes().get("kind")))) {
                    Object bytes = s.getAttributes().get("bytesSent");
                    result.complete(new double[]{((Number) bytes).doubleValue(), s.getTimestamp()});
                    return;
                }
            }
            result.completeExceptionally(new AssertionError("нет outbound-rtp аудио"));
        }));
        return result.get(5, TimeUnit.SECONDS);
    }

    /** Стерео белый шум 48 кГц блоками по 10 мс. */
    private static final class NoiseProvider implements SystemAudioProvider {
        private final AtomicBoolean running = new AtomicBoolean();
        private Thread thread;

        @Override
        public void start(PcmSink sink) {
            running.set(true);
            thread = new Thread(() -> {
                Random rnd = new Random(1);
                while (running.get()) {
                    float[] l = new float[480];
                    float[] r = new float[480];
                    for (int i = 0; i < 480; i++) {
                        l[i] = (float) (rnd.nextGaussian() * 0.2);
                        r[i] = (float) (rnd.nextGaussian() * 0.2);
                    }
                    sink.onPcm(l, r, 48_000);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "test-noise");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void stop() {
            running.set(false);
            if (thread != null) {
                thread.interrupt();
            }
        }
    }
}

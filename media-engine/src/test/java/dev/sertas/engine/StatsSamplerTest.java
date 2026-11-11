package dev.sertas.engine;

import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.RTCStatsType;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Разбор отчёта getStats - на синтетических отчётах, без сети. */
class StatsSamplerTest {

    private static final Map<String, String> LOCAL = Map.of("0", "mic", "1", "screen", "2", "camera");
    private static final Map<String, String> REMOTE = Map.of("0", "mic", "1", "screen", "2", "camera");

    private static RTCStats stats(long tsUs, RTCStatsType type, String id, Map<String, Object> attrs) {
        return new RTCStats(tsUs, type, id, attrs) {};
    }

    private static RTCStatsReport report(long tsUs, RTCStats... stats) {
        Map<String, RTCStats> map = new HashMap<>();
        for (RTCStats s : stats) {
            map.put(s.getId(), s);
        }
        return new RTCStatsReport(map, tsUs) {};
    }

    /** Приём камеры: 30 FPS, 1 секунда, +250 000 байт, +10 потерь на 990 пакетов. */
    private static RTCStatsReport inboundCamera(long tsUs, long bytes, long packets, int lost) {
        return report(tsUs,
                stats(tsUs, RTCStatsType.INBOUND_RTP, "IT01V", Map.of(
                        "kind", "video", "mid", "2", "framesPerSecond", 30.0,
                        "frameWidth", 1280L, "frameHeight", 720L,
                        "bytesReceived", BigInteger.valueOf(bytes), "packetsReceived", packets,
                        "packetsLost", lost, "jitter", 0.004)),
                stats(tsUs, RTCStatsType.TRANSPORT, "T01", Map.of("selectedCandidatePairId", "CP1")),
                stats(tsUs, RTCStatsType.CANDIDATE_PAIR, "CP1", Map.of("currentRoundTripTime", 0.012)));
    }

    @Test
    void firstSampleHasNoIntervalValues() {
        List<StatsSampler.Row> rows = new StatsSampler()
                .sample("main", "p1", inboundCamera(1_000_000, 100_000, 1000, 0), LOCAL, REMOTE);

        StatsSampler.Row r = rows.get(0);
        assertEquals("in", r.direction());
        assertEquals("video", r.kind());
        assertEquals("camera", r.label());
        assertEquals(30.0, r.fps());
        assertEquals(1280, r.width());
        assertEquals(4.0, r.jitterMs(), 1e-9);
        assertEquals(12.0, r.rttMs(), 1e-9);
        assertTrue(Double.isNaN(r.bitrateKbps()), "битрейт без прошлого снимка");
        assertTrue(Double.isNaN(r.lossPct()));
    }

    @Test
    void secondSampleComputesBitrateAndIntervalLoss() {
        StatsSampler sampler = new StatsSampler();
        sampler.sample("main", "p1", inboundCamera(1_000_000, 100_000, 1000, 0), LOCAL, REMOTE);

        StatsSampler.Row r = sampler
                .sample("main", "p1", inboundCamera(2_000_000, 350_000, 1990, 10), LOCAL, REMOTE)
                .get(0);

        assertEquals(2000.0, r.bitrateKbps(), 1e-9);  // 250 000 байт * 8 / 1 с
        assertEquals(1.0, r.lossPct(), 1e-9);         // 10 / (10 + 990)
        assertEquals(10, r.packetsLost());
    }

    @Test
    void outboundTakesLossJitterAndRttFromReceiverReport() {
        RTCStatsReport rep = report(1_000_000,
                stats(1_000_000, RTCStatsType.OUTBOUND_RTP, "OT01A", Map.of(
                        "kind", "audio", "mid", "0", "bytesSent", BigInteger.valueOf(5000),
                        "packetsSent", 50L)),
                stats(1_000_000, RTCStatsType.REMOTE_INBOUND_RTP, "RI01A", Map.of(
                        "localId", "OT01A", "packetsLost", 3, "fractionLost", 0.02,
                        "jitter", 0.0015, "roundTripTime", 0.031)));

        StatsSampler.Row r = new StatsSampler().sample("main", "p1", rep, LOCAL, REMOTE).get(0);

        assertEquals("out", r.direction());
        assertEquals("mic", r.label());
        assertEquals(3, r.packetsLost());
        assertEquals(2.0, r.lossPct(), 1e-9);
        assertEquals(1.5, r.jitterMs(), 1e-9);
        assertEquals(31.0, r.rttMs(), 1e-9);
    }

    @Test
    void outboundLabelFallsBackToMediaSourceTrack() {
        RTCStatsReport rep = report(1_000_000,
                stats(1_000_000, RTCStatsType.OUTBOUND_RTP, "OT01V", Map.of(
                        "kind", "video", "mediaSourceId", "SV1")),
                stats(1_000_000, RTCStatsType.MEDIA_SOURCE, "SV1", Map.of("trackIdentifier", "screen")));

        assertEquals("screen", new StatsSampler().sample("main", "p1", rep, Map.of(), Map.of())
                .get(0).label());
    }

    @Test
    void audioConcealmentShareOverInterval() {
        StatsSampler sampler = new StatsSampler();
        sampler.sample("demo-audio-in", "p1", inboundAudio(1_000_000, 0, 48_000), LOCAL, REMOTE);

        StatsSampler.Row r = sampler
                .sample("demo-audio-in", "p1", inboundAudio(2_000_000, 480, 96_000), LOCAL, REMOTE)
                .get(0);

        assertEquals(1.0, r.concealedPct(), 1e-9); // 480 из 48 000 сэмплов
    }

    private static RTCStatsReport inboundAudio(long tsUs, long concealed, long samples) {
        return report(tsUs, stats(tsUs, RTCStatsType.INBOUND_RTP, "IT01A", Map.of(
                "kind", "audio", "mid", "0", "bytesReceived", BigInteger.ZERO,
                "packetsReceived", 0L, "packetsLost", 0,
                "concealedSamples", BigInteger.valueOf(concealed),
                "totalSamplesReceived", BigInteger.valueOf(samples))));
    }

    @Test
    void csvLeavesMissingValuesEmpty() {
        StatsSampler.Row r = new StatsSampler()
                .sample("main", "p1", inboundCamera(1_000_000, 100_000, 1000, 0), LOCAL, REMOTE)
                .get(0);

        String line = r.toCsv("2026-11-11T12:00:00.000", 1.0);

        assertEquals(StatsSampler.HEADER.split(",").length, line.split(",", -1).length);
        assertEquals("2026-11-11T12:00:00.000,1.0,main,p1,in,video,camera,2,30.0,1280,720,,0,,4.0,12.0,",
                line);
    }
}

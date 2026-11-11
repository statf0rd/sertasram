package dev.sertas.engine;

import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.RTCStatsType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Превращает отчёт {@code getStats} в строки: по одной на RTP-поток (приём и
 * отдача, аудио и видео). Счётчики (байты, пакеты, потери) накопительные, поэтому
 * битрейт и доля потерь за интервал считаются как разность с прошлым снимком
 * того же потока; у первого снимка эти поля пустые.
 *
 * <p>Не потокобезопасен: вызывающий сериализует {@link #sample}.
 */
final class StatsSampler {

    /** Заголовок CSV; порядок полей совпадает с {@link Row#toCsv}. */
    static final String HEADER = "timestamp,elapsed_s,connection,peer,direction,kind,label,mid,"
            + "fps,width,height,bitrate_kbps,packets_lost,loss_pct,jitter_ms,rtt_ms,concealed_pct";

    /**
     * Одна строка: состояние потока на момент снимка. Отсутствующее значение -
     * {@link Double#NaN} (в CSV - пустое поле).
     *
     * @param direction    {@code in} - приём, {@code out} - отдача
     * @param label        id трека у отправителя ({@code mic}, {@code screen}, {@code camera}, {@code screen-audio})
     * @param bitrateKbps  за интервал с прошлого снимка
     * @param packetsLost  накопительно; у отдачи - по отчёту получателя (RTCP)
     * @param lossPct      доля потерь за интервал (у отдачи - fractionLost из RTCP)
     * @param jitterMs     у отдачи - по отчёту получателя
     * @param rttMs        у отдачи - RTCP RTT; у приёма - RTT выбранной ICE-пары
     * @param concealedPct доля замаскированных (выдуманных декодером) аудиосэмплов за интервал
     */
    record Row(String connection, String peer, String direction, String kind, String label, String mid,
               double fps, double width, double height, double bitrateKbps, double packetsLost,
               double lossPct, double jitterMs, double rttMs, double concealedPct) {

        String toCsv(String timestamp, double elapsedS) {
            return String.join(",",
                    timestamp, num(elapsedS, 1), csv(connection), csv(peer), direction, csv(kind),
                    csv(label), csv(mid), num(fps, 1), num(width, 0), num(height, 0),
                    num(bitrateKbps, 1), num(packetsLost, 0), num(lossPct, 2), num(jitterMs, 1),
                    num(rttMs, 1), num(concealedPct, 2));
        }
    }

    /** Прошлые значения накопительных счётчиков потока. */
    private record Counters(long timestampUs, double bytes, double packets, double lost,
                            double concealed, double samples) {}

    private final Map<String, Counters> previous = new HashMap<>();

    /**
     * @param localLabels  mid → id своего трека (локальный SDP) - для отдачи
     * @param remoteLabels mid → id трека пира (удалённый SDP) - для приёма
     */
    List<Row> sample(String connection, String peer, RTCStatsReport report,
                     Map<String, String> localLabels, Map<String, String> remoteLabels) {
        Map<String, RTCStats> all = report.getStats();
        Map<String, RTCStats> remoteInboundByLocalId = new HashMap<>();
        for (RTCStats s : all.values()) {
            if (s.getType() == RTCStatsType.REMOTE_INBOUND_RTP) {
                String localId = str(s, "localId");
                if (localId != null) {
                    remoteInboundByLocalId.put(localId, s);
                }
            }
        }
        double transportRttMs = selectedPairRttMs(all);

        List<Row> rows = new ArrayList<>();
        for (RTCStats s : all.values()) {
            boolean in = s.getType() == RTCStatsType.INBOUND_RTP;
            boolean out = s.getType() == RTCStatsType.OUTBOUND_RTP;
            if (!in && !out) {
                continue;
            }
            String kind = str(s, "kind") != null ? str(s, "kind") : str(s, "mediaType");
            String mid = str(s, "mid");
            String label = mid == null ? null : (in ? remoteLabels : localLabels).get(mid);
            if (label == null && out) {
                label = mediaSourceTrack(all, s); // у отдачи id своего трека есть и в media-source
            }

            double bytes = num(s, in ? "bytesReceived" : "bytesSent");
            double packets = num(s, in ? "packetsReceived" : "packetsSent");
            double lost = in ? num(s, "packetsLost") : Double.NaN;
            double concealed = num(s, "concealedSamples");
            double samples = num(s, "totalSamplesReceived");

            String key = connection + '|' + peer + '|' + s.getId();
            Counters now = new Counters(s.getTimestamp(), bytes, packets, lost, concealed, samples);
            Counters before = previous.put(key, now);

            double bitrate = Double.NaN;
            double lossPct = Double.NaN;
            double concealedPct = Double.NaN;
            if (before != null && now.timestampUs > before.timestampUs) {
                double dtUs = now.timestampUs - before.timestampUs;
                // бит/мкс = Мбит/с; x1000 = кбит/с
                bitrate = (bytes - before.bytes) * 8 / dtUs * 1000;
                if (in) {
                    double dLost = lost - before.lost;
                    double dRecv = packets - before.packets;
                    if (dLost + dRecv > 0) {
                        lossPct = Math.max(0, dLost) / (dLost + dRecv) * 100;
                    }
                    double dSamples = samples - before.samples;
                    if (dSamples > 0) {
                        concealedPct = (concealed - before.concealed) / dSamples * 100;
                    }
                }
            }

            double jitterMs;
            double rttMs;
            double packetsLost;
            if (in) {
                jitterMs = num(s, "jitter") * 1000;
                rttMs = transportRttMs;
                packetsLost = lost;
            } else {
                RTCStats rr = remoteInboundByLocalId.get(s.getId());
                jitterMs = rr == null ? Double.NaN : num(rr, "jitter") * 1000;
                rttMs = rr == null ? Double.NaN : num(rr, "roundTripTime") * 1000;
                packetsLost = rr == null ? Double.NaN : num(rr, "packetsLost");
                lossPct = rr == null ? Double.NaN : num(rr, "fractionLost") * 100;
            }

            rows.add(new Row(connection, peer, in ? "in" : "out", kind, label, mid,
                    num(s, "framesPerSecond"), num(s, "frameWidth"), num(s, "frameHeight"),
                    bitrate, packetsLost, lossPct, jitterMs, rttMs, concealedPct));
        }
        rows.sort((a, b) -> (a.direction + a.kind + a.label).compareTo(b.direction + b.kind + b.label));
        return rows;
    }

    /** RTT выбранной ICE-пары (транспорт → selectedCandidatePairId), мс. */
    private static double selectedPairRttMs(Map<String, RTCStats> all) {
        for (RTCStats s : all.values()) {
            if (s.getType() == RTCStatsType.TRANSPORT) {
                String pairId = str(s, "selectedCandidatePairId");
                RTCStats pair = pairId == null ? null : all.get(pairId);
                if (pair != null) {
                    return num(pair, "currentRoundTripTime") * 1000;
                }
            }
        }
        return Double.NaN;
    }

    private static String mediaSourceTrack(Map<String, RTCStats> all, RTCStats outbound) {
        String sourceId = str(outbound, "mediaSourceId");
        RTCStats source = sourceId == null ? null : all.get(sourceId);
        return source == null ? null : str(source, "trackIdentifier");
    }

    private static String str(RTCStats s, String key) {
        Object v = s.getAttributes().get(key);
        return v == null ? null : v.toString();
    }

    /** Число из атрибута (Integer/Long/BigInteger/Double); иначе NaN. */
    private static double num(RTCStats s, String key) {
        return s.getAttributes().get(key) instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private static String num(double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "";
        }
        return decimals == 0
                ? Long.toString(Math.round(v))
                : String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        return v.contains(",") || v.contains("\"") || v.contains("\n")
                ? '"' + v.replace("\"", "\"\"") + '"'
                : v;
    }
}

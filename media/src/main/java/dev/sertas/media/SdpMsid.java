package dev.sertas.media;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Читает {@code a=msid} по m-секциям SDP: {@code mid → id трека отправителя}
 * ({@code mic}, {@code screen}, {@code camera}...).
 *
 * <p>Нужен, чтобы на приёме отличить экран от камеры: id удалённого трека в
 * libwebrtc не всегда совпадает с id трека отправителя. Если трансивер создан
 * локальным {@code addTrack} до переговорки (так делает меш на обеих сторонах),
 * его receiver-трек получает случайный id, и метку можно узнать только из SDP
 * другой стороны.
 */
public final class SdpMsid {

    private SdpMsid() {}

    private static final Pattern SECTION_START = Pattern.compile("(?m)^(?=m=)");
    private static final Pattern MID = Pattern.compile("(?m)^a=mid:(\\S+)");
    private static final Pattern MSID = Pattern.compile("(?m)^a=msid:\\S+\\s+(\\S+)");

    /**
     * @return {@code mid → id трека} для секций, где есть и {@code a=mid}, и
     *         {@code a=msid} с id трека; пустая карта для {@code null}/пустого SDP
     */
    public static Map<String, String> trackIdsByMid(String sdp) {
        Map<String, String> out = new LinkedHashMap<>();
        if (sdp == null || sdp.isEmpty()) {
            return out;
        }
        for (String section : SECTION_START.split(sdp)) {
            if (!section.startsWith("m=")) {
                continue; // сессионная часть до первой m-строки
            }
            Matcher mid = MID.matcher(section);
            Matcher msid = MSID.matcher(section);
            if (mid.find() && msid.find()) {
                out.put(mid.group(1), msid.group(1));
            }
        }
        return out;
    }
}

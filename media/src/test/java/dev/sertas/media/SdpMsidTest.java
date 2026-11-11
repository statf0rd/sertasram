package dev.sertas.media;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SdpMsidTest {

    private static final String ANSWER = String.join("\r\n",
            "v=0",
            "o=- 1 2 IN IP4 127.0.0.1",
            "s=-",
            "t=0 0",
            "a=group:BUNDLE 0 1 2 3",
            "a=msid-semantic: WMS sertas",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=mid:0",
            "a=sendrecv",
            "a=msid:sertas mic",
            "a=rtpmap:111 opus/48000/2",
            "m=video 9 UDP/TLS/RTP/SAVPF 96",
            "a=mid:1",
            "a=sendrecv",
            "a=msid:sertas screen",
            "a=rtpmap:96 VP8/90000",
            "m=video 9 UDP/TLS/RTP/SAVPF 96",
            "a=mid:2",
            "a=sendrecv",
            "a=msid:sertas camera",
            "a=rtpmap:96 VP8/90000",
            "m=application 9 UDP/DTLS/SCTP webrtc-datachannel",
            "a=mid:3",
            "");

    @Test
    void mapsEachMediaSectionMidToSenderTrackId() {
        Map<String, String> ids = SdpMsid.trackIdsByMid(ANSWER);

        assertEquals(Map.of("0", "mic", "1", "screen", "2", "camera"), ids);
    }

    @Test
    void skipsSectionsWithoutTrackId() {
        String recvOnly = String.join("\r\n",
                "v=0",
                "m=video 9 UDP/TLS/RTP/SAVPF 96",
                "a=mid:0",
                "a=recvonly",
                "m=video 9 UDP/TLS/RTP/SAVPF 96",
                "a=mid:1",
                "a=msid:sertas",
                "");

        assertTrue(SdpMsid.trackIdsByMid(recvOnly).isEmpty());
    }

    @Test
    void emptyForMissingSdp() {
        assertTrue(SdpMsid.trackIdsByMid(null).isEmpty());
        assertTrue(SdpMsid.trackIdsByMid("").isEmpty());
    }
}

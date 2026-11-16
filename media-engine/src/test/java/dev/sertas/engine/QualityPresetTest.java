package dev.sertas.engine;

import dev.sertas.engine.ScreenCaptureSource.Quality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QualityPresetTest {

    @Test
    void defaultPresetKeepsPreviousBehaviour() {
        // До пресетов: захват 1080p30, видео экрана до 5 Мбит/с, Opus 192 кбит/с
        // (maxaveragebitrate в OpusSdpMunger).
        Quality q = Quality.DEFAULT;
        assertEquals(Quality.BALANCED, q);
        assertEquals(1920, q.maxWidth());
        assertEquals(1080, q.maxHeight());
        assertEquals(30, q.fps());
        assertEquals(5_000_000, q.videoMaxBitrate());
        assertEquals(192_000, q.opusBitrate());
    }

    @Test
    void presetsAreOrderedByBandwidthAndOpusStaysInCodecRange() {
        Quality[] all = Quality.values();
        for (int i = 1; i < all.length; i++) {
            assertTrue(all[i].videoMaxBitrate() > all[i - 1].videoMaxBitrate(), all[i] + " видео");
            assertTrue(all[i].opusBitrate() > all[i - 1].opusBitrate(), all[i] + " Opus");
        }
        for (Quality q : all) {
            assertTrue(q.opusBitrate() >= 6_000 && q.opusBitrate() <= 510_000, q + ": вне диапазона Opus");
        }
    }
}

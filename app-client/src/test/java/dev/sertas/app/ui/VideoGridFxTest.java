package dev.sertas.app.ui;

import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.sertas.engine.WebRtcEngine;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Node;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * Сетка на настоящих плитках: кадры идут - плитки видны и разложены (экран
 * слева, камера справа, своё превью зеркально); камеру выключили - экран на всю
 * ширину; участник вышел - его плитки убраны.
 */
class VideoGridFxTest {

    private static final double WIDTH = 1000;

    @Test
    void laysOutLiveTilesAndDropsStoppedOnes() throws Exception {
        startJavaFx();
        WebRtcEngine engine = WebRtcEngine.headless();
        CustomVideoSource screenSrc = new CustomVideoSource();
        CustomVideoSource cameraSrc = new CustomVideoSource();
        try {
            VideoTrack screenTrack = engine.createVideoTrack("screen", screenSrc);
            VideoTrack cameraTrack = engine.createVideoTrack("camera", cameraSrc);
            VideoGrid grid = fx(VideoGrid::new);
            VideoTile screen = fx(() -> new VideoTile(screenTrack));
            VideoTile camera = fx(() -> new VideoTile(cameraTrack, true));
            fx(() -> {
                grid.put("p1:screen", screen, VideoGrid.Kind.SCREEN, new SimpleStringProperty("Петя - экран"));
                grid.put("self:camera", camera, VideoGrid.Kind.CAMERA, new SimpleStringProperty("Вы"));
                return null;
            });

            screen.onFrame(frame(1920, 1080));
            camera.onFrame(frame(1280, 720));
            waitFor(() -> screen.activeProperty().get() && camera.activeProperty().get(), "плитки не ожили");

            Node screenCell = fx(() -> layout(grid).getChildren().get(0));
            Node cameraCell = fx(() -> grid.getChildren().get(1));
            double inner = WIDTH - 24; // отступы сетки по 12
            double side = inner * VideoGrid.SIDE_SHARE;
            assertEquals(12, screenCell.getLayoutX(), 1);
            assertEquals(inner - side - VideoGrid.GAP, screenCell.getLayoutBounds().getWidth(), 1);
            assertEquals(12 + inner - side, cameraCell.getLayoutX(), 1, "камера справа от экрана");
            assertEquals(side * 9 / 16, cameraCell.getLayoutBounds().getHeight(), 1);
            assertEquals(-1, camera.node().getScaleX(), "своё превью зеркально");
            assertEquals(1, screen.node().getScaleX());

            camera.clear(); // камеру выключили
            waitFor(() -> !camera.activeProperty().get(), "превью не скрылось");
            fx(() -> layout(grid));
            assertFalse(cameraCell.isVisible());
            assertEquals(inner, screenCell.getLayoutBounds().getWidth(), 1, "экран на всю ширину");

            fx(() -> {
                grid.removeByPrefix("p1:");
                return null;
            });
            assertEquals(1, (int) fx(grid::size), "осталось только своё превью");
            fx(() -> {
                grid.clear();
                return null;
            });
            assertEquals(0, (int) fx(grid::size));
        } finally {
            screenSrc.dispose();
            cameraSrc.dispose();
            engine.dispose();
        }
    }

    private static VideoGrid layout(VideoGrid grid) {
        grid.resize(WIDTH, 800);
        grid.layout();
        return grid;
    }

    private static VideoFrame frame(int w, int h) {
        return new VideoFrame(NativeI420Buffer.allocate(w, h), 0L);
    }

    private static <T> T fx(Callable<T> body) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(body.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(10, TimeUnit.SECONDS);
    }

    /** Ждать условия на FX-потоке (плитки обновляются таймером анимации). */
    private static void waitFor(Callable<Boolean> condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (fx(condition)) {
                return;
            }
            Thread.sleep(20);
        }
        fail(message);
    }

    private static void startJavaFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyRunning) {
            ready.countDown();
        }
        assumeTrue(ready.await(20, TimeUnit.SECONDS), "JavaFX toolkit не поднялся");
        Platform.setImplicitExit(false);
    }
}

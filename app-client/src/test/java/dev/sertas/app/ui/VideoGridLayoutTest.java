package dev.sertas.app.ui;

import dev.sertas.app.ui.VideoGrid.Item;
import dev.sertas.app.ui.VideoGrid.Kind;
import dev.sertas.app.ui.VideoGrid.Placement;
import dev.sertas.app.ui.VideoGrid.Rect;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Раскладка плиток: чистая геометрия, без JavaFX. */
class VideoGridLayoutTest {

    private static final double W = 1000;
    private static final double EPS = 1e-9;
    private static final Item SCREEN = new Item(Kind.SCREEN, 16.0 / 9);
    private static final Item CAM = new Item(Kind.CAMERA, 16.0 / 9);

    @Test
    void screenTakesTheRestAndCamerasStackOnTheRight() {
        Placement p = VideoGrid.place(List.of(CAM, SCREEN, CAM), W);

        Rect screen = p.rects().get(1);
        double side = W * VideoGrid.SIDE_SHARE; // 250: в пределах 160..320
        assertEquals(0, screen.x(), EPS);
        assertEquals(W - side - VideoGrid.GAP, screen.w(), EPS);
        assertEquals(screen.w() * 9 / 16, screen.h(), EPS);

        Rect cam1 = p.rects().get(0);
        Rect cam2 = p.rects().get(2);
        assertEquals(screen.w() + VideoGrid.GAP, cam1.x(), EPS);
        assertEquals(side, cam1.w(), EPS);
        assertEquals(0, cam1.y(), EPS);
        assertEquals(cam1.h() + VideoGrid.GAP, cam2.y(), EPS, "камеры одна под другой");
        assertEquals(Math.max(screen.h(), cam2.y() + cam2.h()), p.height(), EPS);
    }

    @Test
    void severalScreensStackAndColumnWidthIsClamped() {
        Placement p = VideoGrid.place(List.of(SCREEN, SCREEN, CAM), 2000);

        assertEquals(VideoGrid.SIDE_MAX, p.rects().get(2).w(), EPS, "колонка не шире максимума");
        Rect first = p.rects().get(0);
        Rect second = p.rects().get(1);
        assertEquals(first.w(), second.w(), EPS);
        assertEquals(first.h() + VideoGrid.GAP, second.y(), EPS);
    }

    @Test
    void withoutScreensCamerasFormACenteredGrid() {
        Placement one = VideoGrid.place(List.of(CAM), W);
        Rect only = one.rects().get(0);
        assertEquals(VideoGrid.CAMERA_MAX, only.w(), EPS);
        assertEquals((W - VideoGrid.CAMERA_MAX) / 2, only.x(), EPS, "по центру");

        Placement three = VideoGrid.place(List.of(CAM, CAM, CAM), W);
        double w = (W - VideoGrid.GAP) / 2;
        assertEquals(w, three.rects().get(0).w(), EPS, "два в ряд");
        assertEquals(three.rects().get(0).y(), three.rects().get(1).y(), EPS);
        assertEquals(three.rects().get(0).h() + VideoGrid.GAP, three.rects().get(2).y(), EPS, "третья - во втором ряду");
        assertEquals(2 * three.rects().get(0).h() + VideoGrid.GAP, three.height(), EPS);
    }

    @Test
    void narrowWindowPutsCamerasUnderTheScreen() {
        double narrow = VideoGrid.NARROW - 1;
        Placement p = VideoGrid.place(List.of(SCREEN, CAM), narrow);

        Rect screen = p.rects().get(0);
        Rect cam = p.rects().get(1);
        assertEquals(narrow, screen.w(), EPS, "демонстрация на всю ширину");
        assertEquals(screen.h() + VideoGrid.GAP, cam.y(), EPS, "камера под ней");
    }

    @Test
    void portraitCameraAndBrokenAspectStayReasonable() {
        Placement p = VideoGrid.place(List.of(new Item(Kind.CAMERA, 9.0 / 16), new Item(Kind.CAMERA, Double.NaN)), W);

        Rect portrait = p.rects().get(0);
        Rect broken = p.rects().get(1);
        assertEquals(portrait.w() * 16 / 9, portrait.h(), EPS);
        assertEquals(broken.w() * 9 / 16, broken.h(), EPS, "битые пропорции -> 16:9");
        assertEquals(portrait.h(), p.height(), EPS, "высота ряда - по самой высокой плитке");
    }

    @Test
    void emptyOrZeroWidth() {
        assertEquals(0, VideoGrid.place(List.of(), W).height(), EPS);
        assertEquals(0, VideoGrid.place(List.of(SCREEN), 0).height(), EPS);
    }
}

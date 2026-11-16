package dev.sertas.app.ui;

import javafx.beans.InvalidationListener;
import javafx.beans.value.ObservableStringValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Раскладка видео звонка. Пока кто-то показывает экран, демонстрации идут крупно
 * слева, а камеры участников (и своё превью) - колонкой справа. Без демонстраций
 * камеры раскладываются сеткой. Показываются только плитки, по которым идут кадры
 * ({@link VideoTile#activeProperty()}); выключил камеру или показ - плитка уходит.
 *
 * <p>Минимальная ширина нулевая: ширину задаёт окно, а не плитки (иначе плитки,
 * привязанные к ширине контейнера, не дали бы окну сузиться). Минимальная высота -
 * вся раскладка, чтобы {@code ScrollPane} прокручивал её по вертикали.
 *
 * <p>Владеет плитками: заменённые и удалённые плитки освобождает сама. Только FX-поток.
 */
public final class VideoGrid extends Pane {

    /** Что показывает плитка: демонстрация экрана крупно, камера - мелко. */
    public enum Kind { SCREEN, CAMERA }

    static final double GAP = 8;
    /** Колонка камер рядом с демонстрацией: доля ширины, но в этих пределах. */
    static final double SIDE_SHARE = 0.25;
    static final double SIDE_MIN = 160;
    static final double SIDE_MAX = 320;
    /** Уже этого колонка не помещается: камеры уходят сеткой под демонстрацию. */
    static final double NARROW = 560;
    /** Камера в сетке без демонстраций не шире этого. */
    static final double CAMERA_MAX = 640;

    /** Плитка для раскладки: вид и пропорции кадра (ширина / высота). */
    record Item(Kind kind, double aspect) {}

    record Rect(double x, double y, double w, double h) {}

    /** Прямоугольники в порядке плиток и общая высота раскладки. */
    record Placement(List<Rect> rects, double height) {}

    private final class Cell {
        final VideoTile tile;
        final Kind kind;
        final StackPane node;

        Cell(VideoTile tile, Kind kind, ObservableStringValue caption) {
            this.tile = tile;
            this.kind = kind;
            Label label = new Label();
            label.textProperty().bind(caption);
            label.setStyle("-fx-background-color: rgba(0,0,0,0.55); -fx-text-fill: white;"
                    + " -fx-padding: 2 6; -fx-background-radius: 4;");
            StackPane.setAlignment(label, Pos.BOTTOM_LEFT);
            StackPane.setMargin(label, new Insets(6));
            node = new StackPane(tile.node(), label);
            node.setStyle("-fx-background-color: black;");
            node.visibleProperty().bind(tile.activeProperty());
            node.managedProperty().bind(tile.activeProperty());
            tile.activeProperty().addListener(relayout);
            tile.aspectProperty().addListener(relayout);
        }

        void dispose() {
            getChildren().remove(node);
            tile.activeProperty().removeListener(relayout);
            tile.aspectProperty().removeListener(relayout);
            tile.dispose();
        }
    }

    private final Map<String, Cell> cells = new LinkedHashMap<>();
    private final InvalidationListener relayout = o -> requestLayout();

    public VideoGrid() {
        setPadding(new Insets(12));
    }

    /** Добавить плитку под ключом; прежняя плитка с тем же ключом освобождается. */
    public void put(String key, VideoTile tile, Kind kind, ObservableStringValue caption) {
        Cell old = cells.remove(key);
        if (old != null) {
            old.dispose();
        }
        Cell cell = new Cell(tile, kind, caption);
        cells.put(key, cell);
        getChildren().add(cell.node);
    }

    /** Убрать и освободить плитку; {@code false} - такой не было. */
    public boolean remove(String key) {
        Cell cell = cells.remove(key);
        if (cell == null) {
            return false;
        }
        cell.dispose();
        return true;
    }

    /** Убрать все плитки с ключом, начинающимся с {@code prefix} (участник вышел). */
    public void removeByPrefix(String prefix) {
        Iterator<Map.Entry<String, Cell>> it = cells.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Cell> e = it.next();
            if (e.getKey().startsWith(prefix)) {
                it.remove();
                e.getValue().dispose();
            }
        }
    }

    /** Плитка под ключом ({@code null} - нет). */
    public VideoTile tile(String key) {
        Cell cell = cells.get(key);
        return cell == null ? null : cell.tile;
    }

    public int size() {
        return cells.size();
    }

    /** Убрать и освободить все плитки. */
    public void clear() {
        List<Cell> all = new ArrayList<>(cells.values());
        cells.clear();
        all.forEach(Cell::dispose);
    }

    private List<Cell> activeCells() {
        List<Cell> active = new ArrayList<>();
        for (Cell c : cells.values()) {
            if (c.tile.activeProperty().get()) {
                active.add(c);
            }
        }
        return active;
    }

    private static List<Item> items(List<Cell> active) {
        List<Item> items = new ArrayList<>(active.size());
        for (Cell c : active) {
            items.add(new Item(c.kind, c.tile.aspectProperty().get()));
        }
        return items;
    }

    @Override
    protected void layoutChildren() {
        List<Cell> active = activeCells();
        double left = snappedLeftInset();
        double top = snappedTopInset();
        Placement p = place(items(active), getWidth() - left - snappedRightInset());
        for (int i = 0; i < active.size(); i++) {
            Rect r = p.rects().get(i);
            Cell c = active.get(i);
            c.tile.node().setFitWidth(r.w());
            c.node.resizeRelocate(snapPositionX(left + r.x()), snapPositionY(top + r.y()), r.w(), r.h());
        }
    }

    @Override
    protected double computeMinWidth(double height) {
        return snappedLeftInset() + snappedRightInset();
    }

    @Override
    protected double computePrefWidth(double height) {
        return CAMERA_MAX + snappedLeftInset() + snappedRightInset();
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected double computePrefHeight(double width) {
        double w = width < 0 ? getWidth() : width;
        double inner = w - snappedLeftInset() - snappedRightInset();
        return place(items(activeCells()), inner).height() + snappedTopInset() + snappedBottomInset();
    }

    /**
     * Раскладка плиток в полосе ширины {@code width} (без отступов). Чистая
     * функция - проверяется без JavaFX.
     */
    static Placement place(List<Item> items, double width) {
        int n = items.size();
        Rect[] rects = new Rect[n];
        if (n == 0 || width <= 0) {
            for (int i = 0; i < n; i++) {
                rects[i] = new Rect(0, 0, 0, 0);
            }
            return new Placement(List.of(rects), 0);
        }
        List<Integer> screens = new ArrayList<>();
        List<Integer> cameras = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            (items.get(i).kind() == Kind.SCREEN ? screens : cameras).add(i);
        }
        if (screens.isEmpty()) {
            double height = grid(cameras, items, rects, 0, width); // заполняет rects
            return new Placement(List.of(rects), height);
        }

        boolean side = !cameras.isEmpty() && width >= NARROW;
        double sideW = side ? Math.max(SIDE_MIN, Math.min(SIDE_MAX, width * SIDE_SHARE)) : 0;
        double mainW = side ? width - sideW - GAP : width;
        double y = 0;
        for (int i : screens) {
            double h = mainW / aspect(items.get(i));
            rects[i] = new Rect(0, y, mainW, h);
            y += h + GAP;
        }
        double height = y - GAP;
        if (side) {
            double yc = 0;
            for (int i : cameras) {
                double h = sideW / aspect(items.get(i));
                rects[i] = new Rect(mainW + GAP, yc, sideW, h);
                yc += h + GAP;
            }
            height = Math.max(height, yc - GAP);
        } else if (!cameras.isEmpty()) {
            height += GAP + grid(cameras, items, rects, height + GAP, width);
        }
        return new Placement(List.of(rects), height);
    }

    /** Камеры сеткой (1, 2 или 3 в ряд) от {@code top}; возвращает высоту сетки. */
    private static double grid(List<Integer> idx, List<Item> items, Rect[] rects, double top, double width) {
        int n = idx.size();
        if (n == 0) {
            return 0;
        }
        int cols = n == 1 ? 1 : n <= 4 ? 2 : 3;
        double w = Math.max(1, Math.min(CAMERA_MAX, (width - (cols - 1) * GAP) / cols));
        double x0 = Math.max(0, (width - (cols * w + (cols - 1) * GAP)) / 2);
        double y = top;
        for (int row = 0; row * cols < n; row++) {
            double rowH = 0;
            for (int k = 0; k < cols && row * cols + k < n; k++) {
                int i = idx.get(row * cols + k);
                double h = w / aspect(items.get(i));
                rects[i] = new Rect(x0 + k * (w + GAP), y, w, h);
                rowH = Math.max(rowH, h);
            }
            y += rowH + GAP;
        }
        return y - GAP - top;
    }

    /** Пропорции кадра в разумных пределах (битый размер не должен ломать раскладку). */
    private static double aspect(Item item) {
        double a = item.aspect();
        return Double.isFinite(a) && a > 0 ? Math.max(0.3, Math.min(4, a)) : 16.0 / 9;
    }
}

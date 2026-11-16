package dev.sertas.app.ui;

import dev.onvoid.webrtc.media.video.VideoDevice;
import dev.sertas.engine.ScreenCaptureSource.Quality;
import javafx.beans.property.DoubleProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Locale;
import java.util.function.Function;

/**
 * Экран активного звонка: видео-плитки, список участников, mute, камера с
 * выбором устройства, демонстрация с выбором пресета качества, выход.
 */
public final class CallView {

    private final BorderPane root = new BorderPane();
    private final ToggleButton mute = new ToggleButton("Микрофон вкл");
    private final ToggleButton camera = new ToggleButton("Камера");
    private final ComboBox<VideoDevice> cameraChoice = new ComboBox<>();
    private final ToggleButton share = new ToggleButton("Демонстрация");
    private final ComboBox<Quality> qualityChoice = new ComboBox<>();
    private final ToggleButton screenAudio = new ToggleButton("Звук демонстрации");
    private final Button leave = new Button("Выйти");
    private final Label status = new Label(); // последняя ошибка (красным)

    public CallView(String room,
                    ObservableList<ParticipantModel> participants,
                    Region videoPane) {
        Label header = new Label("Комната: " + room);

        ScrollPane videoScroll = new ScrollPane(videoPane);
        videoScroll.setFitToWidth(true);
        videoScroll.setFitToHeight(true);
        videoScroll.setStyle("-fx-background-color: transparent;");

        TableView<ParticipantModel> table = new TableView<>();
        TableColumn<ParticipantModel, String> nameCol = new TableColumn<>("Участник");
        nameCol.setCellValueFactory(c -> c.getValue().nameProperty());
        nameCol.setPrefWidth(240);
        TableColumn<ParticipantModel, String> stateCol = new TableColumn<>("Статус");
        stateCol.setCellValueFactory(c -> c.getValue().stateProperty());
        stateCol.setPrefWidth(150);
        table.getColumns().add(nameCol);
        table.getColumns().add(stateCol);
        // Слайдеры громкости источников показываем только при включённом микшере
        // (-Dsertas.mixer=on) — иначе они были бы инертны (см. CallController).
        if ("on".equalsIgnoreCase(System.getProperty("sertas.mixer", "off"))) {
            table.getColumns().add(gainColumn("Голос", ParticipantModel::voiceGainProperty));
            table.getColumns().add(gainColumn("Демка", ParticipantModel::demoGainProperty));
        }
        table.setItems(participants);
        table.setPlaceholder(new Label("Ожидание участников…"));
        table.setPrefHeight(160);
        table.setMaxHeight(160);

        VBox center = new VBox(8, videoScroll, table);
        VBox.setVgrow(videoScroll, Priority.ALWAYS);

        cameraChoice.setPromptText("Камера не найдена");
        cameraChoice.setButtonCell(new DeviceCell());
        cameraChoice.setCellFactory(l -> new DeviceCell());
        cameraChoice.setPrefWidth(200);
        cameraChoice.setTooltip(new Tooltip("Какую камеру включать; можно сменить и во время звонка"));

        qualityChoice.getItems().setAll(Quality.values());
        qualityChoice.setButtonCell(new QualityCell());
        qualityChoice.setCellFactory(l -> new QualityCell());
        qualityChoice.setPrefWidth(230);

        // Кнопку звука демо показываем только если фича включена (-Dsertas.demoaudio=on);
        // по умолчанию выключена — на реальном ADM она роняет приложение (см. CallController).
        FlowPane controls = new FlowPane(10, 8, mute, camera, cameraChoice, share, qualityChoice);
        if ("on".equalsIgnoreCase(System.getProperty("sertas.demoaudio", "off"))) {
            controls.getChildren().add(screenAudio);
        }
        controls.getChildren().add(leave);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(10));

        status.setWrapText(true);
        status.setStyle("-fx-text-fill: #c62828;");
        VBox top = new VBox(4, header, status);
        top.setPadding(new Insets(10));

        root.setTop(top);
        root.setCenter(center);
        root.setBottom(controls);
    }

    /** Столбец-слайдер громкости источника (0..150%), двусторонне связан со свойством модели. */
    private static TableColumn<ParticipantModel, Void> gainColumn(
            String title, Function<ParticipantModel, DoubleProperty> prop) {
        TableColumn<ParticipantModel, Void> col = new TableColumn<>(title);
        col.setPrefWidth(160);
        col.setSortable(false);
        col.setCellFactory(c -> new TableCell<>() {
            private final Slider slider = new Slider(0, 1.5, 1.0);
            private DoubleProperty bound;

            {
                slider.setBlockIncrement(0.1);
                slider.setMaxWidth(Double.MAX_VALUE);
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                ParticipantModel m = empty || getTableRow() == null ? null : getTableRow().getItem();
                if (bound != null) {
                    slider.valueProperty().unbindBidirectional(bound);
                    bound = null;
                }
                // У себя нет удалённого источника — слайдер был бы инертным, прячем.
                if (m == null || "self".equals(m.id())) {
                    setGraphic(null);
                    return;
                }
                bound = prop.apply(m);
                slider.valueProperty().bindBidirectional(bound);
                setGraphic(slider);
            }
        });
        return col;
    }

    /** Подпись пресета в списке: название и режим захвата. */
    public static String qualityLabel(Quality q) {
        String name = switch (q) {
            case ECONOMY -> "Экономный";
            case SMOOTH -> "Плавный";
            case BALANCED -> "Сбалансированный";
            case CRISP -> "Чёткий";
        };
        return name + " - " + q.maxHeight() + "p" + q.fps();
    }

    /** Подсказка к пресету: для чего он и сколько полосы берёт. */
    public static String qualityHint(Quality q) {
        String use = switch (q) {
            case ECONOMY -> "для слабой сети";
            case SMOOTH -> "для игр и видео";
            case BALANCED -> "по умолчанию";
            case CRISP -> "для текста, кода и слайдов";
        };
        return String.format(Locale.ROOT, "%s: видео до %.1f Мбит/с, звук демонстрации %d кбит/с",
                use, q.videoMaxBitrate() / 1e6, q.opusBitrate() / 1000).replace('.', ',');
    }

    private static final class QualityCell extends ListCell<Quality> {
        @Override
        protected void updateItem(Quality q, boolean empty) {
            super.updateItem(q, empty);
            setText(empty || q == null ? null : qualityLabel(q));
            setTooltip(empty || q == null ? null : new Tooltip(qualityHint(q)));
        }
    }

    private static final class DeviceCell extends ListCell<VideoDevice> {
        @Override
        protected void updateItem(VideoDevice d, boolean empty) {
            super.updateItem(d, empty);
            setText(empty || d == null ? null : d.getName());
        }
    }

    /** Показать ошибку под заголовком (пустая строка — скрыть). */
    public void showError(String message) {
        status.setText(message);
    }

    public Parent getRoot() {
        return root;
    }

    public ToggleButton muteButton() {
        return mute;
    }

    public ToggleButton cameraButton() {
        return camera;
    }

    public ComboBox<VideoDevice> cameraChoice() {
        return cameraChoice;
    }

    public ToggleButton shareButton() {
        return share;
    }

    public ComboBox<Quality> qualityChoice() {
        return qualityChoice;
    }

    public ToggleButton screenAudioButton() {
        return screenAudio;
    }

    public Button leaveButton() {
        return leave;
    }
}

package dev.sertas.app.ui;

import javafx.beans.property.DoubleProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.Function;

/** Экран активного звонка: видео-плитки, список участников, mute, камера, демонстрация, выход. */
public final class CallView {

    private final BorderPane root = new BorderPane();
    private final ToggleButton mute = new ToggleButton("Микрофон вкл");
    private final ToggleButton camera = new ToggleButton("Камера");
    private final ToggleButton share = new ToggleButton("Демонстрация");
    private final ToggleButton screenAudio = new ToggleButton("Звук демонстрации");
    private final Button leave = new Button("Выйти");
    private final Label status = new Label(); // последняя ошибка (красным)

    public CallView(String room,
                    ObservableList<ParticipantModel> participants,
                    FlowPane videoPane) {
        Label header = new Label("Комната: " + room);

        ScrollPane videoScroll = new ScrollPane(videoPane);
        videoScroll.setFitToWidth(true);
        videoScroll.setFitToHeight(true);

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

        // Кнопку звука демо показываем только если фича включена (-Dsertas.demoaudio=on);
        // по умолчанию выключена — на реальном ADM она роняет приложение (см. CallController).
        HBox controls = "on".equalsIgnoreCase(System.getProperty("sertas.demoaudio", "off"))
                ? new HBox(10, mute, camera, share, screenAudio, leave)
                : new HBox(10, mute, camera, share, leave);
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

    public ToggleButton shareButton() {
        return share;
    }

    public ToggleButton screenAudioButton() {
        return screenAudio;
    }

    public Button leaveButton() {
        return leave;
    }
}

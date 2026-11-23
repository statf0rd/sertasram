package dev.sertas.app;

import dev.onvoid.webrtc.media.video.VideoDevice;
import dev.sertas.app.ui.CallView;
import dev.sertas.app.ui.JoinView;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Точка входа JavaFX: экран входа → экран звонка. */
public class SertasApp extends Application {

    private final CallController controller = new CallController();
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("stars");
        showJoin();
        stage.show();
    }

    private void showJoin() {
        JoinView join = new JoinView();
        join.joinButton().setOnAction(e -> {
            String url = join.serverUrl();
            String room = join.roomCode();
            String name = join.displayName();
            if (url.isEmpty() || room.isEmpty() || name.isEmpty()) {
                return;
            }
            JoinPrefs.save(url, room, name);
            controller.join(url, room, name);
            showCall(room);
        });
        stage.setScene(new Scene(join.getRoot(), 420, 300));

        // Тестовый автодрайв: -Dsertas.autojoin=КОМНАТА/ИМЯ — заполнить поля и
        // войти без кликов. Сервер при этом берётся из -Dsertas.server/env/файла
        // (минуя сохранённые prefs), чтобы тест шёл на явно заданный сервер.
        String auto = System.getProperty("sertas.autojoin", "");
        int slash = auto.indexOf('/');
        if (slash > 0 && slash < auto.length() - 1) {
            join.setServerUrl(ServerConfig.defaultServerUrl());
            join.setRoomCode(auto.substring(0, slash));
            join.setDisplayName(auto.substring(slash + 1));
            Platform.runLater(() -> join.joinButton().fire());
        }
    }

    private void showCall(String room) {
        CallView call = new CallView(room, controller.participants(), controller.videoPane());
        call.muteButton().selectedProperty().addListener((obs, was, muted) -> {
            controller.setMicMuted(muted);
            call.muteButton().setText(muted ? "Микрофон выкл" : "Микрофон вкл");
        });
        call.cameraButton().selectedProperty().addListener((obs, was, on) -> {
            if (on) {
                controller.startCamera();
                call.cameraButton().setText("Выключить камеру");
            } else {
                controller.stopCamera();
                call.cameraButton().setText("Камера");
            }
        });
        // Список камер: при входе и при каждом раскрытии (камеру могли подключить).
        // Выбор во время работы камеры сразу переключает захват.
        ComboBox<VideoDevice> cameras = call.cameraChoice();
        boolean[] refreshing = {false}; // программная смена списка - не выбор пользователя
        Runnable refreshCameras = () -> controller.refreshCameras(list -> {
            VideoDevice keep = CallController.pickCamera(list,
                    cameras.getValue() != null ? cameras.getValue() : controller.selectedCamera(),
                    CallController.savedCameraName());
            refreshing[0] = true;
            try {
                cameras.getItems().setAll(list);
                cameras.setValue(keep);
            } finally {
                refreshing[0] = false;
            }
        });
        refreshCameras.run();
        cameras.setOnShowing(e -> refreshCameras.run());
        cameras.valueProperty().addListener((obs, was, device) -> {
            if (!refreshing[0]) {
                controller.selectCamera(device);
            }
        });

        // Пресет качества: меняет разрешение/FPS показа (идущий показ перезапускается),
        // потолок видео и битрейт звука демонстрации.
        call.qualityChoice().setValue(controller.quality());
        call.qualityChoice().valueProperty().addListener((obs, was, q) -> controller.setQuality(q));
        call.shareButton().selectedProperty().addListener((obs, was, on) -> {
            if (on) {
                controller.startScreenShare();
                call.shareButton().setText("Остановить показ");
            } else {
                controller.stopScreenShare();
                call.shareButton().setText("Демонстрация");
                call.screenAudioButton().setSelected(false);
            }
        });
        // Ошибки контроллера (в т.ч. неудачный старт показа) показываем в окне:
        // stderr в .app-бандле пользователю не виден (уходит в лог-файл).
        controller.setErrorSink(msg -> {
            call.showError(msg);
            if (call.shareButton().isSelected() && !controller.isSharing()) {
                call.shareButton().setSelected(false); // показ не стартовал — откат кнопки
            }
            if (call.cameraButton().isSelected() && !controller.isCameraOn()) {
                call.cameraButton().setSelected(false); // камера не включилась — откат кнопки
            }
        });
        // Звук демо доступен только во время демонстрации экрана.
        call.screenAudioButton().disableProperty().bind(call.shareButton().selectedProperty().not());
        call.screenAudioButton().selectedProperty().addListener((obs, was, on) -> {
            if (on) {
                controller.startScreenAudio();
                if (controller.isScreenAudioOn()) {
                    call.screenAudioButton().setText("Звук демо вкл");
                } else {
                    call.screenAudioButton().setSelected(false); // захват недоступен — откат
                }
            } else {
                controller.stopScreenAudio();
                call.screenAudioButton().setText("Звук демонстрации");
            }
        });
        call.leaveButton().setOnAction(e -> {
            controller.leave();
            showJoin();
        });
        stage.setScene(new Scene(call.getRoot(), 1000, 720));

        // Тестовый автодрайв: -Dsertas.autoshare=СЕК — включить демонстрацию через
        // СЕК секунд после входа; -Dsertas.autodemoaudio=СЕК — затем звук демо
        // (отсчёт от старта демонстрации; требует -Dsertas.demoaudio=on).
        int shareAfter = Integer.getInteger("sertas.autoshare", -1);
        if (shareAfter >= 0) {
            PauseTransition share = new PauseTransition(Duration.seconds(shareAfter));
            share.setOnFinished(e -> call.shareButton().setSelected(true));
            share.play();
            int audioAfter = Integer.getInteger("sertas.autodemoaudio", -1);
            if (audioAfter >= 0) {
                PauseTransition audio = new PauseTransition(Duration.seconds(shareAfter + audioAfter));
                audio.setOnFinished(e -> call.screenAudioButton().setSelected(true));
                audio.play();
            }
        }
    }

    @Override
    public void stop() {
        controller.leave();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

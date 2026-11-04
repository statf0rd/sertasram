package dev.sertas.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Сохраняет последние введённые на экране входа значения (адрес сервера, код
 * комнаты, имя) в {@code ~/.sertas/join.properties} и подставляет их при
 * следующем запуске.
 */
public final class JoinPrefs {

    private JoinPrefs() {}

    private static final Path FILE =
            Path.of(System.getProperty("user.home"), ".sertas", "join.properties");

    public static Properties load() {
        Properties p = new Properties();
        try {
            if (Files.isReadable(FILE)) {
                try (InputStream in = Files.newInputStream(FILE)) {
                    p.load(in);
                }
            }
        } catch (IOException ignored) {
            // нет сохранённых значений — вернём пустые
        }
        return p;
    }

    public static void save(String server, String room, String name) {
        Properties p = new Properties();
        p.setProperty("server", server == null ? "" : server);
        p.setProperty("room", room == null ? "" : room);
        p.setProperty("name", name == null ? "" : name);
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                p.store(out, "sertasram join prefs");
            }
        } catch (IOException ignored) {
            // не критично, если не удалось сохранить
        }
    }
}

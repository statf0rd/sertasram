package dev.sertas.app;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Точка входа для запуска с classpath (портативный бандл). НЕ наследует
 * {@code Application} — иначе JVM-лаунчер падает с «JavaFX runtime components
 * are missing», когда JavaFX лежит на classpath, а не на module-path.
 *
 * <p>{@code -Dsertas.log=ФАЙЛ} - писать вывод приложения в файл. Нужен там, где
 * консоли нет: ярлык установщика Windows запускает {@code javaw.exe}.
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        String log = System.getProperty("sertas.log", "");
        if (!log.isBlank()) {
            try {
                PrintStream out = openLog(Path.of(log));
                System.setOut(out);
                System.setErr(out);
            } catch (IOException | RuntimeException e) {
                System.err.println("[log] не удалось открыть " + log + ": " + e);
            }
        }
        SertasApp.main(args);
    }

    /**
     * Открыть лог на запись; лог прошлого запуска переносится в {@code ИМЯ.prev}.
     * Если перенести нельзя (файл держит другая копия приложения), новый вывод
     * дописывается в конец.
     */
    static PrintStream openLog(Path file) throws IOException {
        Path abs = file.toAbsolutePath();
        Files.createDirectories(abs.getParent());
        boolean append = false;
        if (Files.exists(abs)) {
            try {
                Files.move(abs, abs.resolveSibling(abs.getFileName() + ".prev"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                append = true;
            }
        }
        return new PrintStream(new FileOutputStream(abs.toFile(), append), true, StandardCharsets.UTF_8);
    }
}

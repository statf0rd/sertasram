package dev.sertas.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LauncherLogTest {

    @Test
    void createsMissingDirectories(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("a").resolve("b").resolve("sertasram.log");

        try (PrintStream out = Launcher.openLog(log)) {
            out.println("старт");
        }

        assertEquals("старт" + System.lineSeparator(), Files.readString(log, StandardCharsets.UTF_8));
    }

    @Test
    void previousRunMovesToPrev(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("sertasram.log");
        Files.writeString(log, "прошлый запуск");

        try (PrintStream out = Launcher.openLog(log)) {
            out.print("новый запуск");
        }

        assertEquals("новый запуск", Files.readString(log, StandardCharsets.UTF_8));
        assertEquals("прошлый запуск", Files.readString(dir.resolve("sertasram.log.prev"), StandardCharsets.UTF_8));
    }
}

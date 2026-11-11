package dev.sertas.engine;

import dev.onvoid.webrtc.RTCStatsReport;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Раз в период снимает {@code getStats} со всех соединений звонка и дописывает
 * строки в CSV (FPS, разрешение, битрейт, потери, джиттер, RTT, маскирование
 * аудио) - для настройки пресетов и сравнительных измерений.
 *
 * <p>Включается {@code -Dsertas.stats=on} или переменной {@code SERTAS_STATS=on}:
 * файл {@code ~/.sertas/stats/stats-ГГГГММДД-ЧЧММСС.csv}. Вместо {@code on} можно
 * передать каталог для файлов.
 */
public final class StatsRecorder implements AutoCloseable {

    /**
     * Что опрашивать.
     *
     * @param connection {@code main} - основное соединение (голос, экран, камера);
     *                   {@code demo-audio-out}/{@code demo-audio-in} - звук демонстрации
     */
    public record Target(String connection, String peer, PeerSession session) {}

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter ROW_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private final Path file;
    private final Supplier<List<Target>> targets;
    private final StatsSampler sampler = new StatsSampler();
    private final BufferedWriter out;
    private final ScheduledExecutorService timer;
    private final long startNs = System.nanoTime();
    private boolean closed;        // под this
    private boolean writeFailed;   // под this

    public StatsRecorder(Path file, Supplier<List<Target>> targets, Duration period) throws IOException {
        this.file = file;
        this.targets = targets;
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        out.write(StatsSampler.HEADER);
        out.newLine();
        out.flush();
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sertas-stats");
            t.setDaemon(true);
            return t;
        });
        long ms = period.toMillis();
        timer.scheduleAtFixedRate(this::tick, ms, ms, TimeUnit.MILLISECONDS);
    }

    /**
     * Запустить запись, если она включена настройкой ({@code -Dsertas.stats} /
     * {@code SERTAS_STATS}); иначе {@code null}. Ошибка создания файла не мешает
     * звонку - пишется в лог, возвращается {@code null}.
     */
    public static StatsRecorder startIfEnabled(Supplier<List<Target>> targets) {
        Optional<Path> dir = resolveDir(System.getProperty("sertas.stats"), System.getenv("SERTAS_STATS"),
                Path.of(System.getProperty("user.home")));
        if (dir.isEmpty()) {
            return null;
        }
        Path file = dir.get().resolve("stats-" + LocalDateTime.now().format(FILE_TIME) + ".csv");
        try {
            StatsRecorder recorder = new StatsRecorder(file, targets, Duration.ofSeconds(1));
            System.err.println("[stats] статистика WebRTC пишется в " + file);
            return recorder;
        } catch (IOException e) {
            System.err.println("[stats] не удалось создать " + file + ": " + e);
            return null;
        }
    }

    /**
     * Каталог для CSV по настройке: свойство важнее переменной окружения.
     * {@code on/true/1} - {@code ~/.sertas/stats}; {@code off/false/0}/пусто - выключено;
     * любое другое значение - путь к каталогу.
     */
    static Optional<Path> resolveDir(String property, String env, Path home) {
        String v = property != null && !property.isBlank() ? property.trim()
                : env != null ? env.trim() : "";
        return switch (v.toLowerCase()) {
            case "", "off", "false", "0", "no" -> Optional.empty();
            case "on", "true", "1", "yes" -> Optional.of(home.resolve(".sertas").resolve("stats"));
            default -> Optional.of(Path.of(v));
        };
    }

    public Path file() {
        return file;
    }

    private void tick() {
        try {
            for (Target t : targets.get()) {
                if (t.session() == null) {
                    continue;
                }
                Map<String, String> local = t.session().trackLabels(false);
                Map<String, String> remote = t.session().trackLabels(true);
                long elapsedNs = System.nanoTime() - startNs;
                String time = LocalDateTime.now().format(ROW_TIME);
                t.session().requestStats(report -> onReport(t, report, local, remote, time, elapsedNs));
            }
        } catch (RuntimeException e) {
            // ошибка одного тика (сессия закрылась на ходу) не должна остановить запись
            System.err.println("[stats] тик не удался: " + e);
        }
    }

    /** Нативный поток WebRTC: ничего не бросаем наружу. */
    private void onReport(Target t, RTCStatsReport report, Map<String, String> local,
                          Map<String, String> remote, String time, long elapsedNs) {
        synchronized (this) {
            if (closed) {
                return;
            }
            try {
                for (StatsSampler.Row row : sampler.sample(t.connection(), t.peer(), report, local, remote)) {
                    out.write(row.toCsv(time, elapsedNs / 1e9));
                    out.newLine();
                }
                out.flush(); // файл читается и во время звонка, и после аварийного выхода
            } catch (IOException | RuntimeException e) {
                if (!writeFailed) {
                    writeFailed = true;
                    System.err.println("[stats] запись в " + file + " не удалась: " + e);
                }
            }
        }
    }

    /**
     * Остановить опрос и закрыть файл. Вызывать ДО закрытия соединений: тогда ни
     * один опрос не застанет их на середине закрытия.
     */
    @Override
    public void close() {
        timer.shutdownNow();
        try {
            // Без лока: идущий тик может ждать сигнальный поток, а тот - onReport.
            timer.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                out.close();
            } catch (IOException e) {
                System.err.println("[stats] не удалось закрыть " + file + ": " + e);
            }
        }
    }
}

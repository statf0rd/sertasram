package dev.sertas.signaling;

import dev.sertas.protocol.IceServer;
import dev.sertas.protocol.SignalCodec;
import dev.sertas.protocol.SignalMessage;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket-обёртка над {@link SignalingService}. Сопоставляет id соединения
 * (Javalin sessionId) с {@link WsContext} и рассылает результаты сервиса.
 */
public final class SignalingServer {

    private final SignalingService service = new SignalingService(iceServersFromEnv());
    private final SignalCodec codec = new SignalCodec();
    private final Map<String, WsContext> conns = new ConcurrentHashMap<>();
    private Javalin app;

    // Счётчики для служебной страницы /metrics (формат Prometheus).
    private final long startedAtMillis = System.currentTimeMillis();
    private final AtomicLong connectionsTotal = new AtomicLong();
    private final AtomicLong rejectedTotal = new AtomicLong();
    private final AtomicLong joinsTotal = new AtomicLong();
    private final AtomicLong messagesTotal = new AtomicLong();

    /**
     * Если задан — подключения без совпадающего {@code ?token=} отклоняются.
     * По умолчанию берётся из переменной окружения {@code SERTAS_TOKEN};
     * {@code null} = без авторизации (для локальной разработки).
     */
    private String requiredToken = System.getenv("SERTAS_TOKEN");

    /** Задать обязательный токен программно (например, в тестах). */
    public SignalingServer requireToken(String token) {
        this.requiredToken = token;
        return this;
    }

    public void start(int port) {
        // Javalin 7: маршруты объявляются в конфигурации, до старта сервера.
        app = Javalin.create(config -> {
            // Служебные страницы: /health для проверок после деплоя и HEALTHCHECK
            // контейнера, /metrics для Prometheus. Секретов и имён комнат не отдают.
            config.routes.get("/health", ctx -> ctx.result("ok"));
            config.routes.get("/metrics", ctx -> ctx.contentType("text/plain; version=0.0.4; charset=utf-8").result(metrics()));
            config.routes.ws("/signal", ws -> {
            ws.onConnect(ctx -> {
                if (requiredToken != null && !requiredToken.equals(ctx.queryParam("token"))) {
                    rejectedTotal.incrementAndGet();
                    System.out.println("REJECT(token) " + ctx.sessionId());
                    ctx.closeSession(1008, "unauthorized"); // 1008 = policy violation
                    return;
                }
                // Пинг каждые 5с держит соединение живым через VPN/NAT, которые
                // часто рвут «тихие» TCP за ~10с (иначе клиент молча отваливается).
                ctx.enableAutomaticPings(5, TimeUnit.SECONDS);
                conns.put(ctx.sessionId(), ctx);
                connectionsTotal.incrementAndGet();
                System.out.println("CONNECT " + ctx.sessionId());
            });
            ws.onMessage(ctx -> {
                SignalMessage msg = codec.decode(ctx.message());
                messagesTotal.incrementAndGet();
                if (msg instanceof SignalMessage.Join j) {
                    joinsTotal.incrementAndGet();
                    System.out.println("JOIN " + ctx.sessionId()
                            + " room=[" + j.room() + "] len=" + j.room().length()
                            + " name=[" + j.name() + "]");
                }
                dispatch(service.onMessage(ctx.sessionId(), msg));
            });
            ws.onClose(ctx -> {
                System.out.println("CLOSE " + ctx.sessionId());
                dispatch(service.onDisconnect(ctx.sessionId()));
                conns.remove(ctx.sessionId());
            });
            });
        }).start(port);
    }

    /** Метрики в текстовом формате Prometheus (без внешних библиотек). */
    private String metrics() {
        Runtime rt = Runtime.getRuntime();
        StringBuilder sb = new StringBuilder();
        metric(sb, "sertas_connections_active", "gauge", "Открытые WebSocket-соединения", conns.size());
        metric(sb, "sertas_connections_total", "counter", "Принятые соединения с момента старта", connectionsTotal.get());
        metric(sb, "sertas_rejected_total", "counter", "Соединения, отклонённые из-за неверного токена", rejectedTotal.get());
        metric(sb, "sertas_joins_total", "counter", "Входы в комнаты", joinsTotal.get());
        metric(sb, "sertas_messages_total", "counter", "Принятые сигнальные сообщения", messagesTotal.get());
        metric(sb, "sertas_uptime_seconds", "gauge", "Время работы сервера",
                (System.currentTimeMillis() - startedAtMillis) / 1000);
        metric(sb, "sertas_jvm_memory_used_bytes", "gauge", "Занятая память кучи JVM",
                rt.totalMemory() - rt.freeMemory());
        metric(sb, "sertas_jvm_memory_max_bytes", "gauge", "Предел кучи JVM", rt.maxMemory());
        return sb.toString();
    }

    private static void metric(StringBuilder sb, String name, String type, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        sb.append(name).append(' ').append(value).append('\n');
    }

    private void dispatch(List<Outbound> outs) {
        for (Outbound o : outs) {
            WsContext c = conns.get(o.recipientId());
            if (c == null) {
                continue;
            }
            try {
                c.send(codec.encode(o.message()));
            } catch (RuntimeException ignored) {
                // соединение закрылось между проверкой и отправкой
            }
        }
    }

    /**
     * ICE-серверы для раздачи клиентам: STUN всегда; TURN из env {@code SERTAS_TURN}
     * (формат {@code turn:HOST:3478,USER,PASS}), как UDP+TCP. Креды только здесь.
     */
    private static List<IceServer> iceServersFromEnv() {
        List<IceServer> servers = new ArrayList<>();
        servers.add(new IceServer(List.of("stun:stun.l.google.com:19302"), null, null));
        String turn = System.getenv("SERTAS_TURN");
        if (turn != null && !turn.isBlank()) {
            String[] p = turn.trim().split("[,\\s]+");
            if (p.length >= 3) {
                String url = p[0];
                String tcp = url.contains("?") ? url : url + "?transport=tcp";
                servers.add(new IceServer(List.of(url, tcp), p[1], p[2]));
            }
        }
        return servers;
    }

    public int port() {
        return app.port();
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        new SignalingServer().start(port);
        System.out.println("signaling on ws://localhost:" + port + "/signal");
    }
}

package dev.sertas.signaling;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Служебные страницы, на которые опираются HEALTHCHECK контейнера, деплой и Prometheus. */
class ServiceEndpointsTest {

    static SignalingServer server;
    static int port;

    @BeforeAll
    static void up() {
        server = new SignalingServer();
        server.start(0);
        port = server.port();
    }

    @AfterAll
    static void down() {
        server.stop();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthAnswersOk() throws Exception {
        HttpResponse<String> r = get("/health");
        assertEquals(200, r.statusCode());
        assertEquals("ok", r.body());
    }

    @Test
    void metricsAreInPrometheusTextFormat() throws Exception {
        HttpResponse<String> r = get("/metrics");
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
        String body = r.body();
        assertTrue(body.contains("# TYPE sertas_connections_active gauge"));
        assertTrue(body.contains("# TYPE sertas_connections_total counter"));
        assertTrue(body.contains("sertas_uptime_seconds "));
        // каждая строка без # - это "имя значение"
        for (String line : body.split("\n")) {
            if (!line.startsWith("#")) {
                assertTrue(line.matches("[a-z_]+ \\d+"), "не похоже на метрику: " + line);
            }
        }
    }
}
